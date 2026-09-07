// FSR1 spatial upscaler for the NetherSX2 Vulkan shim (AMD FidelityFX FSR 1.x).
//
// Every Vulkan entry point used here is resolved at run time through the host's
// vkGetDeviceProcAddr (see fsr_load_fns), so the real headers are pulled in for
// their *types only*. VK_NO_PROTOTYPES suppresses the function declarations so
// nothing in this file can accidentally acquire a link-time dependency on
// libvulkan — the shim has to keep working with a driver it loaded itself.
//
// This replaced a block of hand-written struct declarations. Several of them
// were the wrong shape (missing fields, invented fields, reordered fields), and
// because the compiler had nothing to check them against, every call built on
// them handed the driver garbage. Do not re-introduce local Vulkan types.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>

#include "fsr_upscaler.h"

#include <android/log.h>
#include <math.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#if defined(NETHER_NO_FSR_FRAMEGEN) && NETHER_NO_FSR_FRAMEGEN
#define NETHER_FSR_FRAMEGEN_ACTIVE 0
#else
#define NETHER_FSR_FRAMEGEN_ACTIVE 1
#endif

#include "fsr/fsr_easu_spv.h"
#include "fsr/fsr_rcas_spv.h"
#if NETHER_FSR_FRAMEGEN_ACTIVE
#include "fsr/fg_blend_spv.h"
#include "fsr/fg_flow_spv.h"
#endif

extern "C" void fsr_cpu_populate_easu(uint32_t con[4][4],
                                      float input_w, float input_h,
                                      float output_w, float output_h);
extern "C" void fsr_cpu_populate_rcas(uint32_t con[4], float sharpness);

#define FSR_TAG "VulkanShim"

static void (*g_logi)(const char *) = NULL;
static void (*g_loge)(const char *) = NULL;

static void fsr_logi(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (g_logi) g_logi(buf);
    else __android_log_write(ANDROID_LOG_INFO, FSR_TAG, buf);
}

static void fsr_loge(const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (g_loge) g_loge(buf);
    else __android_log_write(ANDROID_LOG_ERROR, FSR_TAG, buf);
}

/* ------------------------------------------------------------------ */
/* Config                                                              */
/* ------------------------------------------------------------------ */
static int   g_upscaler  = FSR_UPSCALER_OFF;
static float g_sharpness = 0.5f;
static int   g_quality   = FSR_QUALITY_BALANCED;

static int   g_framegen_enabled = 0;
static int   g_framegen_mode    = FG_MODE_BLEND;
static float g_framegen_alpha   = 0.5f;
static int   g_fg_multiplier    = 2;
static float g_flow_scale       = 0.75f;
static float g_fg_display_hz    = 60.0f;

static uint64_t g_last_present_ns = 0;
static double   g_ft_ring[15];
static int      g_ft_count = 0;

static float g_viewport_x = 0.0f;
static float g_viewport_y = 0.0f;
static float g_viewport_w = 0.0f;
static float g_viewport_h = 0.0f;
static int   g_viewport_ready = 0;

/* One log line per transition of the sharpen pass: exactly one "active" line when
 * it starts running and exactly one "stopped" line when it stops. */
static int g_rcas_active_logged = 0;
static int g_easu_active_logged = 0;

/* Fraction of the panel the application is told to render at. 0 = off. */
static float g_rs_scale = 0.0f;
static int   g_rs_off_logged = 0;

static void fsr_rcas_log_stopped(const char *why) {
    if (!g_rcas_active_logged) return;
    g_rcas_active_logged = 0;
    fsr_logi("FSR: RCAS stopped (%s)", why ? why : "off");
}

void fsr_set_config(int upscaler, float sharpness, int quality) {
    /* Toggling the upscaler off is the common case for the "stopped" line: with
     * both FSR and framegen off the shim stops calling fsr_on_queue_present
     * altogether, so nothing downstream would ever notice the transition. */
    if (upscaler != FSR_UPSCALER_FSR1)
        fsr_rcas_log_stopped("upscaler off");
    g_upscaler  = upscaler;
    g_sharpness = sharpness;
    if (g_quality != quality) {
        g_quality = quality;
    }
}

void fg_set_config(int enabled, int mode, float blend_alpha) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
    g_framegen_enabled = enabled ? 1 : 0;
    g_framegen_mode = mode;
    g_framegen_alpha = blend_alpha;
    if (g_framegen_alpha < 0.0f) g_framegen_alpha = 0.0f;
    if (g_framegen_alpha > 1.0f) g_framegen_alpha = 1.0f;
#else
    (void)enabled;
    (void)mode;
    (void)blend_alpha;
#endif
}

void fg_set_extended(int multiplier, float flow_scale, float display_hz) {
#if NETHER_FSR_FRAMEGEN_ACTIVE
    if (multiplier < 2) multiplier = 2;
    if (multiplier > 4) multiplier = 4;
    g_fg_multiplier = multiplier;
    if (flow_scale < 0.25f) flow_scale = 0.25f;
    if (flow_scale > 1.0f) flow_scale = 1.0f;
    g_flow_scale = flow_scale;
    if (display_hz > 0.0f) g_fg_display_hz = display_hz;
#else
    (void)multiplier;
    (void)flow_scale;
    (void)display_hz;
#endif
}

void fsr_set_log_fn(void (*logi)(const char *), void (*loge)(const char *)) {
    g_logi = logi;
    g_loge = loge;
}

void fsr_on_viewport(float x, float y, float width, float height) {
    if (width > 1.0f && height > 1.0f) {
        g_viewport_x = x;
        g_viewport_y = y;
        g_viewport_w = width;
        g_viewport_h = height;
        g_viewport_ready = 1;
    }
}

int fsr_viewport_is_ready(void) {
    return g_viewport_ready && g_viewport_w > 8.0f && g_viewport_h > 8.0f;
}

void fsr_frame_tick(void) {
    /* Keep last viewport until vkCmdSetViewport updates it — clearing here broke
     * FSR when SetViewport was not re-issued every frame before present. */
}

static float quality_scale(int q) {
    switch (q) {
        case FSR_QUALITY_PERFORMANCE: return 0.50f;
        case FSR_QUALITY_ULTRA:       return 0.77f;
        case FSR_QUALITY_4X:          return 0.25f;
        default:                      return 0.59f; /* balanced */
    }
}

/* ------------------------------------------------------------------ */
/* Dynamically loaded device dispatch table                            */
/*                                                                     */
/* Every PFN_vk* below comes from the real Vulkan header included at the */
/* top; the shim only stores pointers it resolved itself (fsr_load_fns). */
/* ------------------------------------------------------------------ */
struct FsrVkFns {
    PFN_vkCreateImage vkCreateImage;
    PFN_vkDestroyImage vkDestroyImage;
    PFN_vkCreateImageView vkCreateImageView;
    PFN_vkDestroyImageView vkDestroyImageView;
    PFN_vkAllocateMemory vkAllocateMemory;
    PFN_vkFreeMemory vkFreeMemory;
    PFN_vkBindImageMemory vkBindImageMemory;
    PFN_vkGetImageMemoryRequirements vkGetImageMemoryRequirements;
    PFN_vkCreateBuffer vkCreateBuffer;
    PFN_vkDestroyBuffer vkDestroyBuffer;
    PFN_vkBindBufferMemory vkBindBufferMemory;
    PFN_vkGetBufferMemoryRequirements vkGetBufferMemoryRequirements;
    PFN_vkCreateCommandPool vkCreateCommandPool;
    PFN_vkDestroyCommandPool vkDestroyCommandPool;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers;
    PFN_vkFreeCommandBuffers vkFreeCommandBuffers;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer;
    PFN_vkEndCommandBuffer vkEndCommandBuffer;
    PFN_vkCmdPipelineBarrier vkCmdPipelineBarrier;
    PFN_vkCmdBindPipeline vkCmdBindPipeline;
    PFN_vkCmdBindDescriptorSets vkCmdBindDescriptorSets;
    PFN_vkCmdDispatch vkCmdDispatch;
    PFN_vkCmdCopyImage vkCmdCopyImage;
    PFN_vkCmdBlitImage vkCmdBlitImage;
    PFN_vkCmdCopyImage2 vkCmdCopyImage2;
    PFN_vkCmdBlitImage2 vkCmdBlitImage2;
    PFN_vkResetCommandBuffer vkResetCommandBuffer;
    PFN_vkCreateShaderModule vkCreateShaderModule;
    PFN_vkDestroyShaderModule vkDestroyShaderModule;
    PFN_vkCreateDescriptorSetLayout vkCreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout vkDestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool vkCreateDescriptorPool;
    PFN_vkDestroyDescriptorPool vkDestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets vkAllocateDescriptorSets;
    PFN_vkUpdateDescriptorSets vkUpdateDescriptorSets;
    PFN_vkCreatePipelineLayout vkCreatePipelineLayout;
    PFN_vkDestroyPipelineLayout vkDestroyPipelineLayout;
    PFN_vkCreateComputePipelines vkCreateComputePipelines;
    PFN_vkDestroyPipeline vkDestroyPipeline;
    PFN_vkCreateSampler vkCreateSampler;
    PFN_vkDestroySampler vkDestroySampler;
    PFN_vkCreateFence vkCreateFence;
    PFN_vkDestroyFence vkDestroyFence;
    PFN_vkWaitForFences vkWaitForFences;
    PFN_vkResetFences vkResetFences;
    /* Soft-loaded (see fsr_load_fns): without them the shim keeps the old
     * host-drain submit instead of failing the whole device bind. */
    PFN_vkCreateSemaphore vkCreateSemaphore;
    PFN_vkDestroySemaphore vkDestroySemaphore;
    PFN_vkQueueSubmit vkQueueSubmit;
    PFN_vkGetPhysicalDeviceMemoryProperties vkGetPhysicalDeviceMemoryProperties;
    PFN_vkGetSwapchainImagesKHR vkGetSwapchainImagesKHR;
    PFN_vkDeviceWaitIdle vkDeviceWaitIdle;
    PFN_vkMapMemory vkMapMemory;
    PFN_vkUnmapMemory vkUnmapMemory;
};

struct FsrImage {
    VkImage        image;
    VkImageView    view;
    VkDeviceMemory memory;
    uint32_t       width;
    uint32_t       height;
    uint32_t       format;
};

struct FsrEasuUbo {
    uint32_t con0[4];
    uint32_t con1[4];
    uint32_t con2[4];
    uint32_t con3[4];
    uint32_t out_size[2];
    uint32_t pad[2];
};

struct FsrRcasUbo {
    uint32_t con[4];
    uint32_t out_size[2];
    uint32_t pad[2];
};

struct FgInterpUbo {
    float    alpha;
    float    flow_scale;
    uint32_t width;
    uint32_t height;
};

struct FsrSwapchainState {
    VkSwapchainKHR swapchain;
    uint32_t       out_w;
    uint32_t       out_h;
    uint32_t       format;
    VkImage       *images;
    uint32_t       image_count;
    uint32_t       present_count;

    uint32_t       in_w;
    uint32_t       in_h;

    FsrImage       input_img;
    FsrImage       work_img;
    FsrImage       final_img;

    VkBuffer       easu_ubo;
    VkDeviceMemory easu_ubo_mem;
    VkBuffer       rcas_ubo;
    VkDeviceMemory rcas_ubo_mem;

    VkSampler      sampler;
    VkDescriptorSetLayout easu_dsl;
    VkDescriptorSetLayout rcas_dsl;
    VkDescriptorPool      desc_pool;
    VkDescriptorSet       easu_ds;
    VkDescriptorSet       rcas_ds;
    VkPipelineLayout      easu_pl;
    VkPipelineLayout      rcas_pl;
    VkPipeline            easu_pipe;
    VkPipeline            rcas_pipe;

    VkCommandPool   cmd_pool;
    /* The slot currently being recorded. NOT owned: both alias into the ring
     * below and are re-pointed by fsr_acquire_slot() once per present, so every
     * fsr_record_ and fg_record_ helper can keep reading s->cmd_buf unchanged. */
    VkCommandBuffer cmd_buf;
    VkFence         fence;

    /* One command buffer + fence + signal semaphore per swapchain image index.
     *
     * A single reused command buffer forces a host drain before every
     * re-record, which is what made the presenting thread block on the GPU. One
     * set per image index means the set we are about to re-record was last used
     * `image_count` presents ago and the GPU is long done with it, so the fence
     * wait in fsr_acquire_slot() is already satisfied in steady state.
     *
     * ring_pending[i] tracks whether fences[i] actually has a submission behind
     * it. Without it the very first use of each slot — and any present that
     * bailed out after acquiring a slot but before submitting — would wait the
     * full timeout on a fence nothing will ever signal. */
    VkCommandBuffer *ring_cmd;
    VkFence         *ring_fence;
    VkSemaphore     *ring_sem;
    uint8_t         *ring_pending;
    uint32_t         ring_count;
    uint32_t         cur_slot;

    int             gpu_ready;

    /* Frame generation (synthetic blend between consecutive frames) */
    FsrImage        fg_history_img;
    FsrImage        fg_backup_img;
    FsrImage        fg_scratch_img;
    VkBuffer        fg_ubo;
    VkDeviceMemory  fg_ubo_mem;
    VkDescriptorSetLayout fg_dsl;
    VkDescriptorPool      fg_desc_pool;
    VkDescriptorSet       fg_ds;
    VkPipelineLayout      fg_pl;
    VkPipeline            fg_pipe;
    VkPipeline            fg_flow_pipe;
    int             fg_gpu_ready;
    int             fg_history_ready;

    /* Render-scale: images handed to the application in place of the real
     * swapchain images, index-aligned with images[] above. The application
     * renders into app_images[i]; images[i] is what actually gets presented. */
    FsrImage       *app_images;
    uint32_t        app_image_count;
    uint32_t        rs_w;
    uint32_t        rs_h;
    int             rs_active;   /* images exist */
    int             rs_handed;   /* ...and the app has taken them */
};

struct FsrDeviceState {
    VkDevice              device;
    VkPhysicalDevice      physical_device;
    FsrVkFns              fn;
    FsrSwapchainState    *swapchains;
    uint32_t              swapchain_count;
    uint32_t              queue_family;
    int                   initialized;
};

static FsrDeviceState *g_dev = NULL;

#define LOAD_DEV_FN(name) \
    fn->name = (PFN_##name)get_proc(device, #name); \
    if (!fn->name) { fsr_loge("FSR: missing " #name); return 0; }

static int fsr_load_fns(VkDevice device, PFN_fsr_vk_get_device_proc get_proc, FsrVkFns *fn) {
    memset(fn, 0, sizeof(*fn));
    LOAD_DEV_FN(vkCreateImage);
    LOAD_DEV_FN(vkDestroyImage);
    LOAD_DEV_FN(vkCreateImageView);
    LOAD_DEV_FN(vkDestroyImageView);
    LOAD_DEV_FN(vkAllocateMemory);
    LOAD_DEV_FN(vkFreeMemory);
    LOAD_DEV_FN(vkBindImageMemory);
    LOAD_DEV_FN(vkGetImageMemoryRequirements);
    LOAD_DEV_FN(vkCreateBuffer);
    LOAD_DEV_FN(vkDestroyBuffer);
    LOAD_DEV_FN(vkBindBufferMemory);
    LOAD_DEV_FN(vkGetBufferMemoryRequirements);
    LOAD_DEV_FN(vkCreateCommandPool);
    LOAD_DEV_FN(vkDestroyCommandPool);
    LOAD_DEV_FN(vkAllocateCommandBuffers);
    LOAD_DEV_FN(vkFreeCommandBuffers);
    LOAD_DEV_FN(vkBeginCommandBuffer);
    LOAD_DEV_FN(vkEndCommandBuffer);
    LOAD_DEV_FN(vkCmdPipelineBarrier);
    LOAD_DEV_FN(vkCmdBindPipeline);
    LOAD_DEV_FN(vkCmdBindDescriptorSets);
    LOAD_DEV_FN(vkCmdDispatch);
    fn->vkCmdCopyImage2 = (PFN_vkCmdCopyImage2)get_proc(device, "vkCmdCopyImage2");
    if (!fn->vkCmdCopyImage2)
        fn->vkCmdCopyImage2 = (PFN_vkCmdCopyImage2)get_proc(device, "vkCmdCopyImage2KHR");
    fn->vkCmdBlitImage2 = (PFN_vkCmdBlitImage2)get_proc(device, "vkCmdBlitImage2");
    if (!fn->vkCmdBlitImage2)
        fn->vkCmdBlitImage2 = (PFN_vkCmdBlitImage2)get_proc(device, "vkCmdBlitImage2KHR");
    if (!fn->vkCmdCopyImage2) {
        LOAD_DEV_FN(vkCmdCopyImage);
        fsr_logi("FSR: vkCmdCopyImage2 unavailable, using vkCmdCopyImage");
    }
    if (!fn->vkCmdBlitImage2) {
        LOAD_DEV_FN(vkCmdBlitImage);
        fsr_logi("FSR: vkCmdBlitImage2 unavailable, using vkCmdBlitImage");
    }
    LOAD_DEV_FN(vkResetCommandBuffer);
    LOAD_DEV_FN(vkCreateShaderModule);
    LOAD_DEV_FN(vkDestroyShaderModule);
    LOAD_DEV_FN(vkCreateDescriptorSetLayout);
    LOAD_DEV_FN(vkDestroyDescriptorSetLayout);
    LOAD_DEV_FN(vkCreateDescriptorPool);
    LOAD_DEV_FN(vkDestroyDescriptorPool);
    LOAD_DEV_FN(vkAllocateDescriptorSets);
    LOAD_DEV_FN(vkUpdateDescriptorSets);
    LOAD_DEV_FN(vkCreatePipelineLayout);
    LOAD_DEV_FN(vkDestroyPipelineLayout);
    LOAD_DEV_FN(vkCreateComputePipelines);
    LOAD_DEV_FN(vkDestroyPipeline);
    LOAD_DEV_FN(vkCreateSampler);
    LOAD_DEV_FN(vkDestroySampler);
    LOAD_DEV_FN(vkCreateFence);
    LOAD_DEV_FN(vkDestroyFence);
    LOAD_DEV_FN(vkWaitForFences);
    LOAD_DEV_FN(vkResetFences);
    LOAD_DEV_FN(vkQueueSubmit);
    /* Deliberately soft: these are core 1.0 and should always resolve, but a
     * missing one must degrade to the host-drain submit path rather than fail
     * fsr_bind_device and take the whole upscaler out with it. */
    fn->vkCreateSemaphore = (PFN_vkCreateSemaphore)get_proc(device, "vkCreateSemaphore");
    fn->vkDestroySemaphore = (PFN_vkDestroySemaphore)get_proc(device, "vkDestroySemaphore");
    if (!fn->vkCreateSemaphore || !fn->vkDestroySemaphore)
        fsr_logi("FSR: semaphores unavailable — submits will host-drain");
    LOAD_DEV_FN(vkGetSwapchainImagesKHR);
    LOAD_DEV_FN(vkDeviceWaitIdle);
    fn->vkMapMemory = (PFN_vkMapMemory)get_proc(device, "vkMapMemory");
    fn->vkUnmapMemory = (PFN_vkUnmapMemory)get_proc(device, "vkUnmapMemory");
    return 1;
}

static uint32_t fsr_find_memory_type(FsrDeviceState *d, uint32_t type_bits, uint32_t props) {
    VkPhysicalDeviceMemoryProperties mp;
    if (d->fn.vkGetPhysicalDeviceMemoryProperties)
        d->fn.vkGetPhysicalDeviceMemoryProperties(d->physical_device, &mp);
    else {
        /* No query available. Guessing a type index silently binds host-visible
         * allocations to device-local memory, which then fails to map with no
         * error anywhere — fail loudly instead. */
        fsr_loge("FSR: memory type query unavailable (props=0x%x) — refusing to guess",
                 props);
        return UINT32_MAX;
    }
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((type_bits & (1u << i)) &&
            (mp.memoryTypes[i].propertyFlags & props) == props)
            return i;
    }
    /* Falling back to index 0 here would bind memory that does not have the
     * requested properties, which shows up much later as a failed map. */
    fsr_loge("FSR: no memory type with props=0x%x in mask=0x%x", props, type_bits);
    return UINT32_MAX;
}

/* All of the shim's images are single-mip, single-layer 2D colour images. */
static VkImageSubresourceRange fsr_color_range(void) {
    VkImageSubresourceRange r;
    r.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
    r.baseMipLevel   = 0;
    r.levelCount     = 1;
    r.baseArrayLayer = 0;
    r.layerCount     = 1;
    return r;
}

static VkImageSubresourceLayers fsr_color_layers(void) {
    VkImageSubresourceLayers l;
    l.aspectMask     = VK_IMAGE_ASPECT_COLOR_BIT;
    l.mipLevel       = 0;
    l.baseArrayLayer = 0;
    l.layerCount     = 1;
    return l;
}

static void fsr_barrier(FsrDeviceState *d, VkCommandBuffer cmd,
                        VkImage img, VkImageLayout old_layout, VkImageLayout new_layout,
                        VkAccessFlags src_access, VkAccessFlags dst_access,
                        VkPipelineStageFlags src_stage, VkPipelineStageFlags dst_stage) {
    if (!d || !cmd || !d->fn.vkCmdPipelineBarrier) return;
    VkImageMemoryBarrier b;
    memset(&b, 0, sizeof(b));
    b.sType               = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask       = src_access;
    b.dstAccessMask       = dst_access;
    b.oldLayout           = old_layout;
    b.newLayout           = new_layout;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image               = img;
    b.subresourceRange    = fsr_color_range();
    d->fn.vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0,
                               0, NULL, 0, NULL, 1, &b);
}

/* vkCmdCopyImage2/vkCmdBlitImage2 are Vulkan 1.3. Where the driver only offers
 * the 1.0 entry points, the regions have to be repacked: VkImageCopy is
 * VkImageCopy2 without sType/pNext, and VkImageBlit is VkImageBlit2 likewise. */
static void fsr_cmd_copy_image2(FsrVkFns *fn, VkCommandBuffer cmd, const VkCopyImageInfo2 *info) {
    if (!fn || !cmd || !info || !info->pRegions || info->regionCount == 0) return;
    if (fn->vkCmdCopyImage2) {
        fn->vkCmdCopyImage2(cmd, info);
        return;
    }
    if (!fn->vkCmdCopyImage) return;
    VkImageCopy regions[4];
    uint32_t count = info->regionCount > 4 ? 4 : info->regionCount;
    for (uint32_t i = 0; i < count; i++) {
        regions[i].srcSubresource = info->pRegions[i].srcSubresource;
        regions[i].srcOffset      = info->pRegions[i].srcOffset;
        regions[i].dstSubresource = info->pRegions[i].dstSubresource;
        regions[i].dstOffset      = info->pRegions[i].dstOffset;
        regions[i].extent         = info->pRegions[i].extent;
    }
    fn->vkCmdCopyImage(cmd, info->srcImage, info->srcImageLayout,
                       info->dstImage, info->dstImageLayout,
                       count, regions);
}

static void fsr_cmd_blit_image2(FsrVkFns *fn, VkCommandBuffer cmd, const VkBlitImageInfo2 *info) {
    if (!fn || !cmd || !info || !info->pRegions || info->regionCount == 0) return;
    if (fn->vkCmdBlitImage2) {
        fn->vkCmdBlitImage2(cmd, info);
        return;
    }
    if (!fn->vkCmdBlitImage) return;
    VkImageBlit blits[4];
    uint32_t count = info->regionCount > 4 ? 4 : info->regionCount;
    for (uint32_t i = 0; i < count; i++) {
        blits[i].srcSubresource = info->pRegions[i].srcSubresource;
        blits[i].srcOffsets[0]  = info->pRegions[i].srcOffsets[0];
        blits[i].srcOffsets[1]  = info->pRegions[i].srcOffsets[1];
        blits[i].dstSubresource = info->pRegions[i].dstSubresource;
        blits[i].dstOffsets[0]  = info->pRegions[i].dstOffsets[0];
        blits[i].dstOffsets[1]  = info->pRegions[i].dstOffsets[1];
    }
    fn->vkCmdBlitImage(cmd, info->srcImage, info->srcImageLayout,
                       info->dstImage, info->dstImageLayout,
                       count, blits, info->filter);
}

static int fsr_create_image(FsrDeviceState *d, FsrImage *out,
                            uint32_t w, uint32_t h, VkFormat format,
                            VkImageUsageFlags usage) {
    FsrVkFns *fn = &d->fn;
    VkImageCreateInfo ici;
    memset(&ici, 0, sizeof(ici));
    ici.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = format;
    ici.extent.width = w;
    ici.extent.height = h;
    ici.extent.depth = 1;
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    /* The hand-rolled VkImageCreateInfo had no `samples` field at all, so every
     * field from `tiling` on landed one slot early and the driver read
     * samples == VK_IMAGE_TILING_OPTIMAL == 0, which is not a legal sample
     * count. vkCreateImage could never succeed. */
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = usage;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (fn->vkCreateImage(d->device, &ici, NULL, &out->image) != VK_SUCCESS)
        return 0;

    VkMemoryRequirements req;
    fn->vkGetImageMemoryRequirements(d->device, out->image, &req);
    VkMemoryAllocateInfo mai;
    memset(&mai, 0, sizeof(mai));
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = fsr_find_memory_type(d, req.memoryTypeBits,
                                                 VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (fn->vkAllocateMemory(d->device, &mai, NULL, &out->memory) != VK_SUCCESS)
        return 0;
    fn->vkBindImageMemory(d->device, out->image, out->memory, 0);

    VkImageViewCreateInfo vci;
    memset(&vci, 0, sizeof(vci));
    vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vci.image = out->image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = format;
    vci.subresourceRange = fsr_color_range();

    if (fn->vkCreateImageView(d->device, &vci, NULL, &out->view) != VK_SUCCESS)
        return 0;

    out->width = w;
    out->height = h;
    out->format = format;
    return 1;
}

static void fsr_destroy_image(FsrDeviceState *d, FsrImage *img) {
    if (!img->image) return;
    if (img->view) d->fn.vkDestroyImageView(d->device, img->view, NULL);
    if (img->image) d->fn.vkDestroyImage(d->device, img->image, NULL);
    if (img->memory) d->fn.vkFreeMemory(d->device, img->memory, NULL);
    memset(img, 0, sizeof(*img));
}

static VkShaderModule fsr_create_shader(FsrDeviceState *d, const uint32_t *code, uint32_t size) {
    VkShaderModuleCreateInfo smci;
    memset(&smci, 0, sizeof(smci));
    smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smci.codeSize = size;
    smci.pCode = code;
    VkShaderModule mod = VK_NULL_HANDLE;
    if (d->fn.vkCreateShaderModule(d->device, &smci, NULL, &mod) != VK_SUCCESS)
        return VK_NULL_HANDLE;
    return mod;
}

static int fsr_create_compute_pipeline_only(FsrDeviceState *d, const uint32_t *spv, uint32_t spv_size,
                                            VkPipelineLayout pl, VkPipeline *out_pipe) {
    VkShaderModule mod = fsr_create_shader(d, spv, spv_size);
    if (!mod) return 0;

    VkPipelineShaderStageCreateInfo stage;
    memset(&stage, 0, sizeof(stage));
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = mod;
    stage.pName = "main";

    VkComputePipelineCreateInfo pci;
    memset(&pci, 0, sizeof(pci));
    pci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pci.stage = stage;
    pci.layout = pl;

    VkResult r = d->fn.vkCreateComputePipelines(d->device, NULL, 1, &pci, NULL, out_pipe);
    d->fn.vkDestroyShaderModule(d->device, mod, NULL);
    return r == VK_SUCCESS;
}

static int fsr_create_compute_pipe(FsrDeviceState *d, const uint32_t *spv, uint32_t spv_size,
                                   VkDescriptorSetLayout dsl, VkPipelineLayout *out_pl,
                                   VkPipeline *out_pipe) {
    VkShaderModule mod = fsr_create_shader(d, spv, spv_size);
    if (!mod) return 0;

    VkPipelineLayoutCreateInfo plci;
    memset(&plci, 0, sizeof(plci));
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &dsl;
    if (d->fn.vkCreatePipelineLayout(d->device, &plci, NULL, out_pl) != VK_SUCCESS) {
        d->fn.vkDestroyShaderModule(d->device, mod, NULL);
        return 0;
    }

    VkPipelineShaderStageCreateInfo stage;
    memset(&stage, 0, sizeof(stage));
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = mod;
    stage.pName = "main";

    VkComputePipelineCreateInfo pci;
    memset(&pci, 0, sizeof(pci));
    pci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pci.stage = stage;
    pci.layout = *out_pl;

    VkResult r = d->fn.vkCreateComputePipelines(d->device, NULL, 1, &pci, NULL, out_pipe);
    d->fn.vkDestroyShaderModule(d->device, mod, NULL);
    return r == VK_SUCCESS;
}

static FsrSwapchainState *fsr_find_swapchain(FsrDeviceState *d, VkSwapchainKHR sc) {
    for (uint32_t i = 0; i < d->swapchain_count; i++)
        if (d->swapchains[i].swapchain == sc)
            return &d->swapchains[i];
    return NULL;
}

/* One second. Long enough that a merely busy GPU never trips it, short enough
 * that a genuinely wrong ring reports itself instead of hanging the GS thread
 * forever. Used by both the ring wait and the drain submit. */
#define FSR_FENCE_TIMEOUT_NS 1000000000ull

/* True when this slot can order the present GPU-side instead of host-draining. */
static int fsr_async_ok(FsrDeviceState *d, FsrSwapchainState *s) {
    return d->fn.vkCreateSemaphore && d->fn.vkDestroySemaphore &&
           s->ring_sem && s->cur_slot < s->ring_count &&
           s->ring_sem[s->cur_slot] != VK_NULL_HANDLE;
}

/* Retire every slot that still has a submission behind it. Only used off the
 * fast path: before tearing down images an in-flight command buffer may still
 * reference, and before freeing the ring itself. */
static void fsr_ring_wait_all(FsrDeviceState *d, FsrSwapchainState *s) {
    if (!s->ring_fence || !s->ring_pending || !d->fn.vkWaitForFences) return;
    for (uint32_t i = 0; i < s->ring_count; i++) {
        if (!s->ring_pending[i] || s->ring_fence[i] == VK_NULL_HANDLE) continue;
        VkResult wr = d->fn.vkWaitForFences(d->device, 1, &s->ring_fence[i],
                                            VK_TRUE, FSR_FENCE_TIMEOUT_NS);
        if (wr != VK_SUCCESS)
            fsr_loge("FSR: fence ring drain slot %u failed %d", i, (int)wr);
        s->ring_pending[i] = 0;
    }
}

static void fsr_ring_free(FsrDeviceState *d, FsrSwapchainState *s) {
    fsr_ring_wait_all(d, s);
    if (s->ring_sem && d->fn.vkDestroySemaphore) {
        for (uint32_t i = 0; i < s->ring_count; i++)
            if (s->ring_sem[i] != VK_NULL_HANDLE)
                d->fn.vkDestroySemaphore(d->device, s->ring_sem[i], NULL);
    }
    if (s->ring_fence) {
        for (uint32_t i = 0; i < s->ring_count; i++)
            if (s->ring_fence[i] != VK_NULL_HANDLE)
                d->fn.vkDestroyFence(d->device, s->ring_fence[i], NULL);
    }
    /* vkDestroyCommandPool frees every buffer allocated from it, so the ring
     * command buffers need no separate free. */
    if (s->cmd_pool) {
        d->fn.vkDestroyCommandPool(d->device, s->cmd_pool, NULL);
        s->cmd_pool = NULL;
    }
    free(s->ring_cmd);
    free(s->ring_fence);
    free(s->ring_sem);
    free(s->ring_pending);
    s->ring_cmd = NULL;
    s->ring_fence = NULL;
    s->ring_sem = NULL;
    s->ring_pending = NULL;
    s->ring_count = 0;
    s->cur_slot = 0;
    s->cmd_buf = NULL;
    s->fence = NULL;
}

/* Allocate the pool and one command buffer / fence / semaphore per swapchain
 * image index. Idempotent, and the single allocation site for all of it —
 * fsr_ensure_gpu() and fg_ensure_gpu() both come through here. */
static int fsr_ensure_cmd_fence(FsrDeviceState *d, FsrSwapchainState *s) {
    if (s->cmd_pool && s->ring_cmd && s->ring_fence && s->ring_sem &&
        s->ring_pending && s->ring_count == s->image_count && s->ring_count > 0)
        return 1;

    static int size_fail_logged = 0;
    if (s->image_count == 0) {
        if (!size_fail_logged) {
            size_fail_logged = 1;
            fsr_loge("FSR: swapchain reports 0 images — no command ring");
        }
        return 0;
    }

    /* A stale ring (image_count changed under us) is torn down whole rather than
     * grown, so slot indices never outlive the swapchain they were sized for. */
    if (s->ring_count != s->image_count)
        fsr_ring_free(d, s);

    VkCommandPoolCreateInfo cpci;
    memset(&cpci, 0, sizeof(cpci));
    cpci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpci.queueFamilyIndex = d->queue_family;
    cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (!s->cmd_pool &&
        d->fn.vkCreateCommandPool(d->device, &cpci, NULL, &s->cmd_pool) != VK_SUCCESS)
        return 0;

    const uint32_t n = s->image_count;
    if (!s->ring_cmd)     s->ring_cmd     = (VkCommandBuffer *)calloc(n, sizeof(VkCommandBuffer));
    if (!s->ring_fence)   s->ring_fence   = (VkFence *)calloc(n, sizeof(VkFence));
    if (!s->ring_sem)     s->ring_sem     = (VkSemaphore *)calloc(n, sizeof(VkSemaphore));
    if (!s->ring_pending) s->ring_pending = (uint8_t *)calloc(n, sizeof(uint8_t));
    if (!s->ring_cmd || !s->ring_fence || !s->ring_sem || !s->ring_pending) {
        fsr_loge("FSR: out of memory sizing command ring to %u", n);
        return 0;
    }
    s->ring_count = n;

    VkCommandBufferAllocateInfo cbai;
    memset(&cbai, 0, sizeof(cbai));
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = s->cmd_pool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = n;
    if (d->fn.vkAllocateCommandBuffers(d->device, &cbai, s->ring_cmd) != VK_SUCCESS) {
        fsr_loge("FSR: vkAllocateCommandBuffers failed for %u slots", n);
        return 0;
    }

    /* Fences start UNSIGNALLED, matching what the single fence always did.
     * ring_pending[] — not the fence state — is what keeps the first wait on
     * each slot from blocking for the full timeout. */
    for (uint32_t i = 0; i < n; i++) {
        VkFenceCreateInfo fci;
        memset(&fci, 0, sizeof(fci));
        fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        if (d->fn.vkCreateFence(d->device, &fci, NULL, &s->ring_fence[i]) != VK_SUCCESS) {
            fsr_loge("FSR: vkCreateFence failed for slot %u", i);
            return 0;
        }
        if (d->fn.vkCreateSemaphore) {
            VkSemaphoreCreateInfo sci;
            memset(&sci, 0, sizeof(sci));
            sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
            if (d->fn.vkCreateSemaphore(d->device, &sci, NULL, &s->ring_sem[i]) != VK_SUCCESS) {
                /* Not fatal: fsr_async_ok() sees the null handle and this slot
                 * falls back to the host drain. */
                s->ring_sem[i] = VK_NULL_HANDLE;
                fsr_loge("FSR: vkCreateSemaphore failed for slot %u — slot drains", i);
            }
        }
    }

    fsr_logi("FSR: command ring %u slots (per swapchain image index)", n);
    return 1;
}

/* Point s->cmd_buf / s->fence at this image index's slot and make it safe to
 * re-record. Waits on THAT INDEX'S fence only — in steady state the submission
 * behind it is `image_count` presents old and already retired, so this does not
 * stall. Returns 0 without touching the slot if the wait did not complete;
 * re-recording a command buffer whose submission is still pending is undefined,
 * so the caller must present untouched instead. */
static int fsr_acquire_slot(FsrDeviceState *d, FsrSwapchainState *s, uint32_t index) {
    if (!s->ring_cmd || !s->ring_fence || !s->ring_pending || index >= s->ring_count)
        return 0;
    if (!d->fn.vkWaitForFences || !d->fn.vkResetFences || !d->fn.vkResetCommandBuffer)
        return 0;

    if (s->ring_pending[index]) {
        VkResult wr = d->fn.vkWaitForFences(d->device, 1, &s->ring_fence[index],
                                            VK_TRUE, FSR_FENCE_TIMEOUT_NS);
        if (wr != VK_SUCCESS) {
            /* VK_TIMEOUT (2) here means the ring is wrong — a slot was reused
             * before its previous submission retired. */
            fsr_loge("FSR: fence ring timeout slot %u result %d — present passthrough",
                     index, (int)wr);
            return 0;
        }
        s->ring_pending[index] = 0;
        d->fn.vkResetFences(d->device, 1, &s->ring_fence[index]);
    }

    VkResult rr = d->fn.vkResetCommandBuffer(s->ring_cmd[index], 0);
    if (rr != VK_SUCCESS) {
        fsr_loge("FSR: vkResetCommandBuffer slot %u failed %d", index, (int)rr);
        return 0;
    }

    s->cur_slot = index;
    s->cmd_buf = s->ring_cmd[index];
    s->fence = s->ring_fence[index];
    return 1;
}

/* Replace a slot's signal semaphore after a present that may never have waited
 * on it. A binary semaphore our submit signalled but nothing consumed stays
 * signalled forever, and signalling it again next frame is invalid usage. Retire
 * the submit first so no signal operation is still pending against the object. */
static void fsr_slot_recreate_sem(FsrDeviceState *d, FsrSwapchainState *s, uint32_t slot) {
    if (!s->ring_sem || slot >= s->ring_count) return;
    if (!d->fn.vkCreateSemaphore || !d->fn.vkDestroySemaphore) return;

    if (s->ring_pending && s->ring_pending[slot] && d->fn.vkWaitForFences) {
        VkResult wr = d->fn.vkWaitForFences(d->device, 1, &s->ring_fence[slot],
                                            VK_TRUE, FSR_FENCE_TIMEOUT_NS);
        if (wr != VK_SUCCESS)
            fsr_loge("FSR: fence wait before semaphore recreate failed %d", (int)wr);
        else
            s->ring_pending[slot] = 0;
    }
    if (s->ring_sem[slot] != VK_NULL_HANDLE)
        d->fn.vkDestroySemaphore(d->device, s->ring_sem[slot], NULL);
    s->ring_sem[slot] = VK_NULL_HANDLE;

    VkSemaphoreCreateInfo sci;
    memset(&sci, 0, sizeof(sci));
    sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    if (d->fn.vkCreateSemaphore(d->device, &sci, NULL, &s->ring_sem[slot]) != VK_SUCCESS) {
        s->ring_sem[slot] = VK_NULL_HANDLE;
        fsr_loge("FSR: semaphore recreate failed slot %u — slot drains from now on", slot);
    }
}

static void fsr_teardown_fsr_gpu(FsrDeviceState *d, FsrSwapchainState *s) {
    if (!s->gpu_ready) return;
    fsr_destroy_image(d, &s->input_img);
    fsr_destroy_image(d, &s->work_img);
    fsr_destroy_image(d, &s->final_img);
    if (s->easu_ubo) d->fn.vkDestroyBuffer(d->device, s->easu_ubo, NULL);
    if (s->easu_ubo_mem) d->fn.vkFreeMemory(d->device, s->easu_ubo_mem, NULL);
    if (s->rcas_ubo) d->fn.vkDestroyBuffer(d->device, s->rcas_ubo, NULL);
    if (s->rcas_ubo_mem) d->fn.vkFreeMemory(d->device, s->rcas_ubo_mem, NULL);
    if (s->sampler) d->fn.vkDestroySampler(d->device, s->sampler, NULL);
    if (s->easu_dsl) d->fn.vkDestroyDescriptorSetLayout(d->device, s->easu_dsl, NULL);
    if (s->rcas_dsl) d->fn.vkDestroyDescriptorSetLayout(d->device, s->rcas_dsl, NULL);
    if (s->desc_pool) d->fn.vkDestroyDescriptorPool(d->device, s->desc_pool, NULL);
    if (s->easu_pl) d->fn.vkDestroyPipelineLayout(d->device, s->easu_pl, NULL);
    if (s->rcas_pl) d->fn.vkDestroyPipelineLayout(d->device, s->rcas_pl, NULL);
    if (s->easu_pipe) d->fn.vkDestroyPipeline(d->device, s->easu_pipe, NULL);
    if (s->rcas_pipe) d->fn.vkDestroyPipeline(d->device, s->rcas_pipe, NULL);
    s->gpu_ready = 0;
    s->in_w = 0;
    s->in_h = 0;
}

static void fsr_teardown_fg_gpu(FsrDeviceState *d, FsrSwapchainState *s) {
    if (!s->fg_gpu_ready) return;
    fsr_destroy_image(d, &s->fg_history_img);
    fsr_destroy_image(d, &s->fg_backup_img);
    fsr_destroy_image(d, &s->fg_scratch_img);
    if (s->fg_ubo) d->fn.vkDestroyBuffer(d->device, s->fg_ubo, NULL);
    if (s->fg_ubo_mem) d->fn.vkFreeMemory(d->device, s->fg_ubo_mem, NULL);
    if (s->fg_dsl) d->fn.vkDestroyDescriptorSetLayout(d->device, s->fg_dsl, NULL);
    if (s->fg_desc_pool) d->fn.vkDestroyDescriptorPool(d->device, s->fg_desc_pool, NULL);
    if (s->fg_pl) d->fn.vkDestroyPipelineLayout(d->device, s->fg_pl, NULL);
    if (s->fg_pipe) d->fn.vkDestroyPipeline(d->device, s->fg_pipe, NULL);
    if (s->fg_flow_pipe) d->fn.vkDestroyPipeline(d->device, s->fg_flow_pipe, NULL);
    s->fg_pl = NULL;
    s->fg_pipe = NULL;
    s->fg_flow_pipe = NULL;
    s->fg_gpu_ready = 0;
    s->fg_history_ready = 0;
}

static void fsr_teardown_rs(FsrDeviceState *d, FsrSwapchainState *s) {
    if (s->app_images) {
        for (uint32_t i = 0; i < s->app_image_count; i++)
            fsr_destroy_image(d, &s->app_images[i]);
        free(s->app_images);
        s->app_images = NULL;
    }
    s->app_image_count = 0;
    s->rs_w = 0;
    s->rs_h = 0;
    s->rs_active = 0;
    s->rs_handed = 0;
}

static void fsr_teardown_swapchain_gpu(FsrDeviceState *d, FsrSwapchainState *s) {
    /* Unconditional, unlike the two helpers above: the ring is owned here and is
     * freed even when neither gpu_ready nor fg_gpu_ready was ever reached, so a
     * failed setup does not leak the pool, the fences or the semaphores. */
    fsr_teardown_fsr_gpu(d, s);
    fsr_teardown_fg_gpu(d, s);
    fsr_teardown_rs(d, s);
    fsr_ring_free(d, s);
}

/**
 * Drop all state for a device that is already gone, touching only host memory.
 *
 * PCSX2 destroys its VkDevice every time you leave and re-enter a game, so by the
 * time a new swapchain arrives the old device is dead. Running fsr_unbind_device()
 * on it called vkDeviceWaitIdle + the destroy entry points into a freed driver
 * device and crashed inside Turnip:
 *   signal 11 (SIGSEGV), fault addr 0xffffffffffffcc
 *   #00 libvulkan_freedreno_a8xx-turnip-gen8-V31.so
 *   #01 libvulkad.so (fsr_bind_device+76)
 * Nothing needs releasing on the GPU side — the images, views, pools and fences
 * died with the device.
 */
static void fsr_abandon_device(void) {
    if (!g_dev) return;
    fsr_rcas_log_stopped("device gone");
    for (uint32_t i = 0; i < g_dev->swapchain_count; i++) {
        FsrSwapchainState *s = &g_dev->swapchains[i];
        free(s->images);
        /* Host side of the command ring only. The command buffers, fences and
         * semaphores themselves died with the device — calling vkDestroy* on them
         * is exactly the crash this function exists to avoid. */
        free(s->ring_cmd);
        free(s->ring_fence);
        free(s->ring_sem);
        free(s->ring_pending);
        free(s->app_images);
    }
    free(g_dev->swapchains);
    free(g_dev);
    g_dev = NULL;
}

/* vkGetPhysicalDeviceMemoryProperties is an INSTANCE-level entry point, so the
 * device-level get_proc handed to fsr_bind_device can never resolve it. The shim
 * supplies both it and the physical device through fsr_set_physical_device();
 * without them fsr_find_memory_type() fell through to a fallback that returns
 * index 0 for HOST_VISIBLE|HOST_COHERENT, which on Adreno is device-local — so
 * vkMapMemory failed and every FSR/FG uniform buffer kept whatever was already
 * in it. d->physical_device was declared and read but never assigned. */
static VkPhysicalDevice g_phys_device = VK_NULL_HANDLE;
static PFN_vkGetPhysicalDeviceMemoryProperties g_get_mem_props = NULL;

void fsr_set_physical_device(void *phys, void *get_mem_props_fn) {
    g_phys_device = (VkPhysicalDevice)phys;
    g_get_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)get_mem_props_fn;
    if (g_dev) {
        g_dev->physical_device = g_phys_device;
        g_dev->fn.vkGetPhysicalDeviceMemoryProperties = g_get_mem_props;
    }
    fsr_logi("FSR: physical device %p, memory props fn %p",
             (void *)g_phys_device, (void *)g_get_mem_props);
}

int fsr_bind_device(VkDevice device, PFN_fsr_vk_get_device_proc get_proc) {
    if (!device || !get_proc) return 0;
    if (g_dev && g_dev->device == device) return 1;
    if (g_dev) fsr_abandon_device();

    g_dev = (FsrDeviceState *)calloc(1, sizeof(FsrDeviceState));
    g_dev->device = device;
    g_dev->queue_family = 0;
    g_dev->physical_device = g_phys_device;
    if (!fsr_load_fns(device, get_proc, &g_dev->fn)) {
        free(g_dev);
        g_dev = NULL;
        fsr_loge("FSR: failed to load Vulkan device functions");
        return 0;
    }
    /* Instance-level proc: fsr_load_fns cannot resolve it, so restore it here. */
    g_dev->fn.vkGetPhysicalDeviceMemoryProperties = g_get_mem_props;
    if (!g_get_mem_props || !g_phys_device)
        fsr_loge("FSR: no memory properties query — uniform buffers cannot be mapped");
    g_dev->initialized = 1;
    fsr_logi("FSR: device bound");
    return 1;
}

void fsr_unbind_device(VkDevice device) {
    if (!g_dev || g_dev->device != device) return;
    if (g_dev->fn.vkDeviceWaitIdle)
        g_dev->fn.vkDeviceWaitIdle(device);
    for (uint32_t i = 0; i < g_dev->swapchain_count; i++)
        fsr_on_swapchain_destroyed(device, g_dev->swapchains[i].swapchain);
    free(g_dev);
    g_dev = NULL;
}

/* Set while FSR queries the swapchain for its OWN bookkeeping. fsr_load_fns
 * resolves vkGetSwapchainImagesKHR through the shim's vkGetDeviceProcAddr, so
 * g_dev->fn.vkGetSwapchainImagesKHR IS the substituting hook. Without this
 * bypass, fsr_on_swapchain_created() filled s->images with the reduced-size app
 * images, so the pass wrote into one of those and then presented a real panel
 * image nothing had touched -- SIGSEGV inside Turnip's QueuePresentKHR at
 * fault addr 0x48. s->images must always be the REAL swapchain images. */
static int g_rs_internal_query = 0;

void fsr_on_swapchain_created(VkDevice device, VkSwapchainKHR swapchain,
                              uint32_t width, uint32_t height, uint32_t format) {
    if (!g_dev || g_dev->device != device) return;
    if (fsr_find_swapchain(g_dev, swapchain)) return;

    FsrSwapchainState *sc = (FsrSwapchainState *)realloc(
        g_dev->swapchains, (g_dev->swapchain_count + 1) * sizeof(FsrSwapchainState));
    if (!sc) return;
    g_dev->swapchains = sc;
    FsrSwapchainState *s = &g_dev->swapchains[g_dev->swapchain_count++];
    memset(s, 0, sizeof(*s));
    s->swapchain = swapchain;
    s->out_w = width;
    s->out_h = height;
    s->format = format ? format : VK_FORMAT_R8G8B8A8_UNORM;

    /* Bypass the render-scale substitution: these must be the driver's own
     * panel-sized images, because they are what the pass writes into and what
     * the driver presents. */
    uint32_t count = 0;
    g_rs_internal_query = 1;
    g_dev->fn.vkGetSwapchainImagesKHR(device, swapchain, &count, NULL);
    s->images = (VkImage *)calloc(count, sizeof(VkImage));
    s->image_count = count;
    g_dev->fn.vkGetSwapchainImagesKHR(device, swapchain, &count, s->images);
    g_rs_internal_query = 0;

    fsr_logi("FSR: swapchain %ux%u format=%u images=%u (real driver images)",
             width, height, s->format, count);
}

int fsr_query_swapchain(VkDevice device, VkSwapchainKHR swapchain, FsrSwapchainInfo *out) {
    if (!out || !g_dev || g_dev->device != device) return 0;
    FsrSwapchainState *s = fsr_find_swapchain(g_dev, swapchain);
    if (!s || !s->images) return 0;
    out->images = (void *const *)s->images;
    out->image_count = s->image_count;
    out->width = s->out_w;
    out->height = s->out_h;
    out->format = s->format;
    return 1;
}

uint32_t fsr_swapchain_present_count(VkDevice device, VkSwapchainKHR swapchain) {
    if (!g_dev || g_dev->device != device) return 0;
    FsrSwapchainState *s = fsr_find_swapchain(g_dev, swapchain);
    return s ? s->present_count : 0;
}

void fsr_bump_present_count(VkDevice device, VkSwapchainKHR swapchain) {
    if (!g_dev || g_dev->device != device) return;
    FsrSwapchainState *s = fsr_find_swapchain(g_dev, swapchain);
    if (s) s->present_count++;
}

void fsr_on_swapchain_destroyed(VkDevice device, VkSwapchainKHR swapchain) {
    if (!g_dev || g_dev->device != device) return;
    FsrSwapchainState *s = fsr_find_swapchain(g_dev, swapchain);
    if (!s) return;

    fsr_rcas_log_stopped("swapchain destroyed");

    FsrDeviceState *d = g_dev;
    if (d->fn.vkDeviceWaitIdle)
        d->fn.vkDeviceWaitIdle(device);
    fsr_teardown_swapchain_gpu(d, s);
    free(s->images);

    uint32_t idx = (uint32_t)(s - g_dev->swapchains);
    memmove(&g_dev->swapchains[idx], &g_dev->swapchains[idx + 1],
            (g_dev->swapchain_count - idx - 1) * sizeof(FsrSwapchainState));
    g_dev->swapchain_count--;
    if (g_dev->swapchain_count == 0) {
        free(g_dev->swapchains);
        g_dev->swapchains = NULL;
    }
}

static uint32_t fsr_align_up(uint32_t v, uint32_t a) {
    return (v + a - 1u) / a * a;
}

static int fsr_ensure_gpu(FsrDeviceState *d, FsrSwapchainState *s,
                          uint32_t in_w, uint32_t in_h) {
    if (s->gpu_ready && s->in_w == in_w && s->in_h == in_h)
        return 1;

    if (s->gpu_ready) {
        /* Submits no longer host-drain, so a recorded command buffer may still be
         * in flight referencing input_img/work_img/final_img. Retire the ring
         * before destroying them. Only reached on an actual size change, never in
         * the steady state. */
        fsr_ring_wait_all(d, s);
        fsr_teardown_fsr_gpu(d, s);
    }

    s->in_w = in_w;
    s->in_h = in_h;

    const VkFormat fmt_in  = VK_FORMAT_R8G8B8A8_UNORM;
    const VkFormat fmt_out = VK_FORMAT_R16G16B16A16_SFLOAT;
    const VkImageUsageFlags u_in  = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    /* TRANSFER_DST on the rgba16f pair is what lets the sharpen-only path blit the
     * finished 8-bit panel frame into work_img (see fsr_record_sharpen_pass).
     * fsr_rcas.comp declares both of its images rgba16f, so RCAS's *source* has to
     * be an rgba16f image — input_img is R8G8B8A8_UNORM and can never serve as one. */
    const VkImageUsageFlags u_out = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                    VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    if (!fsr_create_image(d, &s->input_img, in_w, in_h, fmt_in, u_in) ||
        !fsr_create_image(d, &s->work_img, s->out_w, s->out_h, fmt_out, u_out) ||
        !fsr_create_image(d, &s->final_img, s->out_w, s->out_h, fmt_out, u_out))
        return 0;

    VkSamplerCreateInfo sci;
    memset(&sci, 0, sizeof(sci));
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.magFilter = VK_FILTER_LINEAR;
    sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (d->fn.vkCreateSampler(d->device, &sci, NULL, &s->sampler) != VK_SUCCESS)
        return 0;

    VkDescriptorSetLayoutBinding binds[3];
    memset(binds, 0, sizeof(binds));
    binds[0].binding = 0;
    binds[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binds[0].descriptorCount = 1;
    binds[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    binds[1].binding = 1;
    binds[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    binds[1].descriptorCount = 1;
    binds[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    binds[2].binding = 2;
    binds[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    binds[2].descriptorCount = 1;
    binds[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo dslci;
    memset(&dslci, 0, sizeof(dslci));
    dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslci.bindingCount = 3;
    dslci.pBindings = binds;
    if (d->fn.vkCreateDescriptorSetLayout(d->device, &dslci, NULL, &s->easu_dsl) != VK_SUCCESS)
        return 0;

    VkDescriptorSetLayoutBinding rb[3];
    memset(rb, 0, sizeof(rb));
    rb[0].binding = 0;
    rb[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rb[0].descriptorCount = 1;
    rb[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    rb[1].binding = 1;
    rb[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rb[1].descriptorCount = 1;
    rb[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    rb[2].binding = 2;
    rb[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    rb[2].descriptorCount = 1;
    rb[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    dslci.pBindings = rb;
    if (d->fn.vkCreateDescriptorSetLayout(d->device, &dslci, NULL, &s->rcas_dsl) != VK_SUCCESS)
        return 0;

    /* RCAS is the one pipeline the sharpen path needs, so it is the only fatal
     * one. EASU belongs to the (disabled) upscale path; letting its failure take
     * the whole setup down would mean sharpening never runs because of a pipeline
     * it never binds. fsr_record_pass re-checks easu_pipe before using it. */
    if (!fsr_create_compute_pipe(d, fsr_rcas_spv, fsr_rcas_spv_size, s->rcas_dsl, &s->rcas_pl, &s->rcas_pipe)) {
        fsr_loge("FSR: RCAS pipeline creation failed");
        return 0;
    }
    if (!fsr_create_compute_pipe(d, fsr_easu_spv, fsr_easu_spv_size, s->easu_dsl, &s->easu_pl, &s->easu_pipe))
        fsr_loge("FSR: EASU pipeline creation failed — render-scale will blit");

    /* EASU: sampler + storage + UBO; RCAS: 2× storage + UBO.
     * Missing UNIFORM_BUFFER made Turnip allocate OK then SIGSEGV on first dispatch. */
    VkDescriptorPoolSize ps[3] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 2},
    };
    VkDescriptorPoolCreateInfo dpci;
    memset(&dpci, 0, sizeof(dpci));
    dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpci.maxSets = 2;
    dpci.poolSizeCount = 3;
    dpci.pPoolSizes = ps;
    if (d->fn.vkCreateDescriptorPool(d->device, &dpci, NULL, &s->desc_pool) != VK_SUCCESS)
        return 0;

    VkDescriptorSetAllocateInfo dsai;
    memset(&dsai, 0, sizeof(dsai));
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = s->desc_pool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &s->easu_dsl;
    if (d->fn.vkAllocateDescriptorSets(d->device, &dsai, &s->easu_ds) != VK_SUCCESS)
        return 0;
    dsai.pSetLayouts = &s->rcas_dsl;
    if (d->fn.vkAllocateDescriptorSets(d->device, &dsai, &s->rcas_ds) != VK_SUCCESS)
        return 0;

    auto make_ubo = [&](VkBuffer *buf, VkDeviceMemory *mem, size_t sz) {
        VkBufferCreateInfo bci;
        memset(&bci, 0, sizeof(bci));
        bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bci.size = sz;
        bci.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        if (d->fn.vkCreateBuffer(d->device, &bci, NULL, buf) != VK_SUCCESS) return 0;
        VkMemoryRequirements req;
        d->fn.vkGetBufferMemoryRequirements(d->device, *buf, &req);
        VkMemoryAllocateInfo mai;
        memset(&mai, 0, sizeof(mai));
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = fsr_find_memory_type(d, req.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (d->fn.vkAllocateMemory(d->device, &mai, NULL, mem) != VK_SUCCESS) return 0;
        d->fn.vkBindBufferMemory(d->device, *buf, *mem, 0);
        return 1;
    };
    if (!make_ubo(&s->easu_ubo, &s->easu_ubo_mem, sizeof(FsrEasuUbo)) ||
        !make_ubo(&s->rcas_ubo, &s->rcas_ubo_mem, sizeof(FsrRcasUbo)))
        return 0;

    /* Command ring: one pool + one command buffer / fence / semaphore per
     * swapchain image index. Shared with the framegen path, which reaches the
     * same allocator through fg_ensure_gpu(). */
    if (!fsr_ensure_cmd_fence(d, s))
        return 0;

    s->gpu_ready = 1;
    fsr_logi("FSR: GPU resources %ux%u -> %ux%u", in_w, in_h, s->out_w, s->out_h);
    return 1;
}

static void fsr_compute_input_size(FsrSwapchainState *s, uint32_t *in_w, uint32_t *in_h) {
    uint32_t cap_w = s->out_w;
    uint32_t cap_h = s->out_h;
    if (g_quality == FSR_QUALITY_4X) {
        cap_w = (uint32_t)(s->out_w * 0.25f);
        cap_h = (uint32_t)(s->out_h * 0.25f);
        if (cap_w < 16) cap_w = 16;
        if (cap_h < 16) cap_h = 16;
    }
    if (g_viewport_w > 8.0f && g_viewport_h > 8.0f &&
        (float)g_viewport_w / (float)s->out_w < 0.95f) {
        *in_w = (uint32_t)(g_viewport_w + 0.5f);
        *in_h = (uint32_t)(g_viewport_h + 0.5f);
        if (g_quality == FSR_QUALITY_4X) {
            if (*in_w > cap_w) *in_w = cap_w;
            if (*in_h > cap_h) *in_h = cap_h;
        }
        return;
    }
    float scale = quality_scale(g_quality);
    *in_w = (uint32_t)(s->out_w * scale);
    *in_h = (uint32_t)(s->out_h * scale);
    if (*in_w < 16) *in_w = 16;
    if (*in_h < 16) *in_h = 16;
}

/* Gate for the EASU upscale path ONLY, which is disabled (see
 * fsr_easu_upscale_path_enabled). It is no longer what decides whether anything
 * runs at present time: the sharpen path has its own gate
 * (fsr_sharpen_extent_ok) and full-panel is explicitly not a reason to skip
 * there. Left in place because it belongs to the upscale machinery. */
static int fsr_should_run(FsrSwapchainState *s, uint32_t in_w, uint32_t in_h) {
    if (g_upscaler != FSR_UPSCALER_FSR1) return 0;
    if (in_w < 16 || in_h < 16) return 0;
    if (in_w >= s->out_w || in_h >= s->out_h) return 0;
    if (s->present_count < 2) return 0;
  if ((float)in_w / (float)s->out_w > 0.95f) return 0;
    /* Full-panel viewport means PCSX2 already upscaled — skip double-upscale. */
    if (fsr_viewport_is_ready() &&
        (float)g_viewport_w / (float)s->out_w > 0.95f)
        return 0;
    return 1;
}

static void fsr_upload_ubo(FsrDeviceState *d, VkDeviceMemory mem, const void *data, size_t sz) {
    static int fail_logged = 0;
    if (d->fn.vkMapMemory) {
        void *mapped = NULL;
        VkResult r = d->fn.vkMapMemory(d->device, mem, 0, sz, 0, &mapped);
        if (r == VK_SUCCESS && mapped) {
            memcpy(mapped, data, sz);
            d->fn.vkUnmapMemory(d->device, mem);
            return;
        }
        /* Silence here was expensive: an unmappable UBO leaves the constants at
         * whatever the allocation happened to contain, so RCAS runs with a
         * meaningless sharpness and the pass looks like it did nothing. */
        if (!fail_logged) {
            fail_logged = 1;
            fsr_loge("FSR: vkMapMemory failed (%d) — shader constants not uploaded", (int)r);
        }
        return;
    }
    if (!fail_logged) {
        fail_logged = 1;
        fsr_loge("FSR: no vkMapMemory — shader constants not uploaded");
    }
}

static int fsr_record_pass(FsrDeviceState *d, FsrSwapchainState *s, VkImage swapchain_image,
                           uint32_t in_w, uint32_t in_h) {
    if (!fsr_ensure_gpu(d, s, in_w, in_h))
        return 0;
    if (!s->easu_pipe)
        return 0;

    FsrEasuUbo easu = {};
    fsr_cpu_populate_easu((uint32_t (*)[4])easu.con0, (float)in_w, (float)in_h,
                          (float)s->out_w, (float)s->out_h);
    easu.out_size[0] = s->out_w;
    easu.out_size[1] = s->out_h;

    FsrRcasUbo rcas = {};
    fsr_cpu_populate_rcas(rcas.con, g_sharpness);
    rcas.out_size[0] = s->out_w;
    rcas.out_size[1] = s->out_h;

    fsr_upload_ubo(d, s->easu_ubo_mem, &easu, sizeof(easu));
    fsr_upload_ubo(d, s->rcas_ubo_mem, &rcas, sizeof(rcas));

    VkCommandBuffer cmd = s->cmd_buf;
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    d->fn.vkBeginCommandBuffer(cmd, &bi);

    /* The image is in PRESENT_SRC_KHR here, not COLOR_ATTACHMENT_OPTIMAL. We are
     * inside vkQueuePresentKHR: vkQueuePresentKHR *requires* every presented image
     * to already be in VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, so whatever PCSX2 did
     * earlier in the frame, its last recorded transition on this image must have
     * been to PRESENT_SRC_KHR. Naming the wrong oldLayout makes the contents
     * undefined per spec, which is exactly how a "working" pass ends up showing
     * garbage or nothing. This matches what the framegen path already assumes. */
    fsr_barrier(d, cmd, swapchain_image,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    fsr_barrier(d, cmd, s->input_img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy2 region;
    memset(&region, 0, sizeof(region));
    region.sType = VK_STRUCTURE_TYPE_IMAGE_COPY_2;
    region.srcSubresource = fsr_color_layers();
    region.dstSubresource = region.srcSubresource;
    region.srcOffset.x = 0;
    region.srcOffset.y = 0;
    if (g_viewport_ready && g_viewport_w > 8.0f && g_viewport_h > 8.0f &&
        (g_quality != FSR_QUALITY_4X ||
         (float)g_viewport_w / (float)s->out_w < 0.95f)) {
        region.srcOffset.x = (int32_t)(g_viewport_x + 0.5f);
        region.srcOffset.y = (int32_t)(g_viewport_y + 0.5f);
    }
    region.extent.width = in_w;
    region.extent.height = in_h;
    region.extent.depth = 1;

    VkCopyImageInfo2 copy;
    memset(&copy, 0, sizeof(copy));
    copy.sType = VK_STRUCTURE_TYPE_COPY_IMAGE_INFO_2;
    copy.srcImage = swapchain_image;
    copy.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    copy.dstImage = s->input_img.image;
    copy.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    copy.regionCount = 1;
    copy.pRegions = &region;
    fsr_cmd_copy_image2(&d->fn, cmd, &copy);

    fsr_barrier(d, cmd, s->input_img.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    fsr_barrier(d, cmd, s->work_img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                0, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    /* Descriptor updates */
    VkDescriptorImageInfo samp_info = {s->sampler, s->input_img.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo work_w = {NULL, s->work_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo easu_buf = {s->easu_ubo, 0, sizeof(FsrEasuUbo)};

    VkWriteDescriptorSet writes[3];
    memset(writes, 0, sizeof(writes));
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = s->easu_ds;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &samp_info;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = s->easu_ds;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &work_w;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = s->easu_ds;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[2].pBufferInfo = &easu_buf;
    d->fn.vkUpdateDescriptorSets(d->device, 3, writes, 0, NULL);

    d->fn.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->easu_pipe);
    d->fn.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->easu_pl,
                                  0, 1, &s->easu_ds, 0, NULL);
    d->fn.vkCmdDispatch(cmd, fsr_align_up(s->out_w, 8) / 8,
                        fsr_align_up(s->out_h, 8) / 8, 1);

    /* EASU writes work_img, RCAS then reads it. Descriptor updates are host-side
     * and order nothing on the GPU, so without this the second dispatch is a
     * read-after-write hazard on the same image. Layout stays GENERAL. */
    fsr_barrier(d, cmd, s->work_img.image,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkDescriptorImageInfo work_r = {NULL, s->work_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo final_w = {NULL, s->final_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo rcas_buf = {s->rcas_ubo, 0, sizeof(FsrRcasUbo)};
    VkWriteDescriptorSet rw[3];
    memset(rw, 0, sizeof(rw));
    rw[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[0].dstSet = s->rcas_ds;
    rw[0].descriptorCount = 1;
    rw[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rw[0].pImageInfo = &work_r;
    rw[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[1].dstSet = s->rcas_ds;
    rw[1].dstBinding = 1;
    rw[1].descriptorCount = 1;
    rw[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rw[1].pImageInfo = &final_w;
    rw[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[2].dstSet = s->rcas_ds;
    rw[2].dstBinding = 2;
    rw[2].descriptorCount = 1;
    rw[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    rw[2].pBufferInfo = &rcas_buf;
    d->fn.vkUpdateDescriptorSets(d->device, 3, rw, 0, NULL);

    d->fn.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->rcas_pipe);
    d->fn.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->rcas_pl,
                                  0, 1, &s->rcas_ds, 0, NULL);
    d->fn.vkCmdDispatch(cmd, fsr_align_up(s->out_w, 8) / 8,
                        fsr_align_up(s->out_h, 8) / 8, 1);

    /* Blit final -> swapchain */
    fsr_barrier(d, cmd, s->final_img.image,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    fsr_barrier(d, cmd, swapchain_image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit2 blit;
    memset(&blit, 0, sizeof(blit));
    blit.sType = VK_STRUCTURE_TYPE_IMAGE_BLIT_2;
    blit.srcSubresource = fsr_color_layers();
    blit.dstSubresource = fsr_color_layers();
    blit.srcOffsets[1].x = (int32_t)s->out_w;
    blit.srcOffsets[1].y = (int32_t)s->out_h;
    blit.srcOffsets[1].z = 1;
    blit.dstOffsets[1].x = (int32_t)s->out_w;
    blit.dstOffsets[1].y = (int32_t)s->out_h;
    blit.dstOffsets[1].z = 1;

    VkBlitImageInfo2 blit_info;
    memset(&blit_info, 0, sizeof(blit_info));
    blit_info.sType = VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2;
    blit_info.srcImage = s->final_img.image;
    blit_info.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    blit_info.dstImage = swapchain_image;
    blit_info.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    blit_info.regionCount = 1;
    blit_info.pRegions = &blit;
    blit_info.filter = VK_FILTER_LINEAR;
    fsr_cmd_blit_image2(&d->fn, cmd, &blit_info);

    fsr_barrier(d, cmd, swapchain_image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    d->fn.vkEndCommandBuffer(cmd);
    return 1;
}

/* ------------------------------------------------------------------ */
/* Sharpen-only path (RCAS at 1:1)                                     */
/*                                                                     */
/* PCSX2 hands vkQueuePresentKHR a finished, fully composited, panel-   */
/* sized frame. There is no smaller source image reachable from here, so */
/* EASU has nothing to upscale from and is not dispatched at all. RCAS   */
/* however is resolution-agnostic — neither fsr_rcas.comp nor FsrRcasCon */
/* encodes any input:output ratio (ffx_fsr1.h:662-672 packs sharpness    */
/* only) — so it runs correctly on the panel-sized frame in place.       */
/*                                                                      */
/* Chain: swapchain -> work_img (blit) -> RCAS -> final_img -> swapchain */
/*                                                                      */
/* work_img is reused as RCAS's source rather than input_img. input_img  */
/* is R8G8B8A8_UNORM with SAMPLED usage, and fsr_rcas.comp declares its   */
/* source `layout(rgba16f) readonly image2D`: a storage image descriptor  */
/* whose view format must match the shader's format qualifier. work_img   */
/* is already panel-sized R16G16B16A16_SFLOAT with STORAGE usage, so it   */
/* matches exactly, and nothing else writes it in this path (the EASU     */
/* dispatch that normally produces it never runs). Getting the 8-bit      */
/* swapchain frame into it therefore needs a *blit*, not a copy: blit     */
/* converts formats component-wise (so a BGRA swapchain stays correct),   */
/* while vkCmdCopyImage between different formats reinterprets bytes.     */
static int fsr_record_sharpen_pass(FsrDeviceState *d, FsrSwapchainState *s,
                                   VkImage swapchain_image) {
    FsrRcasUbo rcas = {};
    fsr_cpu_populate_rcas(rcas.con, g_sharpness);
    rcas.out_size[0] = s->out_w;
    rcas.out_size[1] = s->out_h;
    fsr_upload_ubo(d, s->rcas_ubo_mem, &rcas, sizeof(rcas));

    VkCommandBuffer cmd = s->cmd_buf;
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    d->fn.vkBeginCommandBuffer(cmd, &bi);

    /* Present-ready image -> transfer source. See the note in fsr_record_pass:
     * PRESENT_SRC_KHR is the only layout a presentable image can legally be in
     * at this point. */
    fsr_barrier(d, cmd, swapchain_image,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    /* UNDEFINED discards the old contents, which is what we want — the blit
     * below rewrites every pixel. */
    fsr_barrier(d, cmd, s->work_img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit2 in_blit;
    memset(&in_blit, 0, sizeof(in_blit));
    in_blit.sType = VK_STRUCTURE_TYPE_IMAGE_BLIT_2;
    in_blit.srcSubresource = fsr_color_layers();
    in_blit.dstSubresource = fsr_color_layers();
    in_blit.srcOffsets[1].x = (int32_t)s->out_w;
    in_blit.srcOffsets[1].y = (int32_t)s->out_h;
    in_blit.srcOffsets[1].z = 1;
    in_blit.dstOffsets[1].x = (int32_t)s->out_w;
    in_blit.dstOffsets[1].y = (int32_t)s->out_h;
    in_blit.dstOffsets[1].z = 1;

    VkBlitImageInfo2 in_info;
    memset(&in_info, 0, sizeof(in_info));
    in_info.sType = VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2;
    in_info.srcImage = swapchain_image;
    in_info.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    in_info.dstImage = s->work_img.image;
    in_info.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    in_info.regionCount = 1;
    in_info.pRegions = &in_blit;
    /* Same extent both sides, so NEAREST is an exact 1:1 transfer with only the
     * 8-bit -> 16-bit float conversion applied. LINEAR would resample. */
    in_info.filter = VK_FILTER_NEAREST;
    fsr_cmd_blit_image2(&d->fn, cmd, &in_info);

    fsr_barrier(d, cmd, s->work_img.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    fsr_barrier(d, cmd, s->final_img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                0, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    /* Same descriptor set layout the upscale path uses for RCAS:
     * 0 = STORAGE_IMAGE (src), 1 = STORAGE_IMAGE (dst), 2 = UNIFORM_BUFFER. */
    VkDescriptorImageInfo src_r = {NULL, s->work_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo dst_w = {NULL, s->final_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo rcas_buf = {s->rcas_ubo, 0, sizeof(FsrRcasUbo)};
    VkWriteDescriptorSet rw[3];
    memset(rw, 0, sizeof(rw));
    rw[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[0].dstSet = s->rcas_ds;
    rw[0].descriptorCount = 1;
    rw[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rw[0].pImageInfo = &src_r;
    rw[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[1].dstSet = s->rcas_ds;
    rw[1].dstBinding = 1;
    rw[1].descriptorCount = 1;
    rw[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rw[1].pImageInfo = &dst_w;
    rw[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[2].dstSet = s->rcas_ds;
    rw[2].dstBinding = 2;
    rw[2].descriptorCount = 1;
    rw[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    rw[2].pBufferInfo = &rcas_buf;
    d->fn.vkUpdateDescriptorSets(d->device, 3, rw, 0, NULL);

    d->fn.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->rcas_pipe);
    d->fn.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->rcas_pl,
                                  0, 1, &s->rcas_ds, 0, NULL);
    d->fn.vkCmdDispatch(cmd, fsr_align_up(s->out_w, 8) / 8,
                        fsr_align_up(s->out_h, 8) / 8, 1);

    fsr_barrier(d, cmd, s->final_img.image,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    fsr_barrier(d, cmd, swapchain_image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit2 out_blit = in_blit;
    VkBlitImageInfo2 out_info;
    memset(&out_info, 0, sizeof(out_info));
    out_info.sType = VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2;
    out_info.srcImage = s->final_img.image;
    out_info.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    out_info.dstImage = swapchain_image;
    out_info.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    out_info.regionCount = 1;
    out_info.pRegions = &out_blit;
    out_info.filter = VK_FILTER_NEAREST;
    fsr_cmd_blit_image2(&d->fn, cmd, &out_info);

    /* Back to a presentable layout — the caller presents this image next. */
    fsr_barrier(d, cmd, swapchain_image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    d->fn.vkEndCommandBuffer(cmd);
    return 1;
}

/* A zero or absurd panel extent means the swapchain record is not trustworthy
 * (stale create-info read, torn-down surface); dispatching against it would size
 * images and dispatch grids from garbage. Deliberately NOT a viewport check:
 * the viewport hooks no longer gate this path at all. */
static int fsr_sharpen_extent_ok(const FsrSwapchainState *s) {
    return s->out_w >= 64 && s->out_h >= 64 &&
           s->out_w <= 16384 && s->out_h <= 16384;
}

/* fsr_ensure_cmd_fence() now lives with the rest of the command ring, above
 * fsr_teardown_fsr_gpu(). It allocates one command buffer / fence / semaphore
 * per swapchain image index instead of the single set this used to make. */

static VkFormat fg_intermediate_format(VkFormat swap_fmt) {
    /* Match the swapchain so vkCmdCopyImage is a raw texel copy — blitting
     * RGBA scratch into a BGRA (or UBWC) swapchain produced full-screen purple
     * on Turnip Adreno 740. */
    switch (swap_fmt) {
    case VK_FORMAT_R8G8B8A8_UNORM:
    case VK_FORMAT_R8G8B8A8_SRGB:
    case VK_FORMAT_B8G8R8A8_UNORM:
    case VK_FORMAT_B8G8R8A8_SRGB:
        return swap_fmt;
    default:
        return VK_FORMAT_R8G8B8A8_UNORM;
    }
}

static int fg_ensure_gpu(FsrDeviceState *d, FsrSwapchainState *s) {
#if !NETHER_FSR_FRAMEGEN_ACTIVE
    (void)d;
    (void)s;
    return 0;
#else
    const VkFormat want_fmt = fg_intermediate_format((VkFormat)s->format);
    if (s->fg_gpu_ready) {
        if (s->fg_history_img.format == want_fmt)
            return 1;
        fsr_teardown_fg_gpu(d, s);
    }

    if (!fsr_ensure_cmd_fence(d, s))
        return 0;

    const VkFormat fmt = want_fmt;
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                    VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (!fsr_create_image(d, &s->fg_history_img, s->out_w, s->out_h, fmt, usage) ||
        !fsr_create_image(d, &s->fg_backup_img, s->out_w, s->out_h, fmt, usage) ||
        !fsr_create_image(d, &s->fg_scratch_img, s->out_w, s->out_h, fmt, usage))
        return 0;

    VkDescriptorSetLayoutBinding binds[4];
    memset(binds, 0, sizeof(binds));
    for (int i = 0; i < 3; i++) {
        binds[i].binding = (uint32_t)i;
        binds[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        binds[i].descriptorCount = 1;
        binds[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    binds[3].binding = 3;
    binds[3].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    binds[3].descriptorCount = 1;
    binds[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslci;
    memset(&dslci, 0, sizeof(dslci));
    dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslci.bindingCount = 4;
    dslci.pBindings = binds;
    if (d->fn.vkCreateDescriptorSetLayout(d->device, &dslci, NULL, &s->fg_dsl) != VK_SUCCESS)
        return 0;

    VkPipelineLayoutCreateInfo plci;
    memset(&plci, 0, sizeof(plci));
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &s->fg_dsl;
    if (d->fn.vkCreatePipelineLayout(d->device, &plci, NULL, &s->fg_pl) != VK_SUCCESS)
        return 0;

    if (!fsr_create_compute_pipeline_only(d, fg_blend_spv, fg_blend_spv_size, s->fg_pl,
                                          &s->fg_pipe))
        return 0;
    if (!fsr_create_compute_pipeline_only(d, fg_flow_spv, fg_flow_spv_size, s->fg_pl,
                                          &s->fg_flow_pipe))
        return 0;

    VkDescriptorPoolSize ps[2] = {
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1},
    };
    VkDescriptorPoolCreateInfo dpci;
    memset(&dpci, 0, sizeof(dpci));
    dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpci.maxSets = 1;
    dpci.poolSizeCount = 2;
    dpci.pPoolSizes = ps;
    if (d->fn.vkCreateDescriptorPool(d->device, &dpci, NULL, &s->fg_desc_pool) != VK_SUCCESS)
        return 0;

    VkDescriptorSetAllocateInfo dsai;
    memset(&dsai, 0, sizeof(dsai));
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = s->fg_desc_pool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &s->fg_dsl;
    if (d->fn.vkAllocateDescriptorSets(d->device, &dsai, &s->fg_ds) != VK_SUCCESS)
        return 0;

    VkBufferCreateInfo bci;
    memset(&bci, 0, sizeof(bci));
    bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bci.size = sizeof(FgInterpUbo);
    bci.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    if (d->fn.vkCreateBuffer(d->device, &bci, NULL, &s->fg_ubo) != VK_SUCCESS)
        return 0;
    VkMemoryRequirements req;
    d->fn.vkGetBufferMemoryRequirements(d->device, s->fg_ubo, &req);
    VkMemoryAllocateInfo mai;
    memset(&mai, 0, sizeof(mai));
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = fsr_find_memory_type(d, req.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (d->fn.vkAllocateMemory(d->device, &mai, NULL, &s->fg_ubo_mem) != VK_SUCCESS)
        return 0;
    d->fn.vkBindBufferMemory(d->device, s->fg_ubo, s->fg_ubo_mem, 0);

    s->fg_gpu_ready = 1;
    fsr_logi("FG: GPU resources %ux%u swapchain_fmt=%u intermediate_fmt=%u mode=%d alpha=%.2f",
             s->out_w, s->out_h, s->format, (uint32_t)fmt, g_framegen_mode, g_framegen_alpha);
    return 1;
#endif
}

static void fg_record_copy_to_swapchain(FsrDeviceState *d, FsrSwapchainState *s,
                                        VkImage src, VkImageLayout src_layout, VkImage dst) {
    VkCommandBuffer cmd = s->cmd_buf;
    fsr_barrier(d, cmd, src, src_layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    fsr_barrier(d, cmd, dst, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy2 region;
    memset(&region, 0, sizeof(region));
    region.sType = VK_STRUCTURE_TYPE_IMAGE_COPY_2;
    region.srcSubresource = fsr_color_layers();
    region.dstSubresource = region.srcSubresource;
    region.extent.width = s->out_w;
    region.extent.height = s->out_h;
    region.extent.depth = 1;

    VkCopyImageInfo2 copy;
    memset(&copy, 0, sizeof(copy));
    copy.sType = VK_STRUCTURE_TYPE_COPY_IMAGE_INFO_2;
    copy.srcImage = src;
    copy.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    copy.dstImage = dst;
    copy.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    copy.regionCount = 1;
    copy.pRegions = &region;
    fsr_cmd_copy_image2(&d->fn, cmd, &copy);

    fsr_barrier(d, cmd, dst,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
}

static void fg_record_copy(FsrDeviceState *d, FsrSwapchainState *s,
                           VkImage src, VkImageLayout src_layout,
                           FsrImage *dst, VkImageLayout dst_layout) {
    VkCommandBuffer cmd = s->cmd_buf;
    fsr_barrier(d, cmd, src, src_layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    fsr_barrier(d, cmd, dst->image,
                dst_layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_UNDEFINED : dst_layout,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageCopy2 region;
    memset(&region, 0, sizeof(region));
    region.sType = VK_STRUCTURE_TYPE_IMAGE_COPY_2;
    region.srcSubresource = fsr_color_layers();
    region.dstSubresource = region.srcSubresource;
    region.extent.width = s->out_w;
    region.extent.height = s->out_h;
    region.extent.depth = 1;

    VkCopyImageInfo2 copy;
    memset(&copy, 0, sizeof(copy));
    copy.sType = VK_STRUCTURE_TYPE_COPY_IMAGE_INFO_2;
    copy.srcImage = src;
    copy.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    copy.dstImage = dst->image;
    copy.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    copy.regionCount = 1;
    copy.pRegions = &region;
    fsr_cmd_copy_image2(&d->fn, cmd, &copy);
}

static int fg_record_interpolate(FsrDeviceState *d, FsrSwapchainState *s,
                                 FsrImage *prev, FsrImage *curr,
                                 float alpha) {
    FgInterpUbo ubo = {};
    ubo.alpha = alpha;
    ubo.flow_scale = g_flow_scale;
    ubo.width = s->out_w;
    ubo.height = s->out_h;
    fsr_upload_ubo(d, s->fg_ubo_mem, &ubo, sizeof(ubo));

    VkCommandBuffer cmd = s->cmd_buf;
    fsr_barrier(d, cmd, s->fg_scratch_img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                0, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkDescriptorImageInfo imgs[3];
    memset(imgs, 0, sizeof(imgs));
    imgs[0].imageView = prev->view;
    imgs[0].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    imgs[1].imageView = curr->view;
    imgs[1].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    imgs[2].imageView = s->fg_scratch_img.view;
    imgs[2].imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkDescriptorBufferInfo buf = {s->fg_ubo, 0, sizeof(FgInterpUbo)};
    VkWriteDescriptorSet writes[4];
    memset(writes, 0, sizeof(writes));
    for (int i = 0; i < 3; i++) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = s->fg_ds;
        writes[i].dstBinding = (uint32_t)i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = &imgs[i];
    }
    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = s->fg_ds;
    writes[3].dstBinding = 3;
    writes[3].descriptorCount = 1;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[3].pBufferInfo = &buf;
    d->fn.vkUpdateDescriptorSets(d->device, 4, writes, 0, NULL);

    VkPipeline pipe = s->fg_pipe;
    if (g_framegen_mode == FG_MODE_FLOW)
        pipe = s->fg_flow_pipe;
    d->fn.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
    d->fn.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->fg_pl,
                                  0, 1, &s->fg_ds, 0, NULL);
    d->fn.vkCmdDispatch(cmd, fsr_align_up(s->out_w, 8) / 8,
                        fsr_align_up(s->out_h, 8) / 8, 1);
    return 1;
}

/* Set as soon as one of our own submits has waited on the present's wait
 * semaphores. Those are binary semaphores: each signal may be waited on exactly
 * once, so once we have consumed them the real vkQueuePresentKHR must NOT be
 * handed them again (the WSI turns them into another vkQueueSubmit wait, which
 * would block forever on a semaphore nothing will re-signal). Reset per present
 * in fsr_on_queue_present; present is called from PCSX2's GS thread only, the
 * same single-threaded assumption the rest of this file's globals already make. */
static int g_present_sems_consumed = 0;

/* Which slot's semaphore our async submit signalled this present, or -1 for
 * none. Kept past the present itself so the caller can tell whether a *failed*
 * present may have left that semaphore signalled. */
static int g_async_sem_slot = -1;

/* The single semaphore handle the rewritten present info's pWaitSemaphores
 * points at. File-static so the pointer cannot dangle; see g_pi_nosem below. */
static VkSemaphore g_pi_sem = VK_NULL_HANDLE;

/* Draining submit: host-waits for completion before returning. Still the only
 * submit the framegen path may use — see fg_run_interpolated_present(), which
 * submits and presents several times per call out of one slot, so each submit
 * must retire before the next re-records the same command buffer. */
static int fsr_submit_cmd(FsrDeviceState *d, FsrSwapchainState *s, VkQueue queue,
                            uint32_t wait_count, const VkSemaphore *wait_sems) {
    if (!d || !s || !queue || !s->cmd_buf || !s->fence) return 0;
    if (!d->fn.vkQueueSubmit || !d->fn.vkWaitForFences || !d->fn.vkResetFences) return 0;
    if (wait_count > 0 && !wait_sems)
        wait_count = 0;
    d->fn.vkResetFences(d->device, 1, &s->fence);
    VkSubmitInfo si;
    memset(&si, 0, sizeof(si));
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount = wait_count;
    si.pWaitSemaphores = wait_sems;
    static const uint32_t stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    si.pWaitDstStageMask = wait_count ? &stage : NULL;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &s->cmd_buf;
    VkResult sr = d->fn.vkQueueSubmit(queue, 1, &si, s->fence);
    if (sr != VK_SUCCESS) {
        fsr_loge("shim: vkQueueSubmit failed %d", (int)sr);
        return 0;
    }
    /* Only once the submit is actually queued has the wait been taken. */
    if (wait_count > 0)
        g_present_sems_consumed = 1;
    if (s->ring_pending && s->cur_slot < s->ring_count)
        s->ring_pending[s->cur_slot] = 1;
    /* VK_SUCCESS is 0, so testing the result for truth reported every successful
     * wait as a failure — which made the caller latch g_fsr_session_disabled. */
    VkResult wr = d->fn.vkWaitForFences(d->device, 1, &s->fence, VK_TRUE, FSR_FENCE_TIMEOUT_NS);
    if (wr != VK_SUCCESS) {
        fsr_loge("shim: vkWaitForFences failed %d", (int)wr);
        return 0;
    }
    /* Drained, so the slot is idle again and the next fsr_acquire_slot() on this
     * index has nothing to wait for. */
    if (s->ring_pending && s->cur_slot < s->ring_count)
        s->ring_pending[s->cur_slot] = 0;
    d->fn.vkResetCommandBuffer(s->cmd_buf, 0);
    return 1;
}

/* Non-draining submit: signals this slot's semaphore and returns immediately.
 * The present is then made to wait on that semaphore, which is what keeps our
 * writes to the swapchain image ordered before the scanout WITHOUT parking the
 * presenting thread on the GPU. The fence is still signalled — it is what
 * fsr_acquire_slot() waits on `image_count` presents from now. */
static int fsr_submit_cmd_async(FsrDeviceState *d, FsrSwapchainState *s, VkQueue queue,
                                uint32_t wait_count, const VkSemaphore *wait_sems) {
    if (!d || !s || !queue || !s->cmd_buf || !s->fence) return 0;
    if (!d->fn.vkQueueSubmit || !d->fn.vkResetFences) return 0;
    if (!s->ring_sem || !s->ring_pending || s->cur_slot >= s->ring_count) return 0;
    VkSemaphore sig = s->ring_sem[s->cur_slot];
    if (sig == VK_NULL_HANDLE) return 0;
    if (wait_count > 0 && !wait_sems)
        wait_count = 0;

    d->fn.vkResetFences(d->device, 1, &s->fence);
    VkSubmitInfo si;
    memset(&si, 0, sizeof(si));
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.waitSemaphoreCount = wait_count;
    si.pWaitSemaphores = wait_sems;
    static const uint32_t stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    si.pWaitDstStageMask = wait_count ? &stage : NULL;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &s->cmd_buf;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &sig;
    VkResult sr = d->fn.vkQueueSubmit(queue, 1, &si, s->fence);
    if (sr != VK_SUCCESS) {
        /* Nothing was queued, so nothing signalled and nothing consumed. */
        fsr_loge("shim: vkQueueSubmit (async) failed %d", (int)sr);
        return 0;
    }
    if (wait_count > 0)
        g_present_sems_consumed = 1;
    s->ring_pending[s->cur_slot] = 1;
    g_async_sem_slot = (int)s->cur_slot;
    g_pi_sem = sig;
    return 1;
}

/* Submit the recorded work. allow_async picks GPU-side ordering over the host
 * drain; it degrades to the drain when the slot has no usable semaphore, so a
 * driver without vkCreateSemaphore behaves exactly as before. */
static int fsr_submit_recorded(FsrDeviceState *d, FsrSwapchainState *s, VkQueue queue,
                               uint32_t wait_count, const VkSemaphore *wait_sems,
                               int allow_async) {
    if (allow_async && d && s && fsr_async_ok(d, s))
        return fsr_submit_cmd_async(d, s, queue, wait_count, wait_sems);
    return fsr_submit_cmd(d, s, queue, wait_count, wait_sems);
}

/* A rewritten copy of this present's VkPresentInfoKHR. File-static rather than a
 * pointer to a caller stack frame so it cannot dangle; present is
 * single-threaded (PCSX2's GS thread). g_pi_sem (declared above, next to
 * g_async_sem_slot) holds the one semaphore handle pWaitSemaphores points at,
 * for the same lifetime reason. */
static VkPresentInfoKHR g_pi_nosem;
static int              g_pi_nosem_valid = 0;

/* Every present that happens *after* one of our submits has to go through here.
 * Handing the driver the original VkPresentInfoKHR would make the WSI wait a
 * second time on binary semaphores we already consumed — a hang, not a glitch.
 *
 * Two shapes, depending on how the submit was ordered:
 *
 * 1. Async submit (sharpen path). The present waits on OUR signal semaphore and
 *    nothing else. Ordering argument: our command buffer's last recorded
 *    operation on the swapchain image is the blit plus the transition back to
 *    PRESENT_SRC_KHR; the submit signals the semaphore only after every command
 *    in it has completed; the WSI's read of the image is placed after a wait on
 *    that semaphore. So swapchain writes -> semaphore signal -> present read is
 *    a single GPU-side chain, and the host never has to block to enforce it.
 *    PCSX2's own work stays ordered ahead of ours because our submit *waited* on
 *    the app's present-wait semaphores, so the chain is
 *    app render -> app sem -> our pass -> our sem -> present.
 *
 * 2. Draining submit (framegen path). fsr_submit_cmd() host-waits its fence, so
 *    our work — and transitively the app work we waited on — has completed before
 *    the present is issued, and the present needs no wait semaphore at all.
 *
 * If nothing consumed the semaphores, the original info is passed through
 * untouched so PCSX2's own render-to-present synchronisation is preserved. */
static VkResult fsr_present_after_submit(VkQueue queue, const void *present_info,
                                         VkResult (*real_present)(VkQueue, const void *)) {
    if (!g_pi_nosem_valid)
        return real_present(queue, present_info);

    if (g_async_sem_slot >= 0 && g_pi_sem != VK_NULL_HANDLE) {
        /* Case 1. Note this deliberately does NOT re-add the app's wait
         * semaphores: our submit already consumed them, and a binary semaphore
         * may only be waited once per signal. */
        g_pi_nosem.waitSemaphoreCount = 1;
        g_pi_nosem.pWaitSemaphores = &g_pi_sem;
        /* Our signal is consumed by this one present and must not be handed to a
         * second one. */
        g_pi_sem = VK_NULL_HANDLE;
        return real_present(queue, (const void *)&g_pi_nosem);
    }

    if (g_present_sems_consumed) {
        /* Case 2. */
        g_pi_nosem.waitSemaphoreCount = 0;
        g_pi_nosem.pWaitSemaphores = NULL;
        return real_present(queue, (const void *)&g_pi_nosem);
    }
    return real_present(queue, present_info);
}

static int fg_scratch_to_swapchain(FsrDeviceState *d, FsrSwapchainState *s,
                                   VkCommandBuffer cmd, VkImage target) {
    (void)cmd;
    /* Same-format copy — never blit. Blit from RGBA scratch to BGRA/UBWC swapchain
     * was the full-screen purple/magenta bug on Nova (format=37 panel path). */
    fg_record_copy_to_swapchain(d, s, s->fg_scratch_img.image,
                                VK_IMAGE_LAYOUT_GENERAL, target);
    return 1;
}

static int fg_is_hitch_frame(uint64_t now_ns) {
    if (g_last_present_ns == 0) {
        g_last_present_ns = now_ns;
        return 0;
    }
    double dt_ms = (double)(now_ns - g_last_present_ns) / 1000000.0;
    g_last_present_ns = now_ns;
    if (g_ft_count < 15)
        g_ft_ring[g_ft_count++] = dt_ms;
    else {
        memmove(g_ft_ring, g_ft_ring + 1, sizeof(double) * 14);
        g_ft_ring[14] = dt_ms;
    }
    if (g_ft_count < 4) return 0;
    double sum = 0.0;
    for (int i = 0; i < g_ft_count; i++) sum += g_ft_ring[i];
    double median = sum / (double)g_ft_count;
    return (dt_ms > median * 2.5 && dt_ms > 25.0) ? 1 : 0;
}

static int fg_present_synthetic(FsrDeviceState *d, FsrSwapchainState *s,
                                VkQueue queue, VkImage target,
                                uint32_t wait_count, const VkSemaphore *wait_sems,
                                float alpha,
                                VkResult (*real_present)(VkQueue, const void *),
                                const void *present_info) {
    VkCommandBuffer cmd = s->cmd_buf;
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    d->fn.vkBeginCommandBuffer(cmd, &bi);

    fg_record_interpolate(d, s, &s->fg_history_img, &s->fg_backup_img, alpha);
    fg_scratch_to_swapchain(d, s, cmd, target);
    d->fn.vkEndCommandBuffer(cmd);
    if (!fsr_submit_cmd(d, s, queue, wait_count, wait_sems))
        return 0;
    return fsr_present_after_submit(queue, present_info, real_present) == VK_SUCCESS;
}

static int fg_run_interpolated_present(FsrDeviceState *d, FsrSwapchainState *s,
                                       VkQueue queue, VkImage target,
                                       uint32_t wait_count, const VkSemaphore *wait_sems,
                                       VkResult (*real_present)(VkQueue, const void *),
                                       const void *present_info) {
    if (!fg_ensure_gpu(d, s))
        return 0;

    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now_ns = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
    if (fg_is_hitch_frame(now_ns)) {
        fsr_logi("FG: skipping synthetic frames (hitch detected)");
        return 0;
    }

    VkCommandBuffer cmd = s->cmd_buf;
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    d->fn.vkBeginCommandBuffer(cmd, &bi);

    fg_record_copy(d, s, target, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                   &s->fg_backup_img, VK_IMAGE_LAYOUT_UNDEFINED);
    fsr_barrier(d, cmd, s->fg_backup_img.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    fsr_barrier(d, cmd, s->fg_history_img.image,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    d->fn.vkEndCommandBuffer(cmd);
    if (!fsr_submit_cmd(d, s, queue, wait_count, wait_sems))
        return 0;

    static int fg_logged;
    if (!fg_logged) {
        fg_logged = 1;
#if NETHER_FSR_FRAMEGEN_ACTIVE
        const char *mode_name = "blend";
        if (g_framegen_mode == FG_MODE_FLOW) mode_name = "flow (block-matched)";
        else if (g_framegen_mode == FG_MODE_OPTICAL_FLOW_STUB) mode_name = "optical_flow_stub";
        fsr_logi("FG active: %s x%d flow=%.2f (paced, NOT ML frame interpolation)",
                 mode_name, g_fg_multiplier, g_flow_scale);
#endif
    }

    wait_count = 0;
    wait_sems = NULL;
    for (int phase = 1; phase < g_fg_multiplier; phase++) {
        float alpha = (float)phase / (float)g_fg_multiplier;
        if (!fg_present_synthetic(d, s, queue, target, 0, NULL, alpha,
                                  real_present, present_info))
            return 0;
    }

    d->fn.vkBeginCommandBuffer(cmd, &bi);
    fg_record_copy(d, s, s->fg_backup_img.image, VK_IMAGE_LAYOUT_GENERAL,
                   &s->fg_history_img, VK_IMAGE_LAYOUT_GENERAL);
    fg_record_copy_to_swapchain(d, s, s->fg_backup_img.image,
                                VK_IMAGE_LAYOUT_GENERAL, target);
    d->fn.vkEndCommandBuffer(cmd);
    if (!fsr_submit_cmd(d, s, queue, 0, NULL))
        return 0;

    s->fg_history_ready = 1;
    return 1;
}

static int fg_update_history(FsrDeviceState *d, FsrSwapchainState *s,
                             VkQueue queue, VkImage target,
                             uint32_t wait_count, const VkSemaphore *wait_sems) {
    if (!fg_ensure_gpu(d, s))
        return 0;

    VkCommandBuffer cmd = s->cmd_buf;
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    d->fn.vkBeginCommandBuffer(cmd, &bi);
    fg_record_copy(d, s, target, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                   &s->fg_history_img, VK_IMAGE_LAYOUT_UNDEFINED);
    d->fn.vkEndCommandBuffer(cmd);
    if (!fsr_submit_cmd(d, s, queue, wait_count, wait_sems))
        return 0;
    s->fg_history_ready = 1;
    return 1;
}

static VkResult finish_present(VkQueue queue, const void *present_info,
                               VkResult (*real_present)(VkQueue, const void *)) {
    return real_present(queue, present_info);
}

/* ------------------------------------------------------------------ */
/* Render-scale: app renders into FSR-owned images at panel*scale;     */
/* EASU+RCAS upscale into the real swapchain image at present time.    */
/* ------------------------------------------------------------------ */

void fsr_set_render_scale(float scale) {
    if (scale > 0.0f)
        g_rs_off_logged = 0;
    g_rs_scale = scale;
}

/*
 * DISABLED. Substituting vkGetSwapchainImagesKHR is unsound, not merely buggy.
 *
 * The application renders into images the presentation engine does not own, so at
 * present time the WSI and the compositor have to reconcile buffers that were
 * never part of the swapchain. Measured consequences on an Adreno 740 / Turnip
 * device: a null dereference inside Turnip's own QueuePresentKHR (SIGSEGV, fault
 * addr 0x48), and then a SIGSEGV in surfaceflinger itself — taking down the system
 * compositor, not just the app. Fixing s->images so it held the real driver images
 * (see g_rs_internal_query) removed one real bug and did not stop the crashing.
 *
 * Vulkan only permits presenting images the swapchain owns, so a present-time
 * layer cannot substitute a lower-resolution render target and then upscale it.
 * Reaching PCSX2's pre-display frame needs interception at the point it draws
 * (render pass + bound descriptor), not at swapchain acquisition.
 *
 * Left in place, refusing to arm, so a leftover render_scale= line in a deployed
 * turnip.conf cannot crash the device.
 */
int fsr_render_scale_wanted(void) {
    if (g_rs_scale > 0.0f)
        fsr_render_scale_report_off("disabled: swapchain image substitution "
                                    "crashes surfaceflinger");
    return 0;
}

void fsr_render_scale_report_off(const char *reason) {
    if (g_rs_off_logged) return;
    g_rs_off_logged = 1;
    fsr_logi("FSR: render-scale off (%s)", reason ? reason : "?");
}

int fsr_render_scale_extent(uint32_t panel_w, uint32_t panel_h,
                            uint32_t *out_w, uint32_t *out_h) {
    if (!out_w || !out_h) return 0;
    if (!fsr_render_scale_wanted()) {
        fsr_render_scale_report_off("upscaler is not fsr1 or scale unset");
        return 0;
    }
    uint32_t rw = ((uint32_t)(panel_w * g_rs_scale)) & ~1u;
    uint32_t rh = ((uint32_t)(panel_h * g_rs_scale)) & ~1u;
    if (rw < 64) rw = 64;
    if (rh < 64) rh = 64;
    if (rw >= panel_w || rh >= panel_h) {
        fsr_render_scale_report_off("reduced extent is not smaller than the panel");
        return 0;
    }
    *out_w = rw;
    *out_h = rh;
    return 1;
}

int fsr_render_scale_arm(VkDevice device, VkSwapchainKHR swapchain,
                         uint32_t rs_w, uint32_t rs_h) {
    if (!g_dev || g_dev->device != device) {
        fsr_render_scale_report_off("no bound device");
        return 0;
    }
    FsrSwapchainState *s = fsr_find_swapchain(g_dev, swapchain);
    if (!s || !s->images || s->image_count == 0) {
        fsr_render_scale_report_off("swapchain not tracked");
        return 0;
    }
    if (rs_w < 64 || rs_h < 64 || rs_w >= s->out_w || rs_h >= s->out_h) {
        fsr_render_scale_report_off("reduced extent unusable");
        return 0;
    }

    fsr_teardown_rs(g_dev, s);
    s->app_images = (FsrImage *)calloc(s->image_count, sizeof(FsrImage));
    if (!s->app_images) {
        fsr_render_scale_report_off("out of memory for app images");
        return 0;
    }
    s->app_image_count = s->image_count;
    const VkImageUsageFlags usage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    VkFormat fmt = (VkFormat)s->format;
    if (!fmt) fmt = VK_FORMAT_R8G8B8A8_UNORM;
    for (uint32_t i = 0; i < s->image_count; i++) {
        if (!fsr_create_image(g_dev, &s->app_images[i], rs_w, rs_h, fmt, usage)) {
            fsr_teardown_rs(g_dev, s);
            fsr_render_scale_report_off("app image creation failed");
            return 0;
        }
    }
    s->rs_w = rs_w;
    s->rs_h = rs_h;
    s->rs_active = 1;
    s->rs_handed = 0;
    fsr_logi("FSR: render-scale armed %ux%u app images (%u) → panel %ux%u",
             rs_w, rs_h, s->image_count, s->out_w, s->out_h);
    return 1;
}

/* Set while FSR queries the swapchain for its OWN bookkeeping. fsr_load_fns
 * resolves vkGetSwapchainImagesKHR through the shim's vkGetDeviceProcAddr, so
 * g_dev->fn.vkGetSwapchainImagesKHR IS the substituting hook — without this
 * bypass, fsr_on_swapchain_created() filled s->images with the reduced-size app
 * images, so the pass wrote into one of those and then presented a real panel
 * image nothing had touched. That crashed inside Turnip's QueuePresentKHR
 * (SIGSEGV, fault addr 0x48, null deref). s->images must always be the REAL
 * swapchain images; only the application ever sees the substituted set. */
int fsr_render_scale_query_images(VkDevice device, VkSwapchainKHR swapchain,
                                  uint32_t *pCount, void *pImages,
                                  VkResult *out_result) {
    if (g_rs_internal_query) return 0;
    if (!pCount || !out_result) return 0;
    if (!g_dev || g_dev->device != device) return 0;
    FsrSwapchainState *s = fsr_find_swapchain(g_dev, swapchain);
    if (!s || !s->rs_active || !s->app_images) return 0;
    uint32_t n = s->app_image_count;
    if (!pImages) {
        *pCount = n;
        *out_result = VK_SUCCESS;
        return 1;
    }
    if (*pCount < n) {
        *pCount = n;
        *out_result = VK_INCOMPLETE;
        return 1;
    }
    uint64_t *out = (uint64_t *)pImages;
    for (uint32_t i = 0; i < n; i++)
        out[i] = (uint64_t)(uintptr_t)s->app_images[i].image;
    *pCount = n;
    if (!s->rs_handed) {
        s->rs_handed = 1;
        fsr_logi("FSR: handed %u %ux%u images (app renders here, EASU upscales)",
                 n, s->rs_w, s->rs_h);
    }
    s->rs_handed = 1;
    *out_result = VK_SUCCESS;
    return 1;
}

int fsr_render_scale_any_active(void) {
    if (!g_dev) return 0;
    for (uint32_t i = 0; i < g_dev->swapchain_count; i++) {
        if (g_dev->swapchains[i].rs_handed)
            return 1;
    }
    return 0;
}

static void fsr_easu_log_active(const FsrSwapchainState *s) {
    if (g_easu_active_logged) return;
    g_easu_active_logged = 1;
    fsr_logi("FSR: EASU+RCAS active %ux%u -> %ux%u sharpness=%.2f",
             s->rs_w, s->rs_h, s->out_w, s->out_h, g_sharpness);
}

/* Blit the app's reduced image into the real swapchain (no EASU). Used when
 * render-scale is live but the upscaler is off or EASU failed — otherwise the
 * presented swapchain image is empty and the screen goes black. */
static int fsr_record_rs_blit(FsrDeviceState *d, FsrSwapchainState *s,
                              VkImage src, VkImage dst) {
    VkCommandBuffer cmd = s->cmd_buf;
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    d->fn.vkBeginCommandBuffer(cmd, &bi);

    fsr_barrier(d, cmd, src,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    fsr_barrier(d, cmd, dst,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit2 blit;
    memset(&blit, 0, sizeof(blit));
    blit.sType = VK_STRUCTURE_TYPE_IMAGE_BLIT_2;
    blit.srcSubresource = fsr_color_layers();
    blit.dstSubresource = fsr_color_layers();
    blit.srcOffsets[1].x = (int32_t)s->rs_w;
    blit.srcOffsets[1].y = (int32_t)s->rs_h;
    blit.srcOffsets[1].z = 1;
    blit.dstOffsets[1].x = (int32_t)s->out_w;
    blit.dstOffsets[1].y = (int32_t)s->out_h;
    blit.dstOffsets[1].z = 1;

    VkBlitImageInfo2 info;
    memset(&info, 0, sizeof(info));
    info.sType = VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2;
    info.srcImage = src;
    info.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    info.dstImage = dst;
    info.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    info.regionCount = 1;
    info.pRegions = &blit;
    info.filter = VK_FILTER_LINEAR;
    fsr_cmd_blit_image2(&d->fn, cmd, &info);

    fsr_barrier(d, cmd, src,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_READ_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    fsr_barrier(d, cmd, dst,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    d->fn.vkEndCommandBuffer(cmd);
    return 1;
}

/* EASU from the app's reduced image into the real panel-size swapchain image. */
static int fsr_record_render_scale_pass(FsrDeviceState *d, FsrSwapchainState *s,
                                        VkImage src, VkImage dst) {
    if (!fsr_ensure_gpu(d, s, s->rs_w, s->rs_h))
        return 0;
    if (!s->easu_pipe || !s->rcas_pipe)
        return 0;

    FsrEasuUbo easu = {};
    fsr_cpu_populate_easu((uint32_t (*)[4])easu.con0, (float)s->rs_w, (float)s->rs_h,
                          (float)s->out_w, (float)s->out_h);
    easu.out_size[0] = s->out_w;
    easu.out_size[1] = s->out_h;

    FsrRcasUbo rcas = {};
    fsr_cpu_populate_rcas(rcas.con, g_sharpness);
    rcas.out_size[0] = s->out_w;
    rcas.out_size[1] = s->out_h;

    fsr_upload_ubo(d, s->easu_ubo_mem, &easu, sizeof(easu));
    fsr_upload_ubo(d, s->rcas_ubo_mem, &rcas, sizeof(rcas));

    VkCommandBuffer cmd = s->cmd_buf;
    VkCommandBufferBeginInfo bi;
    memset(&bi, 0, sizeof(bi));
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    d->fn.vkBeginCommandBuffer(cmd, &bi);

    fsr_barrier(d, cmd, src,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    fsr_barrier(d, cmd, s->input_img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    /* Blit (not copy): app image format may not match input_img's RGBA8. */
    VkImageBlit2 in_blit;
    memset(&in_blit, 0, sizeof(in_blit));
    in_blit.sType = VK_STRUCTURE_TYPE_IMAGE_BLIT_2;
    in_blit.srcSubresource = fsr_color_layers();
    in_blit.dstSubresource = fsr_color_layers();
    in_blit.srcOffsets[1].x = (int32_t)s->rs_w;
    in_blit.srcOffsets[1].y = (int32_t)s->rs_h;
    in_blit.srcOffsets[1].z = 1;
    in_blit.dstOffsets[1].x = (int32_t)s->rs_w;
    in_blit.dstOffsets[1].y = (int32_t)s->rs_h;
    in_blit.dstOffsets[1].z = 1;
    VkBlitImageInfo2 in_info;
    memset(&in_info, 0, sizeof(in_info));
    in_info.sType = VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2;
    in_info.srcImage = src;
    in_info.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    in_info.dstImage = s->input_img.image;
    in_info.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    in_info.regionCount = 1;
    in_info.pRegions = &in_blit;
    in_info.filter = VK_FILTER_NEAREST;
    fsr_cmd_blit_image2(&d->fn, cmd, &in_info);

    fsr_barrier(d, cmd, src,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_READ_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    fsr_barrier(d, cmd, s->input_img.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    fsr_barrier(d, cmd, s->work_img.image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                0, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkDescriptorImageInfo samp_info = {s->sampler, s->input_img.view,
                                       VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo work_w = {NULL, s->work_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo easu_buf = {s->easu_ubo, 0, sizeof(FsrEasuUbo)};
    VkWriteDescriptorSet writes[3];
    memset(writes, 0, sizeof(writes));
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = s->easu_ds;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &samp_info;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = s->easu_ds;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &work_w;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = s->easu_ds;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[2].pBufferInfo = &easu_buf;
    d->fn.vkUpdateDescriptorSets(d->device, 3, writes, 0, NULL);

    d->fn.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->easu_pipe);
    d->fn.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->easu_pl,
                                  0, 1, &s->easu_ds, 0, NULL);
    d->fn.vkCmdDispatch(cmd, fsr_align_up(s->out_w, 8) / 8,
                        fsr_align_up(s->out_h, 8) / 8, 1);

    fsr_barrier(d, cmd, s->work_img.image,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    VkDescriptorImageInfo work_r = {NULL, s->work_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo final_w = {NULL, s->final_img.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorBufferInfo rcas_buf = {s->rcas_ubo, 0, sizeof(FsrRcasUbo)};
    VkWriteDescriptorSet rw[3];
    memset(rw, 0, sizeof(rw));
    rw[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[0].dstSet = s->rcas_ds;
    rw[0].descriptorCount = 1;
    rw[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rw[0].pImageInfo = &work_r;
    rw[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[1].dstSet = s->rcas_ds;
    rw[1].dstBinding = 1;
    rw[1].descriptorCount = 1;
    rw[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    rw[1].pImageInfo = &final_w;
    rw[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    rw[2].dstSet = s->rcas_ds;
    rw[2].dstBinding = 2;
    rw[2].descriptorCount = 1;
    rw[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    rw[2].pBufferInfo = &rcas_buf;
    d->fn.vkUpdateDescriptorSets(d->device, 3, rw, 0, NULL);

    d->fn.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->rcas_pipe);
    d->fn.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s->rcas_pl,
                                  0, 1, &s->rcas_ds, 0, NULL);
    d->fn.vkCmdDispatch(cmd, fsr_align_up(s->out_w, 8) / 8,
                        fsr_align_up(s->out_h, 8) / 8, 1);

    fsr_barrier(d, cmd, s->final_img.image,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    fsr_barrier(d, cmd, dst,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkImageBlit2 blit;
    memset(&blit, 0, sizeof(blit));
    blit.sType = VK_STRUCTURE_TYPE_IMAGE_BLIT_2;
    blit.srcSubresource = fsr_color_layers();
    blit.dstSubresource = fsr_color_layers();
    blit.srcOffsets[1].x = (int32_t)s->out_w;
    blit.srcOffsets[1].y = (int32_t)s->out_h;
    blit.srcOffsets[1].z = 1;
    blit.dstOffsets[1].x = (int32_t)s->out_w;
    blit.dstOffsets[1].y = (int32_t)s->out_h;
    blit.dstOffsets[1].z = 1;
    VkBlitImageInfo2 blit_info;
    memset(&blit_info, 0, sizeof(blit_info));
    blit_info.sType = VK_STRUCTURE_TYPE_BLIT_IMAGE_INFO_2;
    blit_info.srcImage = s->final_img.image;
    blit_info.srcImageLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    blit_info.dstImage = dst;
    blit_info.dstImageLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    blit_info.regionCount = 1;
    blit_info.pRegions = &blit;
    blit_info.filter = VK_FILTER_LINEAR;
    fsr_cmd_blit_image2(&d->fn, cmd, &blit_info);

    fsr_barrier(d, cmd, dst,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    d->fn.vkEndCommandBuffer(cmd);
    return 1;
}

/* Only a handful of presents, just enough to get past the very first swapchain
 * frames while PCSX2 is still settling. The old 24-present + viewport warmup was
 * one of the two reasons the pass never ran. */
#define FSR_WARMUP_PRESENTS 4
static uint32_t g_fsr_global_warmup = 0;
static int      g_fsr_warmup_log_stage = 0;
static int      g_fsr_session_disabled = 0;

static void fsr_rcas_log_active(const FsrSwapchainState *s) {
    if (g_rcas_active_logged) return;
    g_rcas_active_logged = 1;
    fsr_logi("FSR: RCAS active %ux%u sharpness=%.2f", s->out_w, s->out_h, g_sharpness);
}

VkResult fsr_on_queue_present(VkQueue queue, VkDevice device, const void *present_info,
                              VkResult (*real_present)(VkQueue, const void *)) {
    if (!real_present) return (VkResult)-1;
    if (!g_dev || g_dev->device != device)
        return finish_present(queue, present_info, real_present);
    const int rs_live = fsr_render_scale_any_active();
    /* Never blit/compute for shim FSR/render-scale (swapchain substitution is
     * unsound; TRANSFER on the GS image at 4× IR is a UBWC tax). Frame gen
     * copies real swapchain images when the user turned it on. */
    if (!rs_live && !g_framegen_enabled)
        return finish_present(queue, present_info, real_present);
    if (g_upscaler != FSR_UPSCALER_FSR1 && !rs_live)
        fsr_rcas_log_stopped("upscaler off");
    if (g_fsr_session_disabled && !rs_live) {
        fsr_rcas_log_stopped("session disabled");
        return finish_present(queue, present_info, real_present);
    }

    /* Fresh per present: nothing has consumed this present's wait semaphores yet,
     * and no submit of ours has signalled anything for it to wait on. */
    g_present_sems_consumed = 0;
    g_pi_nosem_valid = 0;
    g_async_sem_slot = -1;
    g_pi_sem = VK_NULL_HANDLE;

    /* Framegen keeps its own viewport requirement, unchanged. The sharpen path
     * deliberately does not consult the viewport at all — fsr_viewport_is_ready()
     * no longer gates it — so the two gates are now independent instead of one
     * early return that skipped both. */
    const int fg_ok = g_framegen_enabled;

    /* Render-scale cannot skip warmup: the real swapchain image is empty until
     * we copy the app's reduced image into it. */
    if (!rs_live && g_upscaler == FSR_UPSCALER_FSR1 &&
        g_fsr_global_warmup < FSR_WARMUP_PRESENTS) {
        g_fsr_global_warmup++;
        if (g_fsr_warmup_log_stage == 0) {
            fsr_logi("FSR warmup (%u presents)", FSR_WARMUP_PRESENTS);
            g_fsr_warmup_log_stage = 1;
        }
        if (!fg_ok)
            return finish_present(queue, present_info, real_present);
    }

    if (!fg_ok && g_upscaler != FSR_UPSCALER_FSR1 && !rs_live)
        return finish_present(queue, present_info, real_present);

    const VkPresentInfoKHR *pi = (const VkPresentInfoKHR *)present_info;
    if (!pi || pi->swapchainCount == 0)
        return finish_present(queue, present_info, real_present);
    if (!pi->pSwapchains || !pi->pImageIndices)
        return finish_present(queue, present_info, real_present);

    FsrSwapchainState *sc = fsr_find_swapchain(g_dev, pi->pSwapchains[0]);
    if (!sc || !sc->images)
        return finish_present(queue, present_info, real_present);
    if (pi->pImageIndices[0] >= sc->image_count)
        return finish_present(queue, present_info, real_present);

    sc->present_count++;

    const uint32_t image_index = pi->pImageIndices[0];
    VkImage target = sc->images[image_index];
    uint32_t wait_count = pi->waitSemaphoreCount;
    const VkSemaphore *wait_sems = pi->pWaitSemaphores;
    if (wait_count > 0 && !wait_sems)
        wait_count = 0;

    /* Claim this image index's slot before ANY recording happens: every
     * fsr_record_* / fg_record_* helper writes through sc->cmd_buf, which
     * fsr_acquire_slot() re-points at ring_cmd[image_index].
     *
     * If either step fails nothing has been recorded and nothing submitted, so
     * the untouched present is still correct — the frame just goes through
     * unsharpened rather than risking a re-record over pending work. */
    if (!fsr_ensure_cmd_fence(g_dev, sc) || !fsr_acquire_slot(g_dev, sc, image_index))
        return finish_present(queue, present_info, real_present);

    /* Framegen submits and presents several times out of this one slot per call,
     * so it must keep the host drain; only the sharpen/upscale submit may run
     * async. See the comment on fg_run_interpolated_present(). */
    const int allow_async = !fg_ok;

    /* Prepared before any submit, so every later present goes through
     * fsr_present_after_submit() with a rewritten copy ready to use. */
    g_pi_nosem = *pi;
    g_pi_nosem.waitSemaphoreCount = 0;
    g_pi_nosem.pWaitSemaphores = NULL;
    g_pi_nosem_valid = 1;

    int ran_fsr = 0;
    if (sc->rs_handed && sc->app_images && image_index < sc->app_image_count) {
        VkImage src = sc->app_images[image_index].image;
        int ok = 0;
        if (g_upscaler == FSR_UPSCALER_FSR1 &&
            fsr_record_render_scale_pass(g_dev, sc, src, target)) {
            ok = 1;
            fsr_easu_log_active(sc);
        } else {
            ok = fsr_record_rs_blit(g_dev, sc, src, target);
        }
        if (ok && fsr_submit_recorded(g_dev, sc, queue, wait_count, wait_sems, allow_async)) {
            ran_fsr = 1;
            wait_count = 0;
            wait_sems = NULL;
        }
    } else if (g_upscaler == FSR_UPSCALER_FSR1) {
        /* FSR is on but the app still renders at panel size — there is no
         * smaller source to EASU from. Do not run RCAS-on-a-stretched-frame
         * and call it FSR, and do not force 4x internal res. Restart the game
         * after enabling FSR Mode so render-scale can arm. */
        static int logged_no_rs = 0;
        if (!logged_no_rs) {
            logged_no_rs = 1;
            fsr_logi("FSR: on, but render-scale is not armed — presenting unscaled. "
                     "Restart the game after enabling FSR Mode.");
        }
    }

    VkResult result;
    if (fg_ok) {
        if (sc->fg_history_ready &&
            fg_run_interpolated_present(g_dev, sc, queue, target, wait_count, wait_sems,
                                        real_present, present_info)) {
            result = fsr_present_after_submit(queue, present_info, real_present);
        } else {
            fg_update_history(g_dev, sc, queue, target, wait_count, wait_sems);
            result = fsr_present_after_submit(queue, present_info, real_present);
        }
    } else {
        result = fsr_present_after_submit(queue, present_info, real_present);
    }

    /* A present that did not succeed may never have performed the semaphore wait
     * the WSI was asked for, which would leave our binary semaphore signalled and
     * make next frame's signal on it invalid. Replace the object rather than
     * gamble on the driver's behaviour. VK_SUBOPTIMAL_KHR is a success code and
     * does present, so it is excluded. */
    if (g_async_sem_slot >= 0 && result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        fsr_slot_recreate_sem(g_dev, sc, (uint32_t)g_async_sem_slot);
        g_async_sem_slot = -1;
    }

    if (ran_fsr)
        fsr_frame_tick();
    return result;
}
