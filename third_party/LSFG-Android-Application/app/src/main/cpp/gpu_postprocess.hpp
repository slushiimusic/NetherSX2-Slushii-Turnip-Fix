#pragma once

#include "ahb_image_bridge.hpp"

#include <cstdint>

namespace lsfg_android {

struct GpuPostProcessConfig {
    /* 0 = normal post-process. 1/2/3 = rotate 90/180/270 clockwise instead, for
     * portrait-native panels: the swapchain then keeps its native preTransform
     * (cheap compositing) and this pass supplies the pre-rotated content that
     * vkCmdBlitImage cannot produce, since blit scales and flips but never
     * transposes. dst must have src's extents swapped for 90/270. */
    int rotateDir = 0;
    int method = 0;
    float sharpness = 0.5f;
    float strength = 0.5f;
};

class GpuPostProcessor {
public:
    bool process(VulkanSession &vk,
                 const AhbImage &src,
                 const AhbImage &dst,
                 const GpuPostProcessConfig &config);

    void reset(VulkanSession &vk);

private:
    bool ensurePipeline(VulkanSession &vk);

    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkPipeline rotatePipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
};

} // namespace lsfg_android
