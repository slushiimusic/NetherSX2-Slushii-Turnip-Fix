/* fg_source.cpp - see fg_source.h. Copies the GS colour target into an
 * AHardwareBuffer each present and hands it to the frame generator. */

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* vulkan_android.h must precede any volk-style header and needs
 * vulkan_core.h under it; see framegen/src/core/image.cpp for the same
 * ordering trap. Nothing here uses volk, so plain includes suffice. */
#include <mutex>
#include <atomic>
#include <vulkan/vulkan_core.h>
#include <vulkan/vulkan_android.h>

#include "fg_source.h"
#include "capture_slots.hpp"

#define FGI(...) fglog(0, __VA_ARGS__)
#define FGE(...) fglog(1, __VA_ARGS__)

static fg_log_fn g_log = NULL;

static void fglog(int err, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (g_log) g_log(err, buf);
    else __android_log_write(err ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,
                             "VulkanShim", buf);
}

void fg_set_logger(fg_log_fn fn) { g_log = fn; }

static void fg_update_present_rate(uint64_t now_ns);

/* ------------------------------------------------------------------ */
/* JNI handoff                                                         */
/* ------------------------------------------------------------------ */
/* libvulkad.so is loaded with System.loadLibrary from ShimInitProvider,
 * so JNI_OnLoad runs and this is how the VM is reached from the render
 * thread, which is native and not attached by default. */
static JavaVM *g_vm = NULL;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    FGI("VulkanShim: framegen JNI_OnLoad - JavaVM captured");
    return JNI_VERSION_1_6;
}

/* JNI_OnLoad only runs in the copy of this library that Java loaded. The shim
 * is also mapped by the Vulkan loader through linkernsbypass, in a different
 * linker namespace — and that copy, the one running the present hooks, gets its
 * own zeroed statics and therefore a null g_vm. Symptom: frames are "pushed"
 * forever while the pipeline never logs a single pushFrame and sits at
 * posted=0, with no error anywhere, because the handoff returns silently.
 *
 * JNI_GetCreatedJavaVMs reaches the process's one real VM regardless of which
 * copy is asking. */
static void ensure_vm(void) {
    if (g_vm) return;
    static int tried;
    if (tried++) return;
    using GetVMs = jint (*)(JavaVM **, jsize, jsize *);
    GetVMs get = (GetVMs)dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
    if (!get) {
        void *h = dlopen("libnativehelper.so", RTLD_NOW | RTLD_LOCAL);
        if (h) get = (GetVMs)dlsym(h, "JNI_GetCreatedJavaVMs");
    }
    if (!get) {
        FGE("VulkanShim: framegen no JNI_GetCreatedJavaVMs - cannot reach the VM");
        return;
    }
    JavaVM *vms[2] = { NULL, NULL };
    jsize n = 0;
    if (get(vms, 2, &n) == JNI_OK && n > 0 && vms[0]) {
        g_vm = vms[0];
        FGI("VulkanShim: framegen JavaVM recovered via JNI_GetCreatedJavaVMs "
            "(JNI_OnLoad did not run in this copy)");
    } else {
        FGE("VulkanShim: framegen JNI_GetCreatedJavaVMs found no VM (n=%d)", (int)n);
    }
}

static jobject   g_bridge = NULL;    /* NativeBridge.INSTANCE, global ref */
static jmethodID g_push   = NULL;    /* pushFrame(HardwareBuffer, long)   */
static jclass    g_fg_class = NULL;  /* ShimFrameGen, for GS-settle callback */
static jmethodID g_gs_settled = NULL; /* onGsResizeSettledFromNative(II)V */

static void ensure_lsfg_push_direct(void);

/* Bound from Java via nativeSetBridge — never FindClass from a native thread,
 * which gets the system class loader and cannot see app classes. */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetBridge(JNIEnv *env, jclass,
                                                             jobject bridge) {
    if (!bridge) {
        FGE("VulkanShim: framegen nativeSetBridge called with null");
        return;
    }
    if (g_bridge) {
        env->DeleteGlobalRef(g_bridge);
        g_bridge = NULL;
        g_push = NULL;
    }
    g_bridge = env->NewGlobalRef(bridge);
    jclass c = env->GetObjectClass(bridge);
    if (!c) {
        env->ExceptionClear();
        FGE("VulkanShim: framegen nativeSetBridge GetObjectClass failed");
        return;
    }
    g_push = env->GetMethodID(c, "pushFrame",
                              "(Landroid/hardware/HardwareBuffer;J)V");
    if (!g_push) {
        env->ExceptionClear();
        FGE("VulkanShim: framegen nativeSetBridge no pushFrame method");
        return;
    }
    FGI("VulkanShim: framegen bridge bound from Java (pushFrame ready)");
    ensure_lsfg_push_direct();
}

static void fg_hold_capture(void);
static void notify_gs_settled(uint32_t w, uint32_t h);

/* @return a JNIEnv for the calling thread, attaching it if needed. */
static JNIEnv *jni_env(int *attached) {
    *attached = 0;
    ensure_vm();
    if (!g_vm) {
        static int once;
        if (!once++) FGE("VulkanShim: framegen no JavaVM - frames cannot be handed over");
        return NULL;
    }
    JNIEnv *env = NULL;
    jint r = g_vm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, NULL) != JNI_OK) {
            static int once;
            if (!once++) FGE("VulkanShim: framegen AttachCurrentThread failed");
            return NULL;
        }
        *attached = 1;
    } else if (r != JNI_OK) {
        static int once;
        if (!once++) FGE("VulkanShim: framegen GetEnv failed (r=%d)", (int)r);
        return NULL;
    }
    return env;
}

typedef void (*LsfgPushFn)(AHardwareBuffer *, int64_t);
typedef void (*LsfgSkipDupFn)(int);
static LsfgPushFn lsfg_push_direct;
typedef int (*LsfgCaptureInUseFn)(AHardwareBuffer *);
static LsfgCaptureInUseFn lsfg_capture_in_use;
/* fg_panel_rotate -> NATIVE_WINDOW_TRANSFORM_*; -1 leaves the window alone. */
int g_fg_panel_transform = -1;
/* Resolved once in ensure_lsfg_push_direct, kept so the transform can be
 * re-applied LIVE when the device rotates. A static fg_panel_rotate was the
 * whole bug: it stayed at 90 after the panel went back to portrait, so every
 * frame was rotated into a view it no longer matched -- wrong picture AND a
 * per-frame rescale that dragged the emulator down to ~20 fps. */
typedef void (*LsfgWinXformFn)(int);
static LsfgWinXformFn lsfg_set_win_xform = NULL;

/* 1 = IDENTITY preTransform: the compositor rotates and rotate90.comp is
 * skipped. Default. The native-preTransform route costs a full-res compute pass
 * per presented frame and stretches a dimension-swapped image into a swapchain
 * of the opposite orientation, which forced the picture to widescreen. */
int g_fg_pretransform_identity = 1;
typedef void (*LsfgPreXformFn)(int);
static LsfgPreXformFn lsfg_set_pretransform = NULL;

/* Intended display aspect of the game (w/h). 4:3 unless widescreen is on.
 * 0 = stretch to fill (the old behaviour that forced widescreen on everything). */
float g_fg_output_aspect = 4.0f / 3.0f;
typedef void (*LsfgAspectFn)(float);
static LsfgAspectFn lsfg_set_aspect = NULL;

/* 1 = prefer MAILBOX (default), 0 = force FIFO. At a 60 fps source with x2 on a
 * 120 Hz panel, FIFO leaves zero slack -- 60 emulator presents plus 60 injected
 * ones exactly fill the refresh -- so presents block on vblank and the stall
 * propagates into the emulator as ~25 ms hitches. */
int g_fg_prefer_mailbox = 1;
typedef void (*LsfgMailboxFn)(int);
static LsfgMailboxFn lsfg_set_mailbox = NULL;

extern "C" void fg_set_output_aspect(float a) {
    if (a == g_fg_output_aspect) return;
    g_fg_output_aspect = a;
    if (lsfg_set_aspect) {
        lsfg_set_aspect(a);
        FGI("VulkanShim: framegen output aspect -> %.4f (live)", (double)a);
    }
}

/* degrees (0/90/180/270) -> NATIVE_WINDOW_TRANSFORM_*; -1 = leave alone. */
static int fg_transform_for_degrees(int deg) {
    deg = ((deg % 360) + 360) % 360;
    switch (deg) {
        case 90:  return 4;
        case 180: return 3;
        case 270: return 7;
        case 0:   return 0;
        default:  return -1;
    }
}

extern "C" void fg_set_panel_rotate_degrees(int deg) {
    const int t = fg_transform_for_degrees(deg);
    if (t < 0 || t == g_fg_panel_transform) return;
    g_fg_panel_transform = t;
    if (lsfg_set_win_xform) {
        lsfg_set_win_xform(t);
        FGI("VulkanShim: framegen window transform -> %d (live, %d deg)", t, deg);
    }
}
static LsfgSkipDupFn lsfg_skip_dup_direct;
static int lsfg_push_direct_tried;
static int lsfg_skip_dup_tried;

/* libvulkad.so is mapped twice: System.loadLibrary binds nativeSetBridge in one
 * copy, while the Vulkan-loader copy runs fg_capture_and_push. g_bridge stays
 * null here — pushed climbs forever while posted/generated sit at 0. Reach
 * liblsfg-android.so directly; it is loaded once via NativeBridge.load(). */
/* Direct push hands AHardwareBuffers straight to LSFG's bridge, bypassing the
 * Java surface path. It exists because libvulkad.so is mapped twice (see above).
 * It is ALSO the only structural difference from the tree whose framegen runs
 * clean on Adreno 740, and it drives LSFG_3_1::Context::present on the fg-ctx
 * worker -- the thread that dies calling a null volk global. Switchable so the
 * two paths can be A/B'd on a device without a rebuild per attempt:
 *   turnip.conf: fg_push_direct=off  -> use the Java bridge, like the clean tree. */
static int fg_push_direct_allowed = 1;

extern "C" void fg_set_push_direct(int on) {
    fg_push_direct_allowed = on ? 1 : 0;
    /* turnip.conf is parsed AFTER the resolver may already have latched, so the
     * flag alone is not enough -- clear the resolved pointer too, and re-arm the
     * one-shot when switching back on. */
    if (!on) {
        lsfg_push_direct = NULL;
        lsfg_push_direct_tried = 1;
    } else if (!lsfg_push_direct) {
        lsfg_push_direct_tried = 0;
    }
}

static void ensure_lsfg_push_direct(void) {
    if (lsfg_push_direct_tried) return;
    lsfg_push_direct_tried = 1;
    if (!fg_push_direct_allowed) {
        lsfg_push_direct = NULL;
        FGI("VulkanShim: framegen direct push DISABLED (fg_push_direct=off) — "
            "frames go through the Java bridge");
        return;
    }
    void *h = dlopen("liblsfg-android.so", RTLD_NOW | RTLD_NOLOAD);
    if (!h) h = dlopen("liblsfg-android.so", RTLD_NOW);
    if (!h) {
        FGE("VulkanShim: framegen liblsfg-android.so not loaded — cannot push");
        return;
    }
    /* Portrait-native panels: hand LSFG the window transform so its swapchain can
     * keep the panel's NATIVE preTransform (no compositor GPU rotation) while the
     * rotation rides on the window's buffer transform -- the cheap path the host
     * app's own landscape window already uses on such a display. */
    lsfg_set_mailbox = (LsfgMailboxFn)dlsym(h, "lsfg_bridge_set_prefer_mailbox");
    if (lsfg_set_mailbox) {
        lsfg_set_mailbox(g_fg_prefer_mailbox);
        FGI("VulkanShim: framegen present mode -> %s",
            g_fg_prefer_mailbox ? "prefer MAILBOX" : "force FIFO");
    }
    lsfg_set_aspect = (LsfgAspectFn)dlsym(h, "lsfg_bridge_set_output_aspect");
    if (lsfg_set_aspect) {
        lsfg_set_aspect(g_fg_output_aspect);
        FGI("VulkanShim: framegen output aspect -> %.4f",
            (double)g_fg_output_aspect);
    }
    lsfg_set_pretransform =
            (LsfgPreXformFn)dlsym(h, "lsfg_bridge_set_pretransform_identity");
    if (lsfg_set_pretransform) {
        lsfg_set_pretransform(g_fg_pretransform_identity);
        FGI("VulkanShim: framegen preTransform=%s",
            g_fg_pretransform_identity ? "IDENTITY (compositor rotates)"
                                       : "native (app pre-rotates)");
    }
    lsfg_set_win_xform =
            (LsfgWinXformFn)dlsym(h, "lsfg_bridge_set_window_transform");
    if (!lsfg_set_win_xform) {
        FGE("VulkanShim: lsfg_bridge_set_window_transform missing");
    } else if (g_fg_panel_transform >= 0) {
        lsfg_set_win_xform(g_fg_panel_transform);
        FGI("VulkanShim: framegen window transform -> %d", g_fg_panel_transform);
    }
    lsfg_push_direct = (LsfgPushFn)dlsym(h, "lsfg_bridge_push_frame");
    lsfg_capture_in_use = (LsfgCaptureInUseFn)dlsym(h, "lsfg_bridge_capture_in_use");
    if (lsfg_push_direct)
        FGI("VulkanShim: framegen using lsfg_bridge_push_frame (loader copy)");
    else
        FGE("VulkanShim: framegen lsfg_bridge_push_frame missing in liblsfg-android.so");
    if (!lsfg_skip_dup_tried) {
        lsfg_skip_dup_tried = 1;
        lsfg_skip_dup_direct = (LsfgSkipDupFn)dlsym(h, "lsfg_bridge_set_skip_duplicate_capture");
        if (lsfg_skip_dup_direct) {
            lsfg_skip_dup_direct(1);
            FGI("VulkanShim: framegen skip-duplicate-capture on (loader copy)");
        }
    }
}

static void push_to_java(AHardwareBuffer *ahb, int64_t ts) {
    ensure_lsfg_push_direct();
    if (lsfg_push_direct) {
        lsfg_push_direct(ahb, ts);
        return;
    }
    if (!g_bridge || !g_push) {
        static int once;
        if (!once++) FGE("VulkanShim: framegen bridge not bound - nativeSetBridge "
                         "was not called from Java");
        return;
    }
    int attached = 0;
    JNIEnv *env = jni_env(&attached);
    if (!env) return;
    jobject hb = AHardwareBuffer_toHardwareBuffer(env, ahb);
    if (!hb) {
        static int once;
        if (!once++) FGE("VulkanShim: framegen AHardwareBuffer_toHardwareBuffer "
                         "returned null - nothing can be pushed");
    } else {
        env->CallVoidMethod(g_bridge, g_push, hb, (jlong)ts);
        /* Report rather than swallow. Clearing silently is why a pipeline
         * that initialised cleanly and was handed 3300 frames could sit at
         * posted=0 with nothing in any log. */
        if (env->ExceptionCheck()) {
            static int reported;
            if (!reported++) {
                FGE("VulkanShim: framegen pushFrame threw - see the trace below");
                env->ExceptionDescribe();
            }
            env->ExceptionClear();
        }
        env->DeleteLocalRef(hb);
    }
    if (attached) g_vm->DetachCurrentThread();
}

/* ------------------------------------------------------------------ */
/* State                                                               */
/* ------------------------------------------------------------------ */
/* Default capture size; overridden from turnip.conf via fg_set_capture_size
 * before the first build(). Must match ShimFrameGen initContext dimensions. */
#define FG_CAPTURE_W_DEFAULT 480
#define FG_CAPTURE_H_DEFAULT 360

#define FG_SLOTS 4   /* GPU copy + worker + bounded queue, with no producer wait */

static struct {
    int enabled;
    int failed;
    int built;

    uint32_t cap_w;
    uint32_t cap_h;

    VkDevice dev;
    PFN_vkGetDeviceProcAddr gpa;
    VkQueue queue;
    uint32_t family;
    VkPhysicalDeviceMemoryProperties memprops;
    int have_memprops;

    uint64_t gs_image;
    uint32_t gs_w, gs_h, gs_fmt;

    AHardwareBuffer *ahb[FG_SLOTS];
    VkImage          img[FG_SLOTS];
    VkDeviceMemory   mem[FG_SLOTS];
    VkCommandBuffer  cb[FG_SLOTS];
    VkFence          fence[FG_SLOTS];
    CaptureSlot      capture[FG_SLOTS];
    int              slot;

    VkCommandPool pool;
    uint32_t w, h;
    uint64_t pushed, skipped;
    /* 1 = blit the presented swapchain image instead of the latched GS target. */
    int capture_from_present;
    int fg_force;
    int throttle_active;
    int suppress_reason;
    uint64_t rate_window_start_ns;
    uint32_t rate_window_presents;
} S;

static PFN_vkCreateImage            p_vkCreateImage;
static PFN_vkDestroyImage           p_vkDestroyImage;
static PFN_vkAllocateMemory         p_vkAllocateMemory;
static PFN_vkFreeMemory             p_vkFreeMemory;
static PFN_vkBindImageMemory        p_vkBindImageMemory;
static PFN_vkGetImageMemoryRequirements p_vkGetImageMemoryRequirements;
static PFN_vkCreateCommandPool      p_vkCreateCommandPool;
static PFN_vkAllocateCommandBuffers p_vkAllocateCommandBuffers;
static PFN_vkBeginCommandBuffer     p_vkBeginCommandBuffer;
static PFN_vkEndCommandBuffer       p_vkEndCommandBuffer;
static PFN_vkResetCommandBuffer     p_vkResetCommandBuffer;
static PFN_vkCmdPipelineBarrier     p_vkCmdPipelineBarrier;
static PFN_vkCmdBlitImage           p_vkCmdBlitImage;
static PFN_vkCmdClearColorImage     p_vkCmdClearColorImage;
/* fg_test_pattern=on in turnip.conf: write a colour that CHANGES every frame
 * instead of blitting the game. Purely diagnostic. If LSFG's unique-capture
 * count climbs under this, the AHB write path and LSFG's comparison are both
 * sound and the fault is the SOURCE image; if it stays 0, our writes never reach
 * the memory LSFG reads. Five different source-selection strategies have now
 * failed the same way, so this settles which half is broken. */
int g_fg_test_pattern = 0;
static PFN_vkQueueSubmit            p_vkQueueSubmit;
static PFN_vkCreateFence            p_vkCreateFence;
static PFN_vkDestroyFence           p_vkDestroyFence;
static PFN_vkGetFenceStatus         p_vkGetFenceStatus;
static PFN_vkResetFences            p_vkResetFences;
static PFN_vkWaitForFences          p_vkWaitForFences;
static PFN_vkDestroyCommandPool     p_vkDestroyCommandPool;

void fg_set_enabled(int on) {
#ifdef PINK_NO_FRAMEGEN
    (void)on;
    S.enabled = 0;
#else
    S.enabled = on ? 1 : 0;
#endif
}
int  fg_enabled(void) { return S.enabled && !S.failed; }

void fg_set_suppress_reason(int reason) { S.suppress_reason = reason; }
int  fg_suppress_reason(void) { return S.suppress_reason; }
int  fg_throttled(void) { return S.throttle_active; }

static void fg_reset_capture(void);   /* defined below; needed by the setter */

/* Guards S.gs_image between the capture reading it and the queue submit that
 * uses it, against the vkDestroyImage hook freeing it underneath, and guards
 * capture teardown against a capture in flight. Defined up here because
 * fg_set_capture_size below needs it. */
static std::recursive_mutex g_latch_mu;
static std::atomic<int> capture_pause_reasons{0};
static const int LEGACY_PAUSE = 16;
static int capture_paused() { return capture_pause_reasons.load() != 0; }

/**
 * Set the capture size, REBUILDING the AHBs when they already exist.
 *
 * This used to open with a bare `if (S.built) return;` — silent, no log, no
 * return code — so once the capture buffers existed the size was frozen for the
 * rest of the session. Java calls this from three places and never followed it
 * with a reset; nativeResetCapture(), the escape hatch, had no call sites at
 * all. And fg_reset_capture() does not clear cap_w/cap_h, so even a rebuild
 * re-adopted the stale size.
 *
 * The consequence was the whole "crop" family. The capture AHB kept the size it
 * was first built at (typically the 480x360 default, before the GS extent is
 * known) while LSFG's context was rebuilt at the real GS size. LSFG copies the
 * AHB into its input slot 1:1 from the TOP-LEFT, clamped to the min of the two
 * extents — so an oversized AHB is cropped, and that crop is then blown up to
 * fill the panel. Measured 2026-08-16: AHB 480x360 against a 384x336 context
 * gives x1.250 y1.071, and the panel showed x1.252 y1.061 with the minimap
 * pushed off the bottom-right corner. Intermittent because it depended on
 * whether the AHB rebuild won the race against Java's size push.
 *
 * Root-caused by a parallel review of the whole capture chain, 2026-08-16.
 */
void fg_set_capture_size(uint32_t w, uint32_t h) {
    if (w < 256 || h < 224) return;
    w &= ~1u;
    h &= ~1u;
    if (w == S.cap_w && h == S.cap_h) return;
    uint32_t ow = S.cap_w, oh = S.cap_h;
    S.cap_w = w;
    S.cap_h = h;
    if (S.built) {
        /* Drop the buffers; the next present rebuilds them at the new size.
         * Without this the setter was a no-op and every counter still agreed. */
        FGI("VulkanShim: framegen capture size %ux%u -> %ux%u — rebuilding the "
            "capture buffers (they were already built)",
            (unsigned)ow, (unsigned)oh, (unsigned)w, (unsigned)h);
        /* Same lock the capture holds, and the same lock the reject path takes.
         * This runs on the Android MAIN thread (ShimFrameGen.trackCaptureToGs ->
         * nativeSetCaptureSize) while the GS thread can be inside
         * fg_capture_and_push recording into S.cb[i] and submitting S.fence[i].
         * Tearing those down underneath it is the exact shape of the tombstone
         * that took the app down at 23:18 on 2026-08-16. */
        std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
        fg_reset_capture();
    } else {
        FGI("VulkanShim: framegen capture size set to %ux%u", S.cap_w, S.cap_h);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetCaptureSize(JNIEnv *, jclass,
                                                                jint w, jint h) {
    fg_set_capture_size((uint32_t)w, (uint32_t)h);
}

/* The image vkQueuePresentKHR was handed this frame, and the panel extent.
 *
 * Written by the present hook immediately before fg_capture_and_push on the
 * same thread, so no lock: this is not shared state, it is an argument that
 * could not be passed as one without changing a C ABI the FSR path also uses. */
static uint64_t present_image;
/* Set once a real presentable image has been handed to us. The shim only records
 * those when fg_capture_src=swapchain, because capturing from the swapchain needs
 * TRANSFER_SRC usage, and forcing that drops UBWC and produced full-screen stride
 * garbage on Adreno 650. So on the default GS route this stays 0 forever. */
static int present_seen;

/* ACTUAL layout of tracked images, fed by the shim's vkCmdPipelineBarrier hook.
 *
 * The capture used to ASSUME a layout (SHADER_READ_ONLY for the GS route,
 * PRESENT_SRC for the swapchain route). If the assumption is wrong, the
 * transition to TRANSFER_SRC declares a mismatched oldLayout and Vulkan is
 * entitled to discard the contents -- which yields a destination that never
 * changes. That is exactly what "N captures/s but 0 unique" was, and it happened
 * for BOTH sources, which is why chasing source selection never helped. The
 * measured transitions on one GS target were 0->2, 2->7, 7->5, 2->5, so the
 * assumed constant was wrong a large part of the time. */
#define FG_LAYOUT_N 64
static uint64_t layout_img[FG_LAYOUT_N];
static uint32_t layout_val[FG_LAYOUT_N];
static int layout_n;

extern "C" void fg_note_image_layout(uint64_t img, uint32_t layout) {
    if (!img) return;
    for (int i = 0; i < layout_n; i++)
        if (layout_img[i] == img) { layout_val[i] = layout; return; }
    static int rr;
    int slot = layout_n < FG_LAYOUT_N ? layout_n++ : (rr++ % FG_LAYOUT_N);
    layout_img[slot] = img;
    layout_val[slot] = layout;
}

/* Known layout for img, or `fallback` when we have never seen a barrier for it. */
static uint32_t fg_layout_of(uint64_t img, uint32_t fallback) {
    for (int i = 0; i < layout_n; i++)
        if (layout_img[i] == img) return layout_val[i];
    return fallback;
}

static uint32_t present_w, present_h;

void fg_notify_device_lost(void) {
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    if (!S.dev && !S.built) {
        /* The silent path. It matters which one ran: a second game that comes
         * up with built=0 looks identical whether this fired or was skipped. */
        FGI("VulkanShim: framegen device-lost notice ignored (dev=%p built=%d)",
            (void *)S.dev, S.built);
        return;
    }
    FGI("VulkanShim: framegen device destroyed — discarding the pool, command "
        "buffers, fences and images built on it");
    for (int i = 0; i < FG_SLOTS; i++) {
        /* AHBs are NOT device objects; they are ours to release. Everything
         * else died with the device and must only be forgotten. */
        if (S.ahb[i]) { AHardwareBuffer_release(S.ahb[i]); S.ahb[i] = NULL; }
        S.img[i]   = VK_NULL_HANDLE;
        S.mem[i]   = VK_NULL_HANDLE;
        S.cb[i]    = VK_NULL_HANDLE;
        S.fence[i] = VK_NULL_HANDLE;
        S.capture[i] = {};
    }
    S.pool   = VK_NULL_HANDLE;
    S.built  = 0;
    S.slot   = 0;
    S.dev    = VK_NULL_HANDLE;
    S.queue  = VK_NULL_HANDLE;   /* re-noted from the new vkGetDeviceQueue */
    S.family = 0;
    S.gs_image = 0;              /* that target died with its device too */
    present_image = 0;           /* and so did the presentable images */
    S.failed = 0;                /* a fresh device deserves a fresh try */
}

/* RECORD WHAT THE DRIVER HANDS US EVEN WHILE CAPTURE IS OFF.
 *
 * This used to open with `if (!S.enabled) return;` and that is the whole
 * "framegen dies on the second game" bug. Exiting a game runs
 * nativeSetCaptureEnabled(false); the emulator then destroys its VkDevice and
 * creates a new one for the next game, and the Java side only re-enables
 * capture once its own pipeline is back up. vkCreateDevice therefore lands
 * INSIDE the disabled window: the shim binds correctly and logs "framegen
 * bound to emulator device", this function drops the handle on the floor, and
 * S.dev stays NULL for the life of the process. build() then returns 0 at its
 * `!S.dev` guard forever -- "capture idle ... built=0", nothing pushed, nothing
 * generated, while every other field looks perfectly healthy.
 *
 * The tell was fg_note_render_queue, the ONE setter here with no enable gate:
 * it was the one field that came back populated (queue=0xb4..., dev=0x0).
 *
 * Recording a handle costs nothing and starts nothing. Every behavioural gate
 * is elsewhere -- fg_enabled(), the capture path, build() itself -- so the
 * enable flag has no business filtering what we are simply told. */
void fg_set_device(void *device, void *device_gpa) {
    /* A NEW DEVICE MEANS EVERYTHING WE HOLD IS DEAD.
     *
     * Exiting a game and starting another without leaving the app destroys the
     * emulator's Vulkan device and creates a fresh one. Every handle here —
     * command pool, command buffers, fences, images, memory — belonged to the
     * old one, and S.queue was worse still: fg_note_queue early-returns once
     * set, so it pointed at the dead device's queue forever.
     *
     * Nothing errors. The submits simply never complete, so the fences never
     * signal, so every frame after the first two takes the "previous copy still
     * in flight" skip. Measured on the second game of a session:
     *   FRAMEGEN no source ... pushed 2150  skipped 7111  posted 0  gen 0
     *
     * Drop them WITHOUT destroying: the objects died with their device and
     * calling vkDestroy* against a destroyed device is undefined. The AHBs are
     * not device objects, so those we do release. memprops is kept — it comes
     * from the PHYSICAL device, which has not changed. */
    FGI("VulkanShim: framegen fg_set_device %p -> %p (gpa %p, built=%d, "
        "enabled=%d)", (void *)S.dev, device, device_gpa, S.built, S.enabled);
    if (S.dev && device && (VkDevice)device != S.dev) fg_notify_device_lost();
    S.dev = (VkDevice)device;
    S.gpa = (PFN_vkGetDeviceProcAddr)device_gpa;
}

void fg_note_queue(void *queue, uint32_t family) {
    /* No enable gate -- see fg_set_device. A queue noted while capture is off
     * is exactly the queue the next game needs. */
    if (!queue) return;
    if (S.queue == (VkQueue)queue) return;
    /* Lock in the emulator queue once fg_set_device has bound. Before that,
     * allow replacement so GetDeviceQueue-before-GetDeviceProcAddr order works. */
    if (S.queue && S.dev) return;
    S.queue = (VkQueue)queue;
    S.family = family;
}

/* The queue the EMULATOR actually submits rendering on, observed from the shim's
 * vkQueueSubmit hook.
 *
 * The capture submit carries no semaphores; its ordering rests entirely on the
 * comment at the submit site -- "submitted on the same queue, so queue order
 * alone puts it after that work". Nothing verified that. fg_note_queue() locks in
 * whichever queue vkGetDeviceQueue handed over FIRST, and if the core renders on
 * a different one there is no ordering at all: the capture reads the target
 * before the frame is drawn, which returns black for any source, at any layout.
 * That is the shape of the surviving "N captures/s but 0 unique" bug. */
extern "C" void fg_note_render_queue(void *queue, uint32_t family) {
    if (!queue || S.queue == (VkQueue)queue) return;
    FGI("VulkanShim: framegen capture queue corrected: %p -> %p (the queue the "
        "emulator submits rendering on)", (void *)S.queue, queue);
    S.queue = (VkQueue)queue;
    if (family != 0xFFFFFFFFu) S.family = family;
}

void fg_note_memory_properties(const void *props) {
    /* No enable gate -- see fg_set_device. These come from the PHYSICAL device
     * and survive a device recreation, so this only ever ran once anyway; it
     * simply must not be the disabled window that it misses. */
    if (S.have_memprops || !props) return;
    memcpy(&S.memprops, props, sizeof(S.memprops));
    S.have_memprops = 1;
}

static int resolve_fns(void);

/* Same probe as hooked_CreateImage in vulkan_shim.cpp, plus a height check
 * that rejects 512x434 scratch copies while accepting every real IR step. */
static int fg_is_valid_gs_extent(uint32_t w, uint32_t h) {
    /* Sub-native IR (0.5x, 0.75x) renders BELOW both floors — 384x336 is
     * 129,024 px, well under the 200,000 scratch cut. Waive the size floors
     * only when the extent is exactly what the chosen IR implies; the aspect
     * and height checks below still apply either way. */
    int expected = fg_expected_gs_is(w, h);
    if (!expected && (w < 256u || h < 224u)) return 0;
    if (!expected && (uint64_t)w * h < 200000u) return 0;   /* 256x256 etc. */
    /* Scale from HEIGHT; width may legitimately be 512, 640 or 704.
     * The old form derived the scale as w/512 and demanded h/448 agree, which
     * rejected every 640-wide game (GTA:SA draws 640x448 -> 1.25 vs 1.00). The
     * shim-side classifier in vulkan_shim.cpp had the identical assumption, so
     * both layers had to change: fixing one alone still drops the frame. */
    float sy = (float)h / 448.f;
    if (sy < 0.45f || sy > 8.1f) return 0;
    static const float kBaseW[] = { 512.f, 640.f, 704.f };
    int aspect_ok = 0;
    for (unsigned bi = 0; bi < sizeof(kBaseW) / sizeof(kBaseW[0]); bi++) {
        float d = (float)w / kBaseW[bi] - sy;
        if (d < 0.f) d = -d;
        if (d <= 0.08f) { aspect_ok = 1; break; }
    }
    if (!aspect_ok) return 0;
    return 1;
}

/* IR applySettings bounces GS through transient sizes (e.g. 768→512→768 in
 * ~80ms). Committing each hop tears down capture and leaves LSFG on stale
 * flow state — horizontal streak / stride garbage while OSD still reads live. */
/* LAST-RESORT backstop only. Java's watchdogStuckPause owns this recovery and
 * acts at 5 s: it knows whether an IR change is legitimately in flight
 * (irRecovering / irPausePending), and this layer does not. An unpause during a
 * real IR change is how the half-size-square and wrong-framing bugs happen, so
 * this must never be the one that fires first -- it exists only for the case
 * where the Java tick loop itself is dead, and is deliberately set well above
 * Java's 5 s so a healthy system never reaches it. */
static const uint64_t PAUSE_GRACE_NS = 10000000000ULL; /* 10 s */
/* When the current pause began; 0 = not paused. */
static std::atomic<uint64_t> pause_since_ns{0};
static uint32_t resize_pending_w = 0;
static uint32_t resize_pending_h = 0;
static uint64_t resize_pending_ns = 0;
static const uint64_t RESIZE_DEBOUNCE_NS = 200000000ULL; /* 200 ms */

/* Last time the CURRENT GS extent was actually rendered into, from the render
 * pass hook — not from vkCreateImage. A downshift is only believable once the
 * bigger target has gone quiet for this long. */
static uint64_t gs_seen_ns = 0;
static const uint64_t GS_QUIET_NS = 1500000000ULL; /* 1.5 s */
/* Separate clock for the stale-latch guard in fg_capture_and_push. It must
 * never share gs_seen_ns — see the comment there. */
static uint64_t latch_drop_ns = 0;
/* When the LATCHED handle was last reported. Distinct from gs_seen_ns, which is
 * "something at this extent was rendered" and is refreshed by sibling passes. */
static uint64_t gs_img_seen_ns = 0;
/* The distinct handles seen at the display extent this window, and the last one
 * seen — diagnostics for how contended the extent actually is. */
/* Diagnostic bookkeeping in the per-render-pass and per-present paths. Off while
 * bisecting a hitching regression — see the perf note. */
#define FG_DIAG_DISTINCT 1
#define FG_DISTINCT_MAX 16
static uint64_t gs_distinct_last = 0;
static uint64_t gs_distinct_set[FG_DISTINCT_MAX];
static uint32_t gs_distinct_n = 0;

/* Handles that went on being reported as the GS target while producing a
 * completely static image. Dropping the latch alone does not help against one
 * of these — fg_note_current_gs simply re-takes the same handle on the next
 * frame, which is exactly what was observed: the frozen-source watchdog fired
 * seven times in a row, 30 s apart, each time re-latching image
 * 12970367404709386096 while the game rendered elsewhere (2026-08-17). So a
 * handle that survives one drop still frozen is refused for a while, which lets
 * a different target latch.
 *
 * Expiring rather than permanent: a genuinely static scene (pause menu, idle
 * rooftop) is a known false positive of that watchdog, and must not disable
 * framegen for the rest of the session. */
static uint64_t fg_monotonic_ns(void);   /* defined below */
#define FG_DEAD_N 4
static uint64_t dead_img[FG_DEAD_N];
static uint64_t dead_ns[FG_DEAD_N];
static const uint64_t DEAD_TTL_NS = 15000000000ULL;   /* 15 s */

static int fg_img_rejected(uint64_t img) {
    if (!img) return 0;
    uint64_t now = fg_monotonic_ns();
    for (int i = 0; i < FG_DEAD_N; i++)
        if (dead_img[i] == img && now - dead_ns[i] < DEAD_TTL_NS) return 1;
    return 0;
}

/* First tick of an unbroken idle run; 0 while capture is succeeding. */
static uint64_t fg_idle_since_ns;

/* Forget every rejection. Only called when the reject list is demonstrably the
 * thing keeping capture wedged. */
static void fg_clear_rejects(void) {
    for (int i = 0; i < FG_DEAD_N; i++) { dead_img[i] = 0; dead_ns[i] = 0; }
}

static void fg_reject_img(uint64_t img) {
    if (!img) return;
    static int rr;
    int slot = rr++ % FG_DEAD_N;
    dead_img[slot] = img;
    dead_ns[slot] = fg_monotonic_ns();
}

/* The GS extent implied by the user's Internal Resolution, pushed from Java.
 *
 * Shape alone cannot identify the GS. After changing IR a few times PCSX2 still
 * holds render targets from every resolution visited, and they ALL satisfy the
 * 512:448 test — measured as a storm of "GS resize pending 512x448 -> 1024x896
 * / 1280x1120 / 1536x1344 / 640x560" many times per millisecond, which flipped
 * the pending size faster than the 200 ms debounce could ever commit. Capture
 * then sits behind `resize_pending_w` forever and framegen reads "paused".
 *
 * The IR the user chose settles it: 512x448 x IR is the one target that is the
 * display. Advisory, not law — if the expected extent never shows up (a
 * per-game override, a pref the shim did not write), the gate opens after
 * EXPECT_GRACE_NS so a wrong hint can never brick framegen. */
static uint64_t fg_monotonic_ns(void);

static uint32_t exp_w = 0, exp_h = 0;
static uint64_t exp_seen_ns = 0;
static const uint64_t EXPECT_GRACE_NS = 5000000000ULL; /* 5 s */

static int fg_extent_expected(uint32_t w, uint32_t h) {
    if (!exp_w || !exp_h) return 1;
    uint32_t dw = w > exp_w ? w - exp_w : exp_w - w;
    uint32_t dh = h > exp_h ? h - exp_h : exp_h - h;
    if (dw <= 8 && dh <= 8) {
        exp_seen_ns = fg_monotonic_ns();
        return 1;
    }
    return exp_seen_ns && fg_monotonic_ns() - exp_seen_ns > EXPECT_GRACE_NS;
}

/* Does this extent match the GS size the user's Internal Resolution implies?
 *
 * The size floors elsewhere exist to reject scratch targets, and they were
 * written on the assumption that IR only ever scales 512x448 UP. This build's
 * picker offers 0.5x and 0.75x, where the REAL display target is 256x224 or
 * 384x336 — below every floor, so it was dropped as scratch and capture stayed
 * latched to the previous resolution's retired buffer. The expectation is the
 * authority for those cases: it comes from the pref the user just set.
 *
 * Still safe against the scratch buffer that motivated the floors: a 256x244
 * scratch does not match a 256x224 expectation inside 8px. */
int fg_expected_gs_is(uint32_t w, uint32_t h) {
    if (!exp_w || !exp_h) return 0;
    uint32_t dw = w > exp_w ? w - exp_w : exp_w - w;
    uint32_t dh = h > exp_h ? h - exp_h : exp_h - h;
    return dw <= 8 && dh <= 8;
}

void fg_set_expected_gs(uint32_t w, uint32_t h) {
    if (w == exp_w && h == exp_h) return;
    exp_w = w;
    exp_h = h;
    exp_seen_ns = fg_monotonic_ns();
    FGI("VulkanShim: framegen expected GS %ux%u (from Internal Resolution)",
        (unsigned)w, (unsigned)h);
}

static uint64_t fg_monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static void fg_hold_capture(void) {
    S.gs_image = 0;
}

static void fg_reset_capture(void);

static void fg_try_commit_resize(void) {
    if (!resize_pending_w || !resize_pending_h) return;
    uint64_t now = fg_monotonic_ns();
    if (now - resize_pending_ns < RESIZE_DEBOUNCE_NS) return;
    /* Same-size pending is a no-op (GS reported current extent again).
     * Do NOT notify_gs_settled here — that fired hot reload before
     * applySettings reopened GS at the new IR, leaving a permanent
     * half-size square with generated=0. */
    if (resize_pending_w == S.gs_w && resize_pending_h == S.gs_h) {
        resize_pending_w = resize_pending_h = 0;
        return;
    }
    FGI("VulkanShim: framegen GS resize settled %ux%u -> %ux%u",
        S.gs_w, S.gs_h, resize_pending_w, resize_pending_h);
    S.gs_w = resize_pending_w;
    S.gs_h = resize_pending_h;
    /* Drop the latched source with the extent it belonged to.
     *
     * The blit takes its SOURCE rect from S.gs_w/S.gs_h and its dest rect from
     * the capture size, so a latched image that is larger than the committed
     * extent is not rescaled — it is CROPPED, top-left, and then stretched to
     * fill the panel. That is a live, moving, plausible-looking picture with
     * every counter healthy, which is why it survived so long: measured
     * 2026-08-16 as a HUD scaled by exactly 1024/768 after a commit to 768x672
     * that PCSX2 had never actually rendered.
     *
     * Nothing re-latches until an image whose extent MATCHES the new one shows
     * up (see the guard in fg_note_current_gs), so if the commit was wrong the
     * panel goes to "no source" instead of quietly lying about the framing. */
    S.gs_image = 0;
    /* Treat the new extent as freshly alive, or the very next smaller
     * candidate would see gs_seen_ns from the OLD extent and look quiet. */
    gs_seen_ns = fg_monotonic_ns();
    resize_pending_w = resize_pending_h = 0;
    fg_reset_capture();
    /* Java gates on irPausePending — must fire even when capture was unpaused
     * on activity resume before the debounced size landed. */
    notify_gs_settled(S.gs_w, S.gs_h);
}

static void fg_reset_capture(void) {
    fg_hold_capture();
    if (!S.built) return;
    if (S.dev && S.gpa)
        resolve_fns();
    /* Destroying a fence, image, memory or pool that a pending submission still
     * references is undefined behaviour (VUID-vkDestroyImage-image-01000), and
     * the loop below did exactly that — it only zeroed S.capture[i].submitted.
     * p_vkWaitForFences was resolved and never once used. Bounded at 100 ms so a
     * wedged GPU cannot hang the main thread. */
    for (int i = 0; i < FG_SLOTS; i++) {
        if (S.capture[i].submitted && S.fence[i] && p_vkWaitForFences)
            p_vkWaitForFences(S.dev, 1, &S.fence[i], VK_TRUE, 100000000ULL);
    }
    for (int i = 0; i < FG_SLOTS; i++) {
        if (S.fence[i] && p_vkDestroyFence) {
            p_vkDestroyFence(S.dev, S.fence[i], NULL);
            S.fence[i] = VK_NULL_HANDLE;
        }
        if (S.img[i] && p_vkDestroyImage) {
            p_vkDestroyImage(S.dev, S.img[i], NULL);
            S.img[i] = VK_NULL_HANDLE;
        }
        if (S.mem[i] && p_vkFreeMemory) {
            p_vkFreeMemory(S.dev, S.mem[i], NULL);
            S.mem[i] = VK_NULL_HANDLE;
        }
        if (S.ahb[i]) {
            AHardwareBuffer_release(S.ahb[i]);
            S.ahb[i] = NULL;
        }
        S.capture[i] = {};
    }
    if (S.pool && p_vkDestroyCommandPool) {
        p_vkDestroyCommandPool(S.dev, S.pool, NULL);
        S.pool = VK_NULL_HANDLE;
    }
    S.built = 0;
    S.failed = 0;
    S.slot = 0;
    FGI("VulkanShim: framegen capture reset (GS internal resolution changed)");
}

void fg_notify_gs_resize(uint32_t w, uint32_t h) {
    if (!fg_is_valid_gs_extent(w, h)) return;
    if (!fg_extent_expected(w, h)) return;
    if (w == S.gs_w && h == S.gs_h && !resize_pending_w) return;
    /* IGNORE TRIVIAL JITTER. Some titles wobble their render target by a couple
     * of pixels between frames -- Thrillville measured 1280x890, 1280x892 and
     * 1280x896 in one session. Treating each as a resolution change tears down
     * and rebuilds the capture AHBs and the LSFG context over and over, which
     * costs far more than the 6 rows it is chasing and shows up as the picture
     * breaking up. A real IR change moves the extent by a large factor, never by
     * two pixels, so anything within 16px of the current extent keeps the
     * existing buffers; the blit already scales into them. */
    if (S.gs_w && S.gs_h && !resize_pending_w) {
        uint32_t dw = w > S.gs_w ? w - S.gs_w : S.gs_w - w;
        uint32_t dh = h > S.gs_h ? h - S.gs_h : S.gs_h - h;
        if (dw <= 16u && dh <= 16u) {
            static uint32_t last_w, last_h;
            if (w != last_w || h != last_h) {
                last_w = w; last_h = h;
                FGI("VulkanShim: framegen ignoring %ux%u jitter (holding %ux%u)",
                    (unsigned)w, (unsigned)h, (unsigned)S.gs_w, (unsigned)S.gs_h);
            }
            return;
        }
    }
    /* A SMALLER valid extent is ambiguous: it is either an IR decrease or a
     * half-resolution pass the game runs alongside the real target. Ultimate
     * Spider-Man at IR 2.75 renders 704x610 next to the GS's 1408x1232 —
     * exactly half, perfect 512:448 aspect, above the scratch floor, and it
     * repeats every frame, so "seen twice in a row" adopts it. The result was
     * a permanent black panel: the extent flip-flopped, tracking was dropped
     * on each hop, and capture stayed latched to a target nothing renders into.
     *
     * Liveness settles it. A real IR change means the old target STOPS being
     * rendered; a sibling pass leaves it rendering every frame.
     *
     * BOTH DIRECTIONS since 2026-08-16. This used to guard downshifts only, so
     * a LARGER stale target was adopted on sight — and PCSX2 keeps the targets
     * from every resolution the session has visited. Measured while toggling
     * OSD settings at IR 1.5: `capture follows GS: 768x672 -> 1024x896` (the
     * retired 2x target) and back 200 ms later, twice, each flip costing a full
     * context rebuild. That 200 ms flip-back is itself the proof that 768x672
     * was still live when 1024x896 was taken. Costs up to GS_QUIET_NS of
     * latency on a genuine IR increase, dwarfed by the rebuild that follows. */
    if (S.gs_w && S.gs_h && w * (uint64_t)h != (uint64_t)S.gs_w * S.gs_h) {
        uint64_t quiet = fg_monotonic_ns() - gs_seen_ns;
        if (gs_seen_ns && quiet < GS_QUIET_NS) {
            static uint32_t last_w, last_h;
            if (w != last_w || h != last_h) {
                last_w = w; last_h = h;
                FGI("VulkanShim: framegen ignoring %ux%u — %ux%u still live "
                    "(%llu ms ago)", (unsigned)w, (unsigned)h,
                    (unsigned)S.gs_w, (unsigned)S.gs_h,
                    (unsigned long long)(quiet / 1000000ULL));
            }
            return;
        }
    }
    if (w == resize_pending_w && h == resize_pending_h) {
        fg_try_commit_resize();
        return;
    }
    FGI("VulkanShim: framegen GS resize pending %ux%u -> %ux%u",
        S.gs_w, S.gs_h, w, h);
    resize_pending_w = w;
    resize_pending_h = h;
    resize_pending_ns = fg_monotonic_ns();
    fg_hold_capture();
    if (S.built) fg_reset_capture();
}

/* The driver is about to free this image. If capture is latched to it, let go
 * NOW — a blit from a destroyed VkImage is undefined behaviour, and what it
 * actually produced was a black panel at a flawless frame rate that no guard
 * could see: the render-pass hook kept resolving the retired framebuffer to
 * this same handle, so "the GS target went unreported" never became true.
 * Capture holds until a live target of the current extent latches. */
void fg_notify_image_destroyed(uint64_t img) {
    if (!img) return;
    /* Serialised against the capture — see g_latch_mu. Without this, clearing
     * the latch races the capture that is already recording a blit FROM it, and
     * the driver frees the image mid-command-buffer:
     *   signal 11 (SIGSEGV), fault addr 0x40
     *   #00-02 libvulkan_freedreno...turnip
     *   #03    libvulkad.so (fg_capture_and_push+612)
     * (device tombstone, 2026-08-16 23:18). Loading a save state destroys every
     * GS target at once, which is why that path crashed and ordinary play did
     * not. */
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    if (S.gs_image != img) return;
    FGI("VulkanShim: framegen latched GS target %llu destroyed — dropping latch",
        (unsigned long long)img);
    S.gs_image = 0;
    latch_drop_ns = fg_monotonic_ns();
}

void fg_note_current_gs(uint64_t image, uint32_t w, uint32_t h, uint32_t format) {
    /* Deliberately NOT gated on S.enabled: the OSD reports the GS extent even
     * when capture is off, and storing four integers costs nothing. Without
     * this the display reads "GS 0x0", which looks like a fault rather than
     * "frame generation is switched off".
     *
     * Ignore scratch targets (256x256, 512x434) via fg_is_valid_gs_extent,
     * not "largest area seen". Largest-area breaks IR decreases: after 2x the
     * stored extent is 1024x896 and a new Native 512x448 target is rejected
     * as "smaller", leaving a stale VkImage handle and wrong blit extents —
     * black screen / corruption while lsfg_overlay is on. */
    if (w && h) {
        if (!fg_is_valid_gs_extent(w, h)) return;
        if (!fg_extent_expected(w, h)) return;
        if (w == S.gs_w && h == S.gs_h)
            gs_seen_ns = fg_monotonic_ns();   /* the GS target is still alive */
        else
            fg_notify_gs_resize(w, h);
    }
    fg_try_commit_resize();
    if (!image) return;
    if (resize_pending_w) return; /* wait for stable IR before latching */
    /* Never latch a target that is not the GS. Games render into several
     * valid-looking 512:448 targets at once (half-res passes); latching one of
     * those blits a buffer nothing draws into, which reads as a black screen
     * at a flawless frame rate. */
    /* Match the resize tolerance above. Holding the extent through a couple of
     * pixels of jitter is only safe if the latch ACCEPTS the jittered target too:
     * with an exact compare here, S.gs_w stayed 1024x896 while the game drew
     * 1024x890 and every image was refused, so the latch went stale and capture
     * re-read one frame forever -- "59 captures/s but 0 unique". */
    if (w && h) {
        uint32_t dw = w > S.gs_w ? w - S.gs_w : S.gs_w - w;
        uint32_t dh = h > S.gs_h ? h - S.gs_h : S.gs_h - h;
        if (dw > 16u || dh > 16u) return;
    }
    /* Refused for its TTL after it stayed frozen through a latch drop. */
    if (fg_img_rejected(image)) {
        /* UNLESS THAT REFUSAL IS WHAT HAS US WEDGED.
         *
         * Measured on GTA:SA, Adreno 740: PCSX2 rotates five distinct targets at
         * 1024x896. The latch sat on one nothing draws into ("capture idle ...
         * built=0" once a second) while the live target was notified every frame
         * and REFUSED here, because the frozen-source watchdog had rejected it
         * earlier -- while the latch was elsewhere and it therefore looked frozen.
         * That is self-reinforcing: dead latch -> no captures -> the one target
         * that could rescue us stays refused.
         *
         * The sticky-latch note below says it outright: being wedged is worse than
         * hopping. If nothing has been captured for three seconds, the reject list
         * is wrong by construction, so drop it and take this target. */
        if (fg_idle_since_ns
                && fg_monotonic_ns() - fg_idle_since_ns > 3000000000ULL) {
            fg_clear_rejects();
            fg_idle_since_ns = 0;
            FGI("VulkanShim: framegen capture idle 3 s with a refused target "
                "live — clearing the reject list and latching %llu",
                (unsigned long long)image);
        } else {
            return;
        }
    }

    /* A STICKY LATCH WAS TRIED HERE AND CAUSED A BLACK SCREEN. DO NOT RETRY.
     *
     * The motivation was sound and the measurement real: seven distinct VkImages
     * were counted at the display extent 1024x896 in one session, so the extent
     * test below cannot identify the presented target and "last pass before
     * present wins" picks among siblings arbitrarily. Holding the incumbent was
     * supposed to stop that hopping.
     *
     * What actually happened (2026-08-17, build 12812433): the latch settled on
     * a target nothing renders into and STAYED there. Screen fully black, mean
     * luma 0.0, while the log read "60 captures/s but 0 unique — output still
     * flowing": LSFG was posting black frames, so `producing` was true and the
     * frozen-source escalation deliberately would not act. Nothing could recover
     * it. The hopping was ugly; being wedged is worse, because re-latching every
     * frame is precisely what used to find the live target again.
     *
     * A sticky latch is only safe once something can tell a wrong latch from a
     * still scene. `uniq == 0` cannot: a parked game gives byte-identical frames
     * too. Build that discriminator FIRST — the distinct-handle count below is a
     * start — and do not re-introduce stickiness without it. */
    S.gs_image = image;
    gs_img_seen_ns = fg_monotonic_ns();
    /* Count DISTINCT handles this window, not alternations. Counting each
     * change saturated a 255 cap within two seconds — PCSX2 rotates through its
     * targets every frame, so "how often does it change" is ~120/s and tells you
     * nothing. "How many different ones exist" is the number that matters, and
     * it is small. */
#if FG_DIAG_DISTINCT
    if (gs_distinct_last != image) {
        gs_distinct_last = image;
        int known = 0;
        for (uint32_t i = 0; i < gs_distinct_n && i < FG_DISTINCT_MAX; i++)
            if (gs_distinct_set[i] == image) { known = 1; break; }
        if (!known && gs_distinct_n < FG_DISTINCT_MAX)
            gs_distinct_set[gs_distinct_n++] = image;
    }
#endif
    if (format) S.gs_fmt = format;
}

static uint32_t find_mem(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < S.memprops.memoryTypeCount; i++)
        if ((bits & (1u << i)) &&
            (S.memprops.memoryTypes[i].propertyFlags & want) == want)
            return i;
    for (uint32_t i = 0; i < S.memprops.memoryTypeCount; i++)
        if (bits & (1u << i)) return i;
    return UINT32_MAX;
}

#define RESOLVE(f) do { \
    p_##f = (PFN_##f)S.gpa(S.dev, #f); \
    if (!p_##f) { FGE("VulkanShim: framegen missing %s", #f); return 0; } \
} while (0)

static int resolve_fns(void) {
    RESOLVE(vkCreateImage);
    RESOLVE(vkDestroyImage);
    RESOLVE(vkAllocateMemory);
    RESOLVE(vkFreeMemory);
    RESOLVE(vkBindImageMemory);
    RESOLVE(vkGetImageMemoryRequirements);
    RESOLVE(vkCreateCommandPool);
    RESOLVE(vkAllocateCommandBuffers);
    RESOLVE(vkBeginCommandBuffer);
    RESOLVE(vkEndCommandBuffer);
    RESOLVE(vkResetCommandBuffer);
    RESOLVE(vkCmdPipelineBarrier);
    RESOLVE(vkCmdBlitImage);
    RESOLVE(vkCmdClearColorImage);
    RESOLVE(vkQueueSubmit);
    RESOLVE(vkCreateFence);
    RESOLVE(vkDestroyFence);
    RESOLVE(vkGetFenceStatus);
    RESOLVE(vkResetFences);
    RESOLVE(vkWaitForFences);
    RESOLVE(vkDestroyCommandPool);
    return 1;
}

/* One AHB plus the VkImage that aliases it. */
static int build_slot(int i) {
    AHardwareBuffer_Desc d;
    memset(&d, 0, sizeof(d));
    d.width = S.w;
    d.height = S.h;
    d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
            | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&d, &S.ahb[i]) != 0 || !S.ahb[i]) {
        FGE("VulkanShim: framegen AHardwareBuffer_allocate failed (%ux%u)", S.w, S.h);
        return 0;
    }

    VkExternalMemoryImageCreateInfo ext;
    memset(&ext, 0, sizeof(ext));
    ext.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo ii;
    memset(&ii, 0, sizeof(ii));
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.pNext = &ext;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = VK_FORMAT_R8G8B8A8_UNORM;
    ii.extent.width = S.w; ii.extent.height = S.h; ii.extent.depth = 1;
    ii.mipLevels = 1; ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (p_vkCreateImage(S.dev, &ii, NULL, &S.img[i]) != VK_SUCCESS) {
        FGE("VulkanShim: framegen vkCreateImage(AHB) failed");
        return 0;
    }

    /* Size and type bits come from the image, NOT from
     * vkGetAndroidHardwareBufferPropertiesANDROID: this ICD does not expose
     * that entry point (the layer logs "no function pointer for ..." at
     * device init), and mini/image.cpp skips it for the same reason. */
    VkMemoryRequirements mr;
    p_vkGetImageMemoryRequirements(S.dev, S.img[i], &mr);

    VkMemoryDedicatedAllocateInfo ded;
    memset(&ded, 0, sizeof(ded));
    ded.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    ded.image = S.img[i];

    VkImportAndroidHardwareBufferInfoANDROID imp;
    memset(&imp, 0, sizeof(imp));
    imp.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    imp.pNext = &ded;
    imp.buffer = S.ahb[i];

    VkMemoryAllocateInfo ai;
    memset(&ai, 0, sizeof(ai));
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.pNext = &imp;
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = find_mem(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX ||
        p_vkAllocateMemory(S.dev, &ai, NULL, &S.mem[i]) != VK_SUCCESS) {
        FGE("VulkanShim: framegen could not import AHB into device memory");
        return 0;
    }
    if (p_vkBindImageMemory(S.dev, S.img[i], S.mem[i], 0) != VK_SUCCESS) {
        FGE("VulkanShim: framegen vkBindImageMemory failed");
        return 0;
    }

    VkCommandBufferAllocateInfo cbi;
    memset(&cbi, 0, sizeof(cbi));
    cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbi.commandPool = S.pool;
    cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbi.commandBufferCount = 1;
    if (p_vkAllocateCommandBuffers(S.dev, &cbi, &S.cb[i]) != VK_SUCCESS) return 0;

    VkFenceCreateInfo fi;
    memset(&fi, 0, sizeof(fi));
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (p_vkCreateFence(S.dev, &fi, NULL, &S.fence[i]) != VK_SUCCESS) return 0;
    return 1;
}

/* WHICH GUARD RETURNED, ONCE A SECOND.
 *
 * build() had four silent early returns, so "capture idle ... built=0" meant
 * any one of them and a first-boot race could not be told apart from the
 * second-game stall. This is the same reasoning as the capture-idle log
 * further down ("the one question never logged is which line returned"),
 * one level lower, where that log bottoms out. */
static void build_blocked(const char *why) {
    static uint64_t last_ns;
    uint64_t now = fg_monotonic_ns();
    if (last_ns && now - last_ns < 10000000000ULL) return;
    last_ns = now;
    FGI("VulkanShim: framegen build blocked at %s — failed=%d dev=%p gpa=%p "
        "queue=%p family=%u memprops=%d gs=%ux%u gs_image=%llu "
        "from_present=%d cap=%ux%u",
        why, S.failed, (void *)S.dev, (void *)S.gpa, (void *)S.queue,
        (unsigned)S.family, S.have_memprops,
        (unsigned)S.gs_w, (unsigned)S.gs_h,
        (unsigned long long)S.gs_image, S.capture_from_present,
        (unsigned)S.cap_w, (unsigned)S.cap_h);
}

static int build(void) {
    if (S.built) return 1;
    if (S.failed) { build_blocked("failed-latch"); return 0; }
    if (!S.dev || !S.gpa || !S.queue || !S.have_memprops
        || !S.gs_w || !S.gs_h) {
        build_blocked(!S.dev ? "no-device"
                      : !S.gpa ? "no-gpa"
                      : !S.queue ? "no-queue"
                      : !S.have_memprops ? "no-memprops" : "no-gs-extent");
        return 0;                       /* not everything has arrived yet */
    }
    /* S.gs_image is the GS route's latch, and only that route needs it here.
     * The presented image is resolved per present and is not held in S at all,
     * so requiring it kept build() from ever running in swapchain mode — the
     * capture had a valid source and a valid extent and still allocated
     * nothing. */
    if (!S.capture_from_present && !S.gs_image) {
        build_blocked("no-gs-image");
        return 0;
    }

    if (!resolve_fns()) { S.failed = 1; return 0; }

    /* Fixed capture size rather than the GS extent. The Java side has to
     * pass the same numbers to initContext(), and it has no way to learn the
     * GS target's size — PCSX2 picks that from the internal-resolution
     * setting long after the overlay is built. vkCmdBlitImage scales, so the
     * GS target can be any size. Tunable via turnip.conf fg_capture_w/h. */
    if (!S.cap_w || !S.cap_h) {
        S.cap_w = FG_CAPTURE_W_DEFAULT;
        S.cap_h = FG_CAPTURE_H_DEFAULT;
    }
    S.w = S.cap_w;
    S.h = S.cap_h;

    VkCommandPoolCreateInfo pi;
    memset(&pi, 0, sizeof(pi));
    pi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pi.queueFamilyIndex = S.family;
    if (p_vkCreateCommandPool(S.dev, &pi, NULL, &S.pool) != VK_SUCCESS) {
        FGE("VulkanShim: framegen vkCreateCommandPool failed");
        S.failed = 1;
        return 0;
    }

    for (int i = 0; i < FG_SLOTS; i++) {
        if (!build_slot(i)) { S.failed = 1; return 0; }
    }

    S.built = 1;
    FGI("VulkanShim: framegen source ready - GS %ux%u -> AHB, %d slots",
        S.w, S.h, FG_SLOTS);
    return 1;
}

static void barrier(VkCommandBuffer cb, VkImage img,
                    VkImageLayout from, VkImageLayout to,
                    VkAccessFlags src, VkAccessFlags dst,
                    uint32_t srcFamily = VK_QUEUE_FAMILY_IGNORED,
                    uint32_t dstFamily = VK_QUEUE_FAMILY_IGNORED) {
    VkImageMemoryBarrier b;
    memset(&b, 0, sizeof(b));
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = from; b.newLayout = to;
    b.srcQueueFamilyIndex = srcFamily;
    b.dstQueueFamilyIndex = dstFamily;
    b.image = img;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;
    b.srcAccessMask = src; b.dstAccessMask = dst;
    p_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, NULL, 0, NULL, 1, &b);
}

static void capture_blocked(const char *why) {
    static uint64_t last_ns;
    static const char *last_why;
    uint64_t now = fg_monotonic_ns();
    if (last_why == why && last_ns && now - last_ns < 10000000000ULL) return;
    last_ns = now; last_why = why;
    FGI("VulkanShim: framegen capture not pushing — %s (enabled=%d failed=%d "
        "pause_reasons=0x%x resize_pending=%ux%u gs=%ux%u gs_image=%llu built=%d)",
        why, S.enabled, S.failed, capture_pause_reasons.load(),
        (unsigned)resize_pending_w, (unsigned)resize_pending_h,
        (unsigned)S.gs_w, (unsigned)S.gs_h,
        (unsigned long long)S.gs_image, S.built);
}

int fg_capture_and_push(void) {
#ifdef PINK_NO_FRAMEGEN
    return 0;
#else
    /* SAY WHY CAPTURE IS NOT PUSHING, ONCE A SECOND.
     *
     * These three returns were silent, and they are ABOVE the "capture idle"
     * log further down -- so when one of them holds, framegen goes completely
     * quiet and posted/gen simply stop climbing with nothing in logcat at all.
     * Observed after loading a save state: posted frozen, zero native lines. */
    if (!S.enabled || S.failed) { capture_blocked("enabled/failed"); return 0; }
    /* Commit debounced resize before capture_paused gate — IR recovery pauses
     * capture while GS reopens; presents must still advance the 200 ms debounce
     * so onGsResizeSettledFromNative fires before Java fallback tears down LSFG. */
    fg_try_commit_resize();
    // Only legacy callers have an automatic native timeout. Managed leases
    // remain held while their native worker is still using the output surface.
    {
        std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
        uint64_t since = pause_since_ns.load();
        if ((capture_pause_reasons.load() & LEGACY_PAUSE) && !resize_pending_w && since
                && fg_monotonic_ns() - since > PAUSE_GRACE_NS) {
            capture_pause_reasons.fetch_and(~LEGACY_PAUSE);
            pause_since_ns.store(0);
            FGI("VulkanShim: framegen expired legacy capture pause");
        }
    }
    if (capture_paused()) { capture_blocked("paused"); return 0; }
    if (resize_pending_w) { capture_blocked("resize-pending"); return 0; }
    /* STALE-LATCH GUARD. The render-pass hook reports the target it is about to
     * draw into on every frame; presents cannot outrun it. So if presents keep
     * arriving while no target has been reported for two seconds, tracking has
     * lost the live target and S.gs_image points at a buffer nobody writes —
     * the exact state that showed a BLACK panel for minutes at a flawless 119
     * fps while the emulator ran at 100% speed.
     *
     * This is deliberately NOT the "captured frames stopped changing" test. A
     * paused game and an idle rooftop freeze the image too; a Java-side version
     * of that test fired on a loading screen within 8 s of boot. "Nobody told
     * us what to draw" has no such ambiguity. */
    /* Held from here to the end of the function, so the latched image cannot be
     * freed between the check below and the queue submit that reads it.
     * RECURSIVE on purpose: build() below destroys our own AHB images through
     * p_vkDestroyImage, which resolves to the same hooked entry point and comes
     * straight back into fg_notify_image_destroyed. A plain mutex deadlocks
     * there. */
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    if (capture_paused()) { capture_blocked("paused-before-push"); return 0; }
    /* The stale-latch guard exists because the GS route can hold a handle
     * nobody renders into any more. A presented image cannot go stale: it is
     * re-resolved from (swapchain, imageIndex) every single present. */
    if (S.gs_image && !S.capture_from_present) {
        uint64_t now = fg_monotonic_ns();
        /* Re-arm on its OWN clock. Writing gs_seen_ns here deadlocked every IR
         * DECREASE: that field means "the current extent was last rendered", and
         * the downshift gate below waits for it to go quiet for 1.5 s — so a
         * guard that refreshed it every 2 s kept the dead extent looking alive
         * forever. The smaller target was reported every frame, refused every
         * frame, and the GS never followed the IR down. */
        /* Back on the EXTENT's clock with the sticky latch reverted. The handle
         * clock only made sense while one handle was held for a long time; with
         * re-latching every frame, gs_img_seen_ns refreshes constantly and the
         * two are equivalent anyway. Kept as gs_seen_ns because that is the
         * field the downshift gate also reads. */
        if (gs_seen_ns && now - gs_seen_ns > 2000000000ULL
                       && now - latch_drop_ns > 2000000000ULL) {
            FGI("VulkanShim: framegen GS target went unreported for %llu ms "
                "(%u distinct target(s) seen at this extent) — dropping stale latch",
                (unsigned long long)((now - gs_seen_ns) / 1000000ULL),
                (unsigned)gs_distinct_n);
            S.gs_image = 0;
            gs_distinct_n = 0;
            gs_distinct_last = 0;
            latch_drop_ns = now;
        }
    }
    /* OBSERVATION ONLY — the data a safe latch fix needs.
     *
     * How many DISTINCT targets show up at the display extent per 10 s is the
     * one number that separates "the latch is hopping between siblings" from
     * "the scene is simply still". `uniq == 0` cannot: a parked game produces
     * byte-identical frames too, which is why the frozen-source watchdog false
     * -fired for weeks and why the sticky latch could not tell it had wedged.
     * A correct latch on a static scene should sit near 1; the session that
     * produced a black panel had seven. Nothing acts on this yet, deliberately. */
#if FG_DIAG_DISTINCT
    {
        static uint64_t distinct_log_ns;
        uint64_t nw = fg_monotonic_ns();
        if (!distinct_log_ns) distinct_log_ns = nw;
        else if (nw - distinct_log_ns > 10000000000ULL) {
            if (gs_distinct_n > 1)
                FGI("VulkanShim: framegen %u distinct targets at %ux%u in the "
                    "last 10 s (latched %llu)", (unsigned)gs_distinct_n,
                    (unsigned)S.gs_w, (unsigned)S.gs_h,
                    (unsigned long long)S.gs_image);
            gs_distinct_n = 0;
            gs_distinct_last = 0;
            distinct_log_ns = nw;
        }
    }
#endif
    /* SWAPCHAIN SOURCE: skip every line of the latch machinery above.
     *
     * There is nothing to identify, nothing to settle, nothing to re-latch and
     * no stale handle to guard against — the present hook just handed us the
     * image the compositor is about to scan out. What the GS path spends two
     * thousand lines being careful about is, here, one assignment. */
    uint64_t src_image = S.gs_image;
    int src_is_present = 0;
    if (S.capture_from_present) {
        src_image = present_image;
        src_is_present = 1;
    }
    /* SAY WHY, ONCE A SECOND. Every previous capture stall in this file was
     * diagnosed by staring at a counter that only reports the outcome; the one
     * question never logged is which line returned. */
    if (!src_image || !build()) {
        static uint64_t why_ns;
        uint64_t now = fg_monotonic_ns();
        if (!why_ns || now - why_ns > 1000000000ULL) {
            why_ns = now;
            FGE("VulkanShim: framegen capture idle — src=%s image=%llu built=%d "
                "src_extent=%ux%u capture=%ux%u enabled=%d paused=%d resize_pending=%u",
                src_is_present ? "present" : "gs",
                (unsigned long long)src_image, S.built,
                (unsigned)S.gs_w, (unsigned)S.gs_h,
                (unsigned)S.cap_w, (unsigned)S.cap_h,
                S.enabled, capture_paused(), (unsigned)resize_pending_w);
        }
        if (!fg_idle_since_ns) fg_idle_since_ns = now;
        return 0;
    }

    /* Capture is proceeding: the idle run, if any, is over. */
    fg_idle_since_ns = 0;
    ensure_lsfg_push_direct();
    if (!lsfg_push_direct || !lsfg_capture_in_use) {
        capture_blocked("capture-lease bridge unavailable");
        return 0;
    }
    // A submit is not a completed capture. Poll on later presents and publish
    // in capture order, keeping the original timestamp for interval estimation.
    for (;;) {
        const int ready = oldestUnpublishedCapture(S.capture);
        if (ready < 0 || p_vkGetFenceStatus(S.dev, S.fence[ready]) != VK_SUCCESS)
            break;
        S.capture[ready].published = true;
        push_to_java(S.ahb[ready], S.capture[ready].timestampNs);
        if ((++S.pushed % 300) == 0)
            FGI("VulkanShim: framegen completed=%llu skipped=%llu slots=%d leases=on",
                (unsigned long long)S.pushed, (unsigned long long)S.skipped, FG_SLOTS);
    }

    // An AHB refcount only keeps its allocation alive. The consumer lease
    // protects its contents until the worker's GPU copy has actually finished.
    int i = -1;
    for (int offset = 0; offset < FG_SLOTS; ++offset) {
        const int candidate = (S.slot + offset) % FG_SLOTS;
        const bool complete = !S.capture[candidate].submitted ||
            p_vkGetFenceStatus(S.dev, S.fence[candidate]) == VK_SUCCESS;
        if (S.capture[candidate].reusable(complete,
                lsfg_capture_in_use(S.ahb[candidate]) != 0)) {
            i = candidate;
            break;
        }
    }
    if (i < 0) { ++S.skipped; return 0; }
    const bool reused = S.capture[i].submitted;

    VkImage src = (VkImage)src_image;
    VkCommandBuffer cb = S.cb[i];
    p_vkResetCommandBuffer(cb, 0);

    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (p_vkBeginCommandBuffer(cb, &bi) != VK_SUCCESS) return 0;

    /* The source's layout at present time depends on which source it is: a
     * presentable image is in PRESENT_SRC_KHR (the app just transitioned it
     * there to hand it over), the GS target is in SHADER_READ_ONLY_OPTIMAL.
     * Both must be put back exactly as found — getting this wrong on a
     * presentable image is a validation error and, on Turnip, a black frame. */
    VkImageLayout src_layout = src_is_present
            ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
            : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    /* Prefer the layout actually observed for this image; the constant above is
     * only a fallback for an image no barrier has been seen for. */
    src_layout = (VkImageLayout)fg_layout_of(src_image, (uint32_t)src_layout);
    const VkAccessFlags src_access = src_is_present
            ? (VkAccessFlags)VK_ACCESS_MEMORY_READ_BIT
            : (VkAccessFlags)VK_ACCESS_SHADER_READ_BIT;
    barrier(cb, src, src_layout,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            src_access, VK_ACCESS_TRANSFER_READ_BIT);
    barrier(cb, S.img[i], reused ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
            reused ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED,
            reused ? S.family : VK_QUEUE_FAMILY_IGNORED);

    /* Blit rather than copy: the GS target's format need not match the
     * AHB's RGBA8888, and blit converts where copy would be invalid. */
    VkImageBlit rg;
    memset(&rg, 0, sizeof(rg));
    rg.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    rg.srcSubresource.layerCount = 1;
    rg.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    rg.dstSubresource.layerCount = 1;
    /* Source rect from the IMAGE, not from what we believe the GS extent is.
     *
     * S.gs_w/S.gs_h is a belief maintained by the resize tracker; the image is
     * a fact. Every time the two have drifted apart the result was a silent
     * top-left CROP scaled up to fill the panel — a live, moving, plausible
     * picture with every counter healthy, because each counter is derived from
     * the same belief and so cannot contradict it. Measured twice on
     * 2026-08-16: 1.333x (1024/768) and 1.995x (1536/768).
     *
     * Blitting the image's true extent makes that class of bug impossible: a
     * wrong belief can now only cost a rebuild, never the framing. */
    uint32_t src_w = src_is_present ? present_w : S.gs_w;
    uint32_t src_h = src_is_present ? present_h : S.gs_h;
    uint32_t real_w = 0, real_h = 0;
    if (fg_query_image_extent(src_image, &real_w, &real_h) && real_w && real_h) {
        if (real_w != S.gs_w || real_h != S.gs_h) {
            static uint32_t warned_w, warned_h;
            if (real_w != warned_w || real_h != warned_h) {
                warned_w = real_w; warned_h = real_h;
                FGE("VulkanShim: framegen SOURCE MISMATCH — latched image is "
                    "%ux%u but tracker says %ux%u; blitting the image's real "
                    "extent (a crop of %.3fx would have been shown)",
                    (unsigned)real_w, (unsigned)real_h,
                    (unsigned)S.gs_w, (unsigned)S.gs_h,
                    S.gs_w ? (double)real_w / (double)S.gs_w : 0.0);
            }
        }
        src_w = real_w;
        src_h = real_h;
    }
    rg.srcOffsets[1].x = (int32_t)src_w;
    rg.srcOffsets[1].y = (int32_t)src_h;
    rg.srcOffsets[1].z = 1;
    rg.dstOffsets[1].x = (int32_t)S.w;
    rg.dstOffsets[1].y = (int32_t)S.h;
    rg.dstOffsets[1].z = 1;
    if (g_fg_test_pattern && p_vkCmdClearColorImage) {
        VkClearColorValue cc;
        float t = (float)(S.pushed & 63u) / 63.f;
        cc.float32[0] = t;
        cc.float32[1] = 1.f - t;
        cc.float32[2] = (S.pushed & 1u) ? 1.f : 0.f;
        cc.float32[3] = 1.f;
        VkImageSubresourceRange rr;
        rr.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        rr.baseMipLevel = 0; rr.levelCount = 1;
        rr.baseArrayLayer = 0; rr.layerCount = 1;
        p_vkCmdClearColorImage(cb, S.img[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               &cc, 1, &rr);
    } else {
        p_vkCmdBlitImage(cb, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         S.img[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         1, &rg, VK_FILTER_NEAREST);
    }

    barrier(cb, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, src_layout,
            VK_ACCESS_TRANSFER_READ_BIT, src_access);
    // Publish a GENERAL image owned by the external consumer. The completed
    // producer fence and consumer lease provide the cross-device ordering.
    barrier(cb, S.img[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
            S.family, VK_QUEUE_FAMILY_EXTERNAL);

    if (p_vkEndCommandBuffer(cb) != VK_SUCCESS) return 0;

    VkSubmitInfo si;
    memset(&si, 0, sizeof(si));
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    /* No wait/signal semaphores: this is a read of a target the core has
     * already finished drawing, submitted on the same queue, so queue
     * order alone puts it after that work. The core's present is not made
     * to wait on anything of ours. */
    if (reused && p_vkResetFences(S.dev, 1, &S.fence[i]) != VK_SUCCESS) return 0;
    if (p_vkQueueSubmit(S.queue, 1, &si, S.fence[i]) != VK_SUCCESS) {
        FGE("VulkanShim: framegen vkQueueSubmit failed");
        S.failed = 1; // fence was reset; never reuse this pool after failed submit
        return 0;
    }
    S.capture[i] = {true, false, static_cast<int64_t>(fg_monotonic_ns())};
    S.slot = (i + 1) % FG_SLOTS;
    // Delivery happens on a later present after this fence signals. Never
    // hand an unfinished copy to a different VkDevice with no wait semaphore.
    return 1;
#endif
}

/* ------------------------------------------------------------------ */
/* Stats for the on-screen display                                     */
/* ------------------------------------------------------------------ */
/* Capturing the GS target means the emulator's own OSD is never in the
 * frame — PCSX2 draws it at present time, after the target we copy — and
 * the output surface then covers the real one. So the shim has to supply
 * its own. This is the native half: counters the Java overlay polls.
 *
 * Statically bound as ShimFrameGen.nativeStats; libvulkad.so is already
 * loaded with System.loadLibrary("vulkad"), so no extra load is needed.
 *
 * out[0] pushed   frames handed to the generator
 * out[1] skipped  frames dropped because their slot was still in flight
 * out[2] gs_w     GS colour target width  (the real internal resolution)
 * out[3] gs_h     GS colour target height
 * out[4] built    1 once the AHB slots exist
 * out[5] enabled  native capture gate (fg_set_enabled)
 * out[6] throttle 1 when auto-throttle is skipping capture
 * out[7] suppress FG_SUPPRESS_* reason from turnip.conf gating
 * out[8] pending_gs_w  resize debounce target (0 if none)
 * out[9] pending_gs_h  resize debounce target height
 * out[10] gs_latched   1 when a GS VkImage handle is held
 * out[11] capture_paused 1 when IR recovery paused native capture
 * @return the number of entries written.
 */
/**
 * Rotate the OUTPUT surface's buffers, producer-side.
 *
 * The sideways panel on a portrait-native display (AYN Thor: 1080x1920 driven in
 * landscape) is not a Vulkan transform -- the emulator's swapchain reports
 * IDENTITY and LSFG never creates a swapchain at all, it renders straight into
 * this Surface, whose buffers SurfaceFlinger then rotates. View-level rotation
 * cannot fix it either: setRotation on a SurfaceView moves the placeholder, not
 * the surface content (measured -- it applied and changed nothing).
 *
 * ANativeWindow_setBuffersTransform is the correct lever, and it must be applied
 * BEFORE LSFG binds the Surface. Both symbols are dlsym'd from libandroid.so
 * rather than linked, matching how apply_frame_rate resolves its own entry point.
 *
 * transform: NATIVE_WINDOW_TRANSFORM_ROT_90=0x04, ROT_180=0x03, ROT_270=0x07, 0=none.
 */
/**
 * Set the framegen rotation from the LIVE display rotation, in degrees.
 *
 * Drives the native rotate90.comp pass (via lsfg_bridge_set_window_transform),
 * NOT the legacy producer-side surface transform below. Safe to call every tick:
 * it early-outs unless the value actually changed.
 */
/**
 * Gate the NATIVE capture path from Java.
 *
 * Stopping the pipeline used to leave capture running: `pushed` kept climbing
 * with `gen 0`, so once the cost guard tripped the app paid the full per-frame
 * copy (8-16 ms) forever and generated nothing. The game stayed at ~20 fps and
 * never recovered, which read as "framegen is off and it is still slow".
 */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetCaptureEnabled(
        JNIEnv *, jclass, jboolean on) {
    extern void fg_set_enabled(int);
    fg_set_enabled(on ? 1 : 0);
}

/** Intended display aspect (w/h); 0 = stretch to fill. */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetOutputAspect(
        JNIEnv *, jclass, jfloat a) {
    extern void fg_set_output_aspect(float);
    fg_set_output_aspect((float)a);
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetPanelRotate(
        JNIEnv *, jclass, jint deg) {
    extern void fg_set_panel_rotate_degrees(int);
    fg_set_panel_rotate_degrees((int)deg);
}

extern "C" JNIEXPORT jint JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetSurfaceTransform(
        JNIEnv *env, jclass, jobject surface, jint transform) {
    typedef void *(*PFN_fromSurface)(JNIEnv *, jobject);
    typedef int32_t (*PFN_setXform)(void *, int32_t);
    typedef void (*PFN_release)(void *);
    static PFN_fromSurface p_from;
    static PFN_setXform p_set;
    static PFN_release p_release;
    static int resolved;
    if (!resolved) {
        resolved = 1;
        void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (lib) {
            p_from = (PFN_fromSurface)dlsym(lib, "ANativeWindow_fromSurface");
            p_set = (PFN_setXform)dlsym(lib, "ANativeWindow_setBuffersTransform");
            p_release = (PFN_release)dlsym(lib, "ANativeWindow_release");
        }
        FGI("VulkanShim: framegen surface-transform symbols %s",
            (p_from && p_set) ? "resolved" : "MISSING");
    }
    if (!p_from || !p_set || !surface) return -1;
    void *win = p_from(env, surface);
    if (!win) return -2;
    int32_t r = p_set(win, (int32_t)transform);
    if (p_release) p_release(win);
    FGI("VulkanShim: framegen output surface transform 0x%x -> result %d",
        (unsigned)transform, (int)r);
    return (jint)r;
}

extern "C" JNIEXPORT jint JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeStats(JNIEnv *env, jclass,
                                                         jlongArray out) {
    /* 14 now: v[12]/v[13] are the size the capture AHBs were ACTUALLY built at.
     * That number is what LSFG's input slot is compared against, and it was the
     * one quantity nothing exported — so a stale AHB cropped the picture while
     * every Java-side figure agreed with itself. Accepts a 12-long array still,
     * for callers that have not been rebuilt. */
    if (!out) return 0;
    jsize n = env->GetArrayLength(out);
    if (n < 12) return 0;
    jlong v[14];
    v[0] = (jlong)S.pushed;
    v[1] = (jlong)S.skipped;
    v[2] = (jlong)S.gs_w;
    v[3] = (jlong)S.gs_h;
    v[4] = (jlong)(S.built ? 1 : 0);
    v[5] = (jlong)S.enabled;
    v[6] = (jlong)S.throttle_active;
    v[7] = (jlong)S.suppress_reason;
    v[8] = (jlong)resize_pending_w;
    v[9] = (jlong)resize_pending_h;
    v[10] = (jlong)(S.gs_image ? 1 : 0);
    v[11] = (jlong)capture_paused();
    if (n < 14) {
        env->SetLongArrayRegion(out, 0, 12, v);
        return 12;
    }
    v[12] = (jlong)(S.built ? S.w : 0);   /* AHB width  actually in use */
    v[13] = (jlong)(S.built ? S.h : 0);   /* AHB height actually in use */
    env->SetLongArrayRegion(out, 0, 14, v);
    return 14;
}

/* Called from Java when Internal Resolution changes mid-session. Clears the
 * GS latch and AHB slots so capture waits for the recreated target instead of
 * blitting a destroyed VkImage for the rest of the session. */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeResetCapture(JNIEnv *, jclass) {
    fg_reset_capture();
}

/* Let go of the current GS target and re-latch on the next report.
 *
 * Recovery of last resort for "the source is frozen": costs ONE frame, because
 * fg_note_current_gs re-latches as soon as any live target of the current
 * extent is reported. Deliberately much cheaper than fg_reset_capture(), which
 * tears the AHB slots down and shows as a multi-second gap — this is meant to
 * be safe to fire on suspicion, including the false positive of a genuinely
 * static scene, where dropping and immediately re-taking the latch is invisible. */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeDropLatch(JNIEnv *, jclass) {
    if (!S.gs_image) return;
    FGI("VulkanShim: framegen latch dropped on request (frozen source) — "
        "was %llu", (unsigned long long)S.gs_image);
    S.gs_image = 0;
    latch_drop_ns = fg_monotonic_ns();
}

/* Escalation from nativeDropLatch: the drop did not help, so this handle is not
 * the live target. Refuse it for DEAD_TTL_NS and rebuild the capture so a
 * different one is taken. Only called after a plain drop has already failed —
 * the watchdog's false positives (a genuinely static scene) must not reach it. */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeRejectSourceAndReset(JNIEnv *,
                                                                       jclass) {
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    if (S.gs_image) {
        FGI("VulkanShim: framegen rejecting frozen GS target %llu for %llus and "
            "rebuilding capture", (unsigned long long)S.gs_image,
            (unsigned long long)(DEAD_TTL_NS / 1000000000ULL));
        fg_reject_img(S.gs_image);
        S.gs_image = 0;
    }
    latch_drop_ns = fg_monotonic_ns();
    fg_reset_capture();
}

/* Reject the handle WITHOUT rebuilding capture.
 *
 * Refusing the frozen handle for its TTL is what actually forces a different
 * target to be latched; fg_reset_capture() on top of that costs ~5 s of no frame
 * generation. Since a still scene and a dead latch are indistinguishable from
 * the captured data alone, the recovery WILL sometimes fire on a parked game —
 * so the first escalation must be cheap enough that being wrong barely shows.
 * The full reset stays as the last resort. */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeRejectSourceOnly(JNIEnv *,
                                                                   jclass) {
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    if (!S.gs_image) return;
    FGI("VulkanShim: framegen rejecting frozen GS target %llu for %llus "
        "(latch only, capture kept)", (unsigned long long)S.gs_image,
        (unsigned long long)(DEAD_TTL_NS / 1000000000ULL));
    fg_reject_img(S.gs_image);
    S.gs_image = 0;
    latch_drop_ns = fg_monotonic_ns();
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetExpectedGs(JNIEnv *, jclass,
                                                                 jint w, jint h) {
    fg_set_expected_gs((uint32_t)(w > 0 ? w : 0), (uint32_t)(h > 0 ? h : 0));
}

/* Static callback on ShimFrameGen — bound from a Java thread in prepare(). */
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeBindCallbacks(JNIEnv *env, jclass clazz) {
    if (g_fg_class) {
        env->DeleteGlobalRef(g_fg_class);
        g_fg_class = NULL;
        g_gs_settled = NULL;
    }
    g_fg_class = (jclass)env->NewGlobalRef(clazz);
    g_gs_settled = env->GetStaticMethodID(clazz, "onGsResizeSettledFromNative", "(II)V");
    if (!g_gs_settled) {
        env->ExceptionClear();
        FGE("VulkanShim: framegen nativeBindCallbacks no onGsResizeSettledFromNative");
        return;
    }
    FGI("VulkanShim: framegen GS-settle callback bound");
}

extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetCapturePaused(JNIEnv *, jclass,
                                                                    jboolean paused) {
    fg_set_capture_paused(paused ? 1 : 0);
}

void fg_note_present_image(uint64_t image, uint32_t w, uint32_t h) {
    if (image) present_seen = 1;
    present_image = image;
    if (w) present_w = w;
    if (h) present_h = h;
    if (!S.capture_from_present || !image) return;
    /* THE FRAME'S SIZE, QUERIED RATHER THAN BELIEVED.
     *
     * build() refuses to allocate until it knows the source extent, and
     * downstream (the OSD's "GS settle" state, the size Java hands
     * initContext) reads the same pair. On the GS route that pair is a belief
     * maintained by the resize tracker and every drift between it and the
     * actual image showed up as a silent top-left crop. Here it is read off
     * the image the compositor is about to scan out, so it cannot drift. */
    uint32_t rw = 0, rh = 0;
    if (!fg_query_image_extent(image, &rw, &rh) || !rw || !rh) {
        rw = present_w;
        rh = present_h;
    }
    if (!rw || !rh) return;
    /* REPORT THE CAPTURE EXTENT, NOT THE PANEL'S.
     *
     * S.gs_w/S.gs_h is read downstream as "the size of the frames we hand
     * LSFG", and Java sizes both the AHBs and the interpolation context from
     * it. Reporting the panel here made Java resize the capture to 1280x960
     * while the context stayed 1024x896 — measured as
     * "CAPTURE STALE: ctx 1024x896, pushing 1280x960" over a picture that was
     * live, moving, and cropped. The blit scales, so the source can be any
     * size; what has to be consistent is the destination. Interpolating at the
     * capture size rather than the panel is also the cheaper half of the frame
     * budget: LSFG cost scales with area, and 1024x896 is 0.75x of 1280x960. */
    uint32_t want_w = S.cap_w ? S.cap_w : rw;
    uint32_t want_h = S.cap_h ? S.cap_h : rh;
    if (S.gs_w != want_w || S.gs_h != want_h) {
        FGI("VulkanShim: framegen present source %ux%u -> capture %ux%u",
            (unsigned)rw, (unsigned)rh, (unsigned)want_w, (unsigned)want_h);
        S.gs_w = want_w;
        S.gs_h = want_h;
        /* Same signal the GS route emits, so the Java state machine, the
         * context rebuild and the OSD all work unchanged — sourced from a fact
         * now instead of from a guess about which target is the frame. */
        notify_gs_settled(want_w, want_h);
    }
}

/* Explicit configuration (fg_capture_src=swapchain) enables present recording in
 * the shim, so the route is armed by construction. Without this the present_seen
 * guard below refuses the CONFIGURED mode too, because the conf is parsed before
 * any present has happened -- which silently left capture on the GS target while
 * the log claimed swapchain. The guard is only meant to stop the AUTOMATIC
 * fallback landing on a route nothing fills. */
void fg_arm_present_route(void) { present_seen = 1; }

/* Readable from vulkan_shim, which CAN log at conf-parse time. fg_source's own
 * FGI/FGE go through a logger that is not installed until device creation, so
 * every message this setter emitted at parse time was silently dropped -- which
 * is why neither the success nor the refusal was ever visible. */
int fg_capture_is_from_present(void) { return S.capture_from_present ? 1 : 0; }

int fg_present_route_armed(void) { return present_seen ? 1 : 0; }

void fg_set_capture_source(int from_present) {
    int want = from_present ? 1 : 0;
    if (S.capture_from_present == want) return;
    /* NEVER fall back to a route that was never armed. The shim only records
     * presentable images when fg_capture_src=swapchain (that route needs
     * TRANSFER_SRC, which costs UBWC and produced stride garbage on Adreno 650),
     * so on the default GS route present_image is 0 forever. Switching anyway is
     * how capture wedged at "src=present image=0 built=1" once a second, with no
     * way back. Staying on the GS target at least lets the latch recover. */
    if (want && !present_seen) {
        FGE("VulkanShim: framegen refusing the present-capture fallback: the "
            "swapchain route was never armed (fg_capture_src=gs), so there are "
            "no presentable images to capture. Staying on the GS target.");
        return;
    }
    S.capture_from_present = want;
    /* The context is built around the capture extent, and the two sources have
     * different ones (panel vs GS). Drop the latch so nothing carries over. */
    S.gs_image = 0;
    present_image = 0;
    FGI("VulkanShim: framegen capture source -> %s",
        want ? "presented swapchain image" : "GS target");
}

void fg_set_capture_paused(int on) {
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    if (on) {
        int previous = capture_pause_reasons.fetch_or(LEGACY_PAUSE);
        if (!(previous & LEGACY_PAUSE)) pause_since_ns.store(fg_monotonic_ns());
    } else {
        capture_pause_reasons.fetch_and(~LEGACY_PAUSE);
        pause_since_ns.store(0);
    }
}

// Called only by CapturePauses' serialized publisher. Never clears legacy state.
extern "C" JNIEXPORT void JNICALL
Java_xyz_aethersx2_android_shim_ShimFrameGen_nativeSetCapturePauseReasons(
        JNIEnv *, jclass, jint reasons) {
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    int previous = capture_pause_reasons.load();
    int next = (previous & LEGACY_PAUSE) | (reasons & 15);
    capture_pause_reasons.store(next);
    if ((next & 1) && !(previous & 1)) {
        // Only a resolution change invalidates the GS target. Output binds and
        // context startup must not throw away an otherwise live source image.
        fg_hold_capture();
        gs_seen_ns = 0;
    }
    if (next != previous)
        FGI("VulkanShim: framegen capture pause reasons=0x%x (IR=1 start=2 rebuild=4 bind=8)", next);
}

void fg_set_force(int on) {
    S.fg_force = on ? 1 : 0;
    if (on) S.throttle_active = 0;
}

static void fg_update_present_rate(uint64_t now_ns) {
    if (S.fg_force) return;
    if (S.rate_window_start_ns == 0
            || now_ns - S.rate_window_start_ns > 2000000000ULL) {
        S.rate_window_start_ns = now_ns;
        S.rate_window_presents = 1;
        if (S.rate_window_presents < 90)
            S.throttle_active = 0;
        return;
    }
    S.rate_window_presents++;
    /* ~55+ presents in 2s => game already at panel half-rate; skip capture. */
    if (S.rate_window_presents >= 110) {
        if (!S.throttle_active)
            FGI("VulkanShim: framegen auto-throttle (game ~60fps, capture skipped)");
        S.throttle_active = 1;
    }
    else if (S.rate_window_presents < 90)
        S.throttle_active = 0;
}

void fg_wait_capture_idle(void) {
    std::lock_guard<std::recursive_mutex> guard(g_latch_mu);
    // Discard captures crossing a pause/reset. Keep their submitted flag and
    // fence intact: reuse must still wait for GPU completion AND consumer use.
    for (int i = 0; i < FG_SLOTS; i++)
        if (S.capture[i].submitted) S.capture[i].published = true;
}

static void notify_gs_settled(uint32_t w, uint32_t h) {
    if (!g_fg_class || !g_gs_settled) return;
    int attached = 0;
    JNIEnv *env = jni_env(&attached);
    if (!env) return;
    env->CallStaticVoidMethod(g_fg_class, g_gs_settled, (jint)w, (jint)h);
    if (env->ExceptionCheck()) {
        static int once;
        if (!once++) {
            FGE("VulkanShim: framegen onGsResizeSettledFromNative threw");
            env->ExceptionDescribe();
        }
        env->ExceptionClear();
    }
    if (attached) g_vm->DetachCurrentThread();
}
