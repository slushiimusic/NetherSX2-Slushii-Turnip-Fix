#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* fsr_upscaler.cpp includes <vulkan/vulkan_core.h> before this header and so
 * already has the real handle types; vulkan_shim.cpp does not, and keeps the
 * opaque ones it has always used. Both spellings are pointer-sized (VkResult is
 * 32-bit either way), so the ABI across the two translation units is identical.
 * VULKAN_CORE_H_ is vulkan_core.h's own include guard. */
#ifndef VULKAN_CORE_H_
typedef void *VkDevice;
typedef void *VkQueue;
typedef void *VkSwapchainKHR;
typedef uint32_t VkResult;
#endif

#define FSR_UPSCALER_OFF  0
#define FSR_UPSCALER_FSR1 1

#define FSR_QUALITY_PERFORMANCE 0
#define FSR_QUALITY_BALANCED    1
#define FSR_QUALITY_ULTRA       2
#define FSR_QUALITY_4X          3

#define FG_MODE_OFF              0
#define FG_MODE_BLEND            1
#define FG_MODE_OPTICAL_FLOW_STUB 2
#define FG_MODE_FLOW             3

typedef void *(*PFN_fsr_vk_get_device_proc)(VkDevice device, const char *name);

void fsr_set_config(int upscaler, float sharpness, int quality);
void fg_set_config(int enabled, int mode, float blend_alpha);
void fg_set_extended(int multiplier, float flow_scale, float display_hz);
void fsr_set_log_fn(void (*logi)(const char *), void (*loge)(const char *));

int  fsr_bind_device(VkDevice device, PFN_fsr_vk_get_device_proc get_proc);
void fsr_unbind_device(VkDevice device);

/* Supply the physical device and vkGetPhysicalDeviceMemoryProperties. Both are
 * INSTANCE-level, so the device-level get_proc above cannot reach them, and
 * without them no host-visible allocation can be found — every uniform buffer
 * silently fails to map. Safe to call before or after fsr_bind_device. */
void fsr_set_physical_device(void *phys, void *get_mem_props_fn);

void fsr_on_swapchain_created(VkDevice device, VkSwapchainKHR swapchain,
                              uint32_t width, uint32_t height, uint32_t format);
void fsr_on_swapchain_destroyed(VkDevice device, VkSwapchainKHR swapchain);

/* Swapchain image list (valid after fsr_on_swapchain_created). */
typedef struct FsrSwapchainInfo {
    void *const *images;
    uint32_t image_count;
    uint32_t width;
    uint32_t height;
    uint32_t format;
} FsrSwapchainInfo;
int fsr_query_swapchain(VkDevice device, VkSwapchainKHR swapchain, FsrSwapchainInfo *out);
uint32_t fsr_swapchain_present_count(VkDevice device, VkSwapchainKHR swapchain);
void fsr_bump_present_count(VkDevice device, VkSwapchainKHR swapchain);

void fsr_on_viewport(float x, float y, float width, float height);
int  fsr_viewport_is_ready(void);
void fsr_frame_tick(void);

/* ------------------------------------------------------------------ */
/* Render-scale — DISABLED.                                            */
/*                                                                     */
/* Vulkan only permits presenting images the swapchain owns. Handing    */
/* the application FSR-allocated images from vkGetSwapchainImagesKHR    */
/* is unsound: at present time WSI/SurfaceFlinger must reconcile a      */
/* buffer they never created, and SurfaceFlinger is a system process.   */
/* fsr_render_scale_wanted() hard-returns 0 so a leftover               */
/* render_scale= line cannot arm this.                                 */
/* ------------------------------------------------------------------ */
void fsr_set_render_scale(float scale);
int  fsr_render_scale_wanted(void);

/* Log the reason render-scale is not running. Once per process, first reason
 * wins — callers on the shim side use this so every fallback line comes out of
 * the same sink and reads the same way. */
void fsr_render_scale_report_off(const char *reason);

/* Reduced extent for a panel: rounded down to even, never below 64, and
 * required to be strictly smaller than the panel. 0 = do not scale. */
int  fsr_render_scale_extent(uint32_t panel_w, uint32_t panel_h,
                             uint32_t *out_w, uint32_t *out_h);

/* Create the app-visible images for a swapchain that the real driver created at
 * panel size while the application believes it is rs_w x rs_h. Call after
 * fsr_on_swapchain_created. Returns 1 when armed, 0 (with a logged reason) when
 * the caller must hand the real swapchain images back instead. */
int  fsr_render_scale_arm(VkDevice device, VkSwapchainKHR swapchain,
                          uint32_t rs_w, uint32_t rs_h);

/* vkGetSwapchainImagesKHR substitution. Returns 1 when it answered the query
 * (result in *out_result), 0 to fall through to the driver's own function. */
int  fsr_render_scale_query_images(VkDevice device, VkSwapchainKHR swapchain,
                                   uint32_t *pCount, void *pImages,
                                   VkResult *out_result);

/* 1 once the application has actually taken substituted images for some live
 * swapchain. While this is true every present MUST go through
 * fsr_on_queue_present, whatever the upscaler toggle now says: the finished
 * frame lives in an FSR-owned image and nothing else will copy it out. */
int  fsr_render_scale_any_active(void);

/* Intercept vkQueuePresentKHR. Returns the real present result. */
VkResult fsr_on_queue_present(VkQueue queue, VkDevice device, const void *present_info,
                              VkResult (*real_present)(VkQueue, const void *));

#ifdef __cplusplus
}
#endif
