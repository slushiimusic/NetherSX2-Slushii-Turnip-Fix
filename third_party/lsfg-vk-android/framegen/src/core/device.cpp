#include <volk.h>
#include <vulkan/vulkan_core.h>

#include "core/device.hpp"
#include "core/image.hpp"
#include "core/instance.hpp"
#include "common/exception.hpp"

#include <cstdint>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

using namespace LSFG::Core;

const std::vector<const char*> requiredExtensions = {
#ifndef __ANDROID__
    "VK_KHR_external_memory_fd",
    "VK_KHR_external_semaphore_fd",
#else
    // On Android we share via AHardwareBuffer, not opaque FDs.
    "VK_ANDROID_external_memory_android_hardware_buffer",
    "VK_KHR_external_memory",                  // base ext, dependency
    "VK_KHR_sampler_ycbcr_conversion",         // dependency of AHB ext
    "VK_KHR_dedicated_allocation",             // required for dedicated AHB import
    "VK_KHR_get_memory_requirements2",         // dependency
    "VK_KHR_bind_memory2",                     // dependency
    "VK_KHR_maintenance1",                     // dependency
#endif
};

namespace {

bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) return true;
    }
    return false;
}

} // namespace

const Image& Device::getFallbackDescriptorImage() const {
    return *this->fallbackDescriptorImage;
}

Device::Device(const Instance& instance, uint64_t deviceUUID) {
    // get all physical devices
    uint32_t deviceCount{};
    auto res = vkEnumeratePhysicalDevices(instance.handle(), &deviceCount, nullptr);
    if (res != VK_SUCCESS || deviceCount == 0)
        throw LSFG::vulkan_error(res, "Failed to enumerate physical devices");

    std::vector<VkPhysicalDevice> devices(deviceCount);
    res = vkEnumeratePhysicalDevices(instance.handle(), &deviceCount, devices.data());
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to get physical devices");

    // get device by uuid
    std::optional<VkPhysicalDevice> physicalDevice;
    for (const auto& device : devices) {
        VkPhysicalDeviceProperties properties;
        vkGetPhysicalDeviceProperties(device, &properties);

        const uint64_t uuid =
            static_cast<uint64_t>(properties.vendorID) << 32 | properties.deviceID;
        if (deviceUUID == uuid || deviceUUID == 0x1463ABAC) {
            physicalDevice = device;
            break;
        }
    }
    if (!physicalDevice)
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED,
            "Could not find physical device with UUID");

    // find queue family indices
    uint32_t familyCount{};
    vkGetPhysicalDeviceQueueFamilyProperties(*physicalDevice, &familyCount, nullptr);

    std::vector<VkQueueFamilyProperties> queueFamilies(familyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(*physicalDevice, &familyCount, queueFamilies.data());

    std::optional<uint32_t> computeFamilyIdx;
    for (uint32_t i = 0; i < familyCount; ++i) {
        if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT)
            computeFamilyIdx = i;
    }
    if (!computeFamilyIdx)
        throw LSFG::vulkan_error(VK_ERROR_INITIALIZATION_FAILED, "No compute queue family found");

    uint32_t extensionCount{};
    res = vkEnumerateDeviceExtensionProperties(*physicalDevice, nullptr, &extensionCount, nullptr);
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to enumerate device extensions");
    std::vector<VkExtensionProperties> availableExtensions(extensionCount);
    res = vkEnumerateDeviceExtensionProperties(*physicalDevice, nullptr,
        &extensionCount, availableExtensions.data());
    if (res != VK_SUCCESS)
        throw LSFG::vulkan_error(res, "Failed to get device extensions");

    std::vector<const char*> enabledExtensions;
    enabledExtensions.reserve(requiredExtensions.size() + 1);
    for (const char* extension : requiredExtensions) {
        if (!hasExtension(availableExtensions, extension)) {
            throw LSFG::vulkan_error(VK_ERROR_EXTENSION_NOT_PRESENT,
                std::string("Missing required device extension: ") + extension);
        }
        enabledExtensions.push_back(extension);
    }

    const bool hasRobustness2 =
        hasExtension(availableExtensions, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME);
    if (hasRobustness2) {
        enabledExtensions.push_back(VK_EXT_ROBUSTNESS_2_EXTENSION_NAME);
    }

    // Probe FP16 support on this physical device. The LSFG-Android port can
    // load precompiled SPIR-V FP16 shader variants from Lossless.dll (resource
    // IDs 304..351) which carry `OpCapability Float16`. Vulkan rejects those at
    // vkCreateShaderModule time unless the device was created with the
    // shaderFloat16 feature explicitly enabled. We probe and unconditionally
    // enable it when supported — there's no downside on FP32-only sessions and
    // it lets the FP16 path "just work" when the user toggles it on.
    VkPhysicalDeviceShaderFloat16Int8Features fp16Probe{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES,
    };
    VkPhysicalDeviceFeatures2 featsProbe{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
        .pNext = &fp16Probe,
    };
    vkGetPhysicalDeviceFeatures2(*physicalDevice, &featsProbe);
    const bool hasFloat16 = fp16Probe.shaderFloat16 == VK_TRUE;

    // create logical device
    const float queuePriority{1.0F}; // highest priority
    VkPhysicalDeviceRobustness2FeaturesEXT robustness2{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT,
        .nullDescriptor = VK_TRUE,
    };
    VkPhysicalDeviceVulkan13Features features13{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES,
        .pNext = hasRobustness2 ? &robustness2 : nullptr,
        .synchronization2 = VK_TRUE
    };
    // shaderFloat16 is exposed in core Vulkan 1.2 — same struct we already
    // chain. Setting it conditionally avoids regressing devices that don't
    // advertise the feature (the validation layers reject create_device when
    // requested features are unsupported).
    VkPhysicalDeviceVulkan12Features features12{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
        .pNext = &features13,
        .shaderFloat16 = hasFloat16 ? VK_TRUE : VK_FALSE,
        .timelineSemaphore = VK_TRUE,
        .vulkanMemoryModel = VK_TRUE
    };
    const VkDeviceQueueCreateInfo computeQueueDesc{
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = *computeFamilyIdx,
        .queueCount = 1,
        .pQueuePriorities = &queuePriority
    };
    const VkDeviceCreateInfo deviceCreateInfo{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = &features12,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &computeQueueDesc,
        .enabledExtensionCount = static_cast<uint32_t>(enabledExtensions.size()),
        .ppEnabledExtensionNames = enabledExtensions.data()
    };
    VkDevice deviceHandle{};
    res = vkCreateDevice(*physicalDevice, &deviceCreateInfo, nullptr, &deviceHandle);
    if (res != VK_SUCCESS || deviceHandle == VK_NULL_HANDLE)
        throw LSFG::vulkan_error(res, "Failed to create logical device");

    volkLoadDevice(deviceHandle);

    // get compute queue
    VkQueue queueHandle{};
    vkGetDeviceQueue(deviceHandle, *computeFamilyIdx, 0, &queueHandle);

    // store in shared ptr
    this->computeQueue = queueHandle;
    this->computeFamilyIdx = *computeFamilyIdx;
    this->physicalDevice = *physicalDevice;
    this->nullDescriptorSupported = hasRobustness2;
    this->device = std::shared_ptr<VkDevice>(
        new VkDevice(deviceHandle),
        [](VkDevice* device) {
            vkDestroyDevice(*device, nullptr);
        }
    );
    if (!this->nullDescriptorSupported) {
        this->fallbackDescriptorImage = std::make_shared<Core::Image>(*this,
            VkExtent2D{1, 1}, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
    }
}
