#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "core/image.hpp"
#include "core/device.hpp"
#include "common/exception.hpp"

#include <cstdint>
#include <memory>
#include <optional>

#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#endif

using namespace LSFG::Core;

namespace {

/* A ZERO-SIZED LEVEL IS WHERE VK_ERROR_INITIALIZATION_FAILED (-3) COMES FROM.
 *
 * The optical-flow pyramid halves the (flow_scale-divided) extent per level
 * until a dimension rounds to zero: capture 640x448 at flow_scale 0.25 walks
 * 160x112 -> 80x56 -> 40x28 -> 20x14 -> 10x7 -> 5x3 -> 2x1 -> 1x0.
 * vkCreateImage happily ACCEPTS 1x0, vkGetImageMemoryRequirements then reports
 * size=0, and vkAllocateMemory rejects a zero-size allocation with
 * VK_ERROR_INITIALIZATION_FAILED. The throw surfaces far from the cause, as
 * "createContextFromAHB threw: Failed to allocate memory for Vulkan image
 * (error -3)", and framegen silently falls back to passthrough with ctxId=-1.
 *
 * It only bites at SMALL capture sizes -- a larger capture bottoms the pyramid
 * out before underflowing -- which is why lowering fg_capture_w/h to cut GPU
 * cost switched frame generation off entirely (measured 2026-09-02 on a 640x448
 * capture; 1024x896 survived). Clamping every dimension to >=1 costs nothing and
 * makes the smallest level degenerate-but-valid instead of unallocatable. */
inline VkExtent2D clampExtent1x1(VkExtent2D e) {
    if (e.width == 0) e.width = 1;
    if (e.height == 0) e.height = 1;
    return e;
}

} // namespace


Image::Image(const Core::Device& device, VkExtent2D extent, VkFormat format,
        VkImageUsageFlags usage, VkImageAspectFlags aspectFlags)
        : extent(clampExtent1x1(extent)), format(format), aspectFlags(aspectFlags) {
    extent = this->extent;   // the body builds VkImageCreateInfo from the param
    // create image
    const VkImageCreateInfo desc{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = {
            .width = extent.width,
            .height = extent.height,
            .depth = 1
        },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    VkImage imageHandle{};
    auto res = vkCreateImage(device.handle(), &desc, nullptr, &imageHandle);
    if (res != VK_SUCCESS || imageHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to create Vulkan image");

    // find memory type
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), &memProps);

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(device.handle(), imageHandle, &memReqs);

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunsafe-buffer-usage"
    std::optional<uint32_t> memType{};
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((memReqs.memoryTypeBits & (1 << i)) && // NOLINTBEGIN
            (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            memType.emplace(i);
            break;
        } // NOLINTEND
    }
    if (!memType.has_value())
        throw LSFG::vulkan_error(VK_ERROR_UNKNOWN, "Unable to find memory type for image");
#pragma clang diagnostic pop

    // allocate and bind memory
    const VkMemoryAllocateInfo allocInfo{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = memReqs.size,
        .memoryTypeIndex = memType.value()
    };
    VkDeviceMemory memoryHandle{};
    res = vkAllocateMemory(device.handle(), &allocInfo, nullptr, &memoryHandle);
    if (res != VK_SUCCESS || memoryHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to allocate memory for Vulkan image");

    res = vkBindImageMemory(device.handle(), imageHandle, memoryHandle, 0);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to bind memory to Vulkan image");

    // create image view
    const VkImageViewCreateInfo viewDesc{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = imageHandle,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .components = {
            .r = VK_COMPONENT_SWIZZLE_IDENTITY,
            .g = VK_COMPONENT_SWIZZLE_IDENTITY,
            .b = VK_COMPONENT_SWIZZLE_IDENTITY,
            .a = VK_COMPONENT_SWIZZLE_IDENTITY
        },
        .subresourceRange = {
            .aspectMask = aspectFlags,
            .levelCount = 1,
            .layerCount = 1
        }
    };

    VkImageView viewHandle{};
    res = vkCreateImageView(device.handle(), &viewDesc, nullptr, &viewHandle);
    if (res != VK_SUCCESS || viewHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to create image view");

    // store objects in shared ptr
    this->layout = std::make_shared<VkImageLayout>(VK_IMAGE_LAYOUT_UNDEFINED);
    this->image = std::shared_ptr<VkImage>(
        new VkImage(imageHandle),
        [dev = device.handle()](VkImage* img) {
            vkDestroyImage(dev, *img, nullptr);
        }
    );
    this->memory = std::shared_ptr<VkDeviceMemory>(
        new VkDeviceMemory(memoryHandle),
        [dev = device.handle()](VkDeviceMemory* mem) {
            vkFreeMemory(dev, *mem, nullptr);
        }
    );
    this->view = std::shared_ptr<VkImageView>(
        new VkImageView(viewHandle),
        [dev = device.handle()](VkImageView* imgView) {
            vkDestroyImageView(dev, *imgView, nullptr);
        }
    );
}

// shared memory constructor

Image::Image(const Core::Device& device, VkExtent2D extent, VkFormat format,
        VkImageUsageFlags usage, VkImageAspectFlags aspectFlags, int fd)
        : extent(clampExtent1x1(extent)), format(format), aspectFlags(aspectFlags) {
    extent = this->extent;   // the body builds VkImageCreateInfo from the param
    // create image
    const VkExternalMemoryImageCreateInfo externalInfo{
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR
    };
    const VkImageCreateInfo desc{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &externalInfo,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = {
            .width = extent.width,
            .height = extent.height,
            .depth = 1
        },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    VkImage imageHandle{};
    auto res = vkCreateImage(device.handle(), &desc, nullptr, &imageHandle);
    if (res != VK_SUCCESS || imageHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to create Vulkan image");

    // find memory type
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), &memProps);

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(device.handle(), imageHandle, &memReqs);

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunsafe-buffer-usage"
    std::optional<uint32_t> memType{};
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((memReqs.memoryTypeBits & (1 << i)) && // NOLINTBEGIN
            (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            memType.emplace(i);
            break;
        } // NOLINTEND
    }
    if (!memType.has_value())
        throw LSFG::vulkan_error(VK_ERROR_UNKNOWN, "Unable to find memory type for image");
#pragma clang diagnostic pop

    // ~~allocate~~ and bind memory
    const VkMemoryDedicatedAllocateInfoKHR dedicatedInfo2{
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO_KHR,
        .image = imageHandle,
    };
    const VkImportMemoryFdInfoKHR importInfo{
        .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR,
        .pNext = &dedicatedInfo2,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT_KHR,
        .fd = fd // closes the fd
    };
    const VkMemoryAllocateInfo allocInfo{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = fd == -1 ? nullptr : &importInfo,
        .allocationSize = memReqs.size,
        .memoryTypeIndex = memType.value()
    };
    VkDeviceMemory memoryHandle{};
    res = vkAllocateMemory(device.handle(), &allocInfo, nullptr, &memoryHandle);
    if (res != VK_SUCCESS || memoryHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to allocate memory for Vulkan image");

    res = vkBindImageMemory(device.handle(), imageHandle, memoryHandle, 0);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to bind memory to Vulkan image");

    // create image view
    const VkImageViewCreateInfo viewDesc{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = imageHandle,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .components = {
            .r = VK_COMPONENT_SWIZZLE_IDENTITY,
            .g = VK_COMPONENT_SWIZZLE_IDENTITY,
            .b = VK_COMPONENT_SWIZZLE_IDENTITY,
            .a = VK_COMPONENT_SWIZZLE_IDENTITY
        },
        .subresourceRange = {
            .aspectMask = aspectFlags,
            .levelCount = 1,
            .layerCount = 1
        }
    };

    VkImageView viewHandle{};
    res = vkCreateImageView(device.handle(), &viewDesc, nullptr, &viewHandle);
    if (res != VK_SUCCESS || viewHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to create image view");

    // store objects in shared ptr
    this->layout = std::make_shared<VkImageLayout>(VK_IMAGE_LAYOUT_UNDEFINED);
    this->image = std::shared_ptr<VkImage>(
        new VkImage(imageHandle),
        [dev = device.handle()](VkImage* img) {
            vkDestroyImage(dev, *img, nullptr);
        }
    );
    this->memory = std::shared_ptr<VkDeviceMemory>(
        new VkDeviceMemory(memoryHandle),
        [dev = device.handle()](VkDeviceMemory* mem) {
            vkFreeMemory(dev, *mem, nullptr);
        }
    );
    this->view = std::shared_ptr<VkImageView>(
        new VkImageView(viewHandle),
        [dev = device.handle()](VkImageView* imgView) {
            vkDestroyImageView(dev, *imgView, nullptr);
        }
    );
}

#ifdef __ANDROID__

// AHardwareBuffer-backed constructor. Android-specific path: opaque-FD export
// from AHB-imported memory isn't supported by Adreno/Mali, so we share via
// the AHB itself instead. Caller retains AHB ownership.
Image::Image(const Core::Device& device, VkExtent2D extent, VkFormat format,
        VkImageUsageFlags usage, VkImageAspectFlags aspectFlags,
        AHardwareBuffer* ahb)
        : extent(clampExtent1x1(extent)), format(format), aspectFlags(aspectFlags),
          externalShared(true) {
    extent = this->extent;   // the body builds VkImageCreateInfo from the param
    if (ahb == nullptr)
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "AHB is null");

    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
    };
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
        .pNext = &fmtProps,
    };
    auto res = vkGetAndroidHardwareBufferPropertiesANDROID(device.handle(), ahb, &ahbProps);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "vkGetAndroidHardwareBufferPropertiesANDROID failed");

    const VkExternalMemoryImageCreateInfo externalInfo{
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    const VkImageCreateInfo desc{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &externalInfo,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = { extent.width, extent.height, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    VkImage imageHandle{};
    res = vkCreateImage(device.handle(), &desc, nullptr, &imageHandle);
    if (res != VK_SUCCESS || imageHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to create Vulkan image (AHB)");

    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), &memProps);
    std::optional<uint32_t> memType{};
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if (ahbProps.memoryTypeBits & (1u << i)) { memType.emplace(i); break; }
    }
    if (!memType.has_value()) {
        vkDestroyImage(device.handle(), imageHandle, nullptr);
        throw LSFG::vulkan_error(VK_ERROR_UNKNOWN, "No memory type for AHB import");
    }

    const VkMemoryDedicatedAllocateInfo dedicated{
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .image = imageHandle,
    };
    const VkImportAndroidHardwareBufferInfoANDROID importInfo{
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .pNext = &dedicated,
        .buffer = ahb,
    };
    const VkMemoryAllocateInfo allocInfo{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &importInfo,
        .allocationSize = ahbProps.allocationSize,
        .memoryTypeIndex = memType.value(),
    };
    VkDeviceMemory memoryHandle{};
    res = vkAllocateMemory(device.handle(), &allocInfo, nullptr, &memoryHandle);
    if (res != VK_SUCCESS || memoryHandle == VK_NULL_HANDLE) {
        vkDestroyImage(device.handle(), imageHandle, nullptr);
        throw LSFG::vulkan_error(res, "Failed to import AHB into VkDeviceMemory");
    }

    res = vkBindImageMemory(device.handle(), imageHandle, memoryHandle, 0);
    if (res != VK_SUCCESS) {
        vkFreeMemory(device.handle(), memoryHandle, nullptr);
        vkDestroyImage(device.handle(), imageHandle, nullptr);
        throw LSFG::vulkan_error(res, "Failed to bind AHB memory");
    }

    const VkImageViewCreateInfo viewDescAhb{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = imageHandle,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .components = {
            .r = VK_COMPONENT_SWIZZLE_IDENTITY,
            .g = VK_COMPONENT_SWIZZLE_IDENTITY,
            .b = VK_COMPONENT_SWIZZLE_IDENTITY,
            .a = VK_COMPONENT_SWIZZLE_IDENTITY,
        },
        .subresourceRange = {
            .aspectMask = aspectFlags,
            .levelCount = 1,
            .layerCount = 1,
        },
    };
    VkImageView viewHandleAhb{};
    res = vkCreateImageView(device.handle(), &viewDescAhb, nullptr, &viewHandleAhb);
    if (res != VK_SUCCESS || viewHandleAhb == VK_NULL_HANDLE) {
        vkFreeMemory(device.handle(), memoryHandle, nullptr);
        vkDestroyImage(device.handle(), imageHandle, nullptr);
        throw LSFG::vulkan_error(res, "Failed to create AHB image view");
    }

    // The Android side hands AHB-backed images back and forth in GENERAL layout.
    // Track that here so queue-family acquire/release barriers use the same layout.
    this->layout = std::make_shared<VkImageLayout>(VK_IMAGE_LAYOUT_GENERAL);
    this->image = std::shared_ptr<VkImage>(
        new VkImage(imageHandle),
        [dev = device.handle()](VkImage* img) {
            vkDestroyImage(dev, *img, nullptr);
        }
    );
    this->memory = std::shared_ptr<VkDeviceMemory>(
        new VkDeviceMemory(memoryHandle),
        [dev = device.handle()](VkDeviceMemory* mem) {
            vkFreeMemory(dev, *mem, nullptr);
        }
    );
    this->view = std::shared_ptr<VkImageView>(
        new VkImageView(viewHandleAhb),
        [dev = device.handle()](VkImageView* imgView) {
            vkDestroyImageView(dev, *imgView, nullptr);
        }
    );
}

#endif // __ANDROID__
