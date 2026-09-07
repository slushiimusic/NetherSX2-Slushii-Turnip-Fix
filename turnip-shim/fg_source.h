/* ------------------------------------------------------------------ *
 * fg_source.h - frame source for in-process frame generation.
 *
 * Copies the GS colour target into an AHardwareBuffer once per present
 * and hands it to liblsfg-android.so's NativeBridge.pushFrame, which is
 * indifferent to where its frames come from. As a separate app the only
 * available source is a MediaProjection screen capture - that is where
 * the LSFG-Android project's documented 50-80ms of latency lives. In
 * process there is no capture at all.
 *
 * Two sources, selected by turnip.conf fg_capture_src (default swapchain).
 *
 * SWAPCHAIN (fg_capture_src=swapchain). The image vkQueuePresentKHR is
 * handed. It is the frame by definition, so there is nothing to identify
 * and nothing to get wrong. That matters because identifying the GS target
 * by extent was never soundable: PCSX2 rotates through several targets at
 * the internal resolution and seven distinct VkImages were logged at one
 * extent in a single session. Every capture bug this file has carried is a
 * wrong answer to that question — the top-left crop scaled to fill, the two
 * live handles that both captured static, and the black panel where PCSX2
 * presented from a target the render-pass hook never saw.
 *
 * The old objection to it was about usage bits, not about the image:
 *   GS image   usage=0x97  SAMPLED|COLOR_ATTACHMENT|INPUT_ATTACHMENT
 *                          |TRANSFER_SRC|TRANSFER_DST
 *   swapchain  usage=0x10  COLOR_ATTACHMENT and nothing else
 * A presentable image cannot be copied FROM without TRANSFER_SRC, so the
 * layer now requests it at vkCreateSwapchainKHR. That has a real, measured
 * price — it costs UBWC compression on the presentable image: with the FSR
 * sharpener, IR 4x went 13.61 -> 39.78 ms GPU (fatal), IR native was
 * 7.54 ms (nearly free). It also once produced 733 swapchain recreations at
 * 24 fps, which is why a rejection latches (g_transfer_rejected) instead of
 * being retried.
 *
 * GS TARGET (fg_capture_src=gs). The old route. Cheaper to copy at 512x448
 * and it costs no UBWC, but it has to guess which target is the frame, and
 * it is kept only as the fallback when the surface refuses TRANSFER_SRC.
 *
 * This never blocks the emulator. Two AHardwareBuffers alternate, each
 * with its own fence; if the next one is still in flight the frame is
 * skipped rather than waited on. A dropped frame costs one interpolation;
 * a stalled present costs the frame budget, which is what made the
 * liblsfg-vk.so route unusable (SOTC 60 -> 23 fps).
 *
 * As with lsfg_chain.h and ls_upscale.h, nothing here names a Vulkan
 * type: vulkan_shim.cpp defines its own opaque VkDevice/VkResult that
 * collide with the real headers, so the interface is void * / uint64_t
 * and every real Vulkan struct lives in fg_source.cpp.
 *
 * Inert until fg_set_enabled(1): with turnip.conf lsfg_overlay absent or
 * off nothing is allocated and the present path is untouched.
 * ------------------------------------------------------------------ */
#ifndef FG_SOURCE_H
#define FG_SOURCE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*fg_log_fn)(int is_error, const char *msg);
void fg_set_logger(fg_log_fn fn);

/* turnip.conf gate. Off by default. */
void fg_set_enabled(int on);
int  fg_enabled(void);

/* Device-level entry point everything is resolved through. */
void fg_set_device(void *device, void *device_gpa);

/* Queue and its family, from vkGetDeviceQueue - VkQueue alone does not
 * carry its family, and the command pool needs it. First call wins. */
void fg_note_queue(void *queue, uint32_t family);

/* VkPhysicalDeviceMemoryProperties, copied verbatim. */
void fg_note_memory_properties(const void *props);

/* The GS target the core last began a render pass into, with its extent
 * and format. PCSX2 rotates through several targets at the internal
 * resolution, so this is the one to read each frame. */
void fg_note_current_gs(uint64_t image, uint32_t w, uint32_t h, uint32_t format);

/* The image vkQueuePresentKHR was actually handed, resolved from
 * (pSwapchains[0], pImageIndices[0]). Call immediately before
 * fg_capture_and_push on the present thread.
 *
 * This is the source that makes fg_note_current_gs and everything hanging off
 * it unnecessary. See the header comment revision below: the "swapchain cannot
 * be copied from" objection was about usage bits, which the layer now requests
 * at vkCreateSwapchainKHR — it was never about the swapchain being the wrong
 * image. The swapchain image is the only image that is, by definition, the
 * frame the user sees. */
void fg_note_present_image(uint64_t image, uint32_t w, uint32_t h);

/* 1 = capture the presented image, 0 = capture the latched GS target.
 * turnip.conf fg_capture_src. Switchable at runtime because the price of the
 * presented image (UBWC loss from TRANSFER_SRC) is resolution-dependent. */
void fg_set_capture_source(int from_present);
void fg_arm_present_route(void);
int fg_capture_is_from_present(void);
int fg_present_route_armed(void);
void fg_note_image_layout(uint64_t img, uint32_t layout);
void fg_note_render_queue(void *queue, uint32_t family);

/* Called from the vkDestroyDevice hook. Every Vulkan object framegen holds
 * belongs to that device, so they must be forgotten — WITHOUT destroying them,
 * which is undefined once the device is gone. Cannot be inferred from the device
 * handle changing: drivers reuse the same handle value for the next device. */
void fg_notify_device_lost(void);

/* True extent of a tracked VkImage, straight from vkCreateImage. Implemented in
 * vulkan_shim.cpp, which owns the tracking table. Returns 0 if the handle is not
 * tracked. The blit uses this instead of the resize tracker's belief — see the
 * SOURCE MISMATCH note in fg_capture_and_push. */
int fg_query_image_extent(uint64_t img, uint32_t *w, uint32_t *h);

/* Called from the vkDestroyImage hook before the driver frees the image, so
 * capture can let go of a target that is about to stop existing. */
void fg_notify_image_destroyed(uint64_t img);

/* 1 when w x h is the GS size the user's Internal Resolution implies (+/-8px).
 * Lets the size floors, which assume IR only scales UP, be waived for the
 * sub-native 0.5x/0.75x settings this build's picker offers. */
int fg_expected_gs_is(uint32_t w, uint32_t h);

/* PCSX2 recreated the GS colour target at a new internal resolution.
 * Called from hooked_CreateImage when the observed GS extent changes. */
void fg_notify_gs_resize(uint32_t w, uint32_t h);

/* 512x448 x the user's Internal Resolution, pushed from Java. Targets of any
 * other size are ignored while this one keeps appearing — PCSX2 keeps live
 * targets from every IR previously visited and they all look like a GS. Pass
 * 0,0 to disable. */
void fg_set_expected_gs(uint32_t w, uint32_t h);

/* Capture buffer size (must match initContext on the Java side). Ignored
 * once the AHB slots are built. Default 480x360 if never called. */
void fg_set_capture_size(uint32_t w, uint32_t h);

/* Copy the current GS target and push it to the frame generator. Called
 * from vkQueuePresentKHR. Returns 1 if a frame was pushed, 0 otherwise -
 * either outcome leaves the caller's present untouched. */
int fg_capture_and_push(void);

/* Pause capture+pushes during IR change until GS settles and Java hot-reloads. */
void fg_set_capture_paused(int on);

/* fg_force=on disables auto-throttle for 60fps titles. */
void fg_set_force(int on);

/** turnip.conf fg_push_direct: 0 routes frames through the Java bridge. */
void fg_set_push_direct(int on);

/* OSD status codes — set by vulkan_shim when capture is gated off. */
#define FG_SUPPRESS_NONE    0
#define FG_SUPPRESS_60FPS   1

void fg_set_suppress_reason(int reason);
int  fg_suppress_reason(void);
int  fg_throttled(void);

#ifdef __cplusplus
}
#endif

#endif /* FG_SOURCE_H */
