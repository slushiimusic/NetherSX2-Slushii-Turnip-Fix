#include "context.hpp"
#include "config/config.hpp"
#include "common/exception.hpp"
#include "extract/extract.hpp"
#include "extract/trans.hpp"
#include "utils/utils.hpp"
#include "hooks.hpp"
#include "layer.hpp"

#ifdef __ANDROID__
#include <android/hardware_buffer.h>
#include <android/log.h>
#define LSFG_CTX_TAG "VulkanShim"
#define LSFG_CTX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LSFG_CTX_TAG, __VA_ARGS__)
#endif

#include <vulkan/vulkan_core.h>
#include <lsfg_3_1.hpp>
#include <lsfg_3_1p.hpp>

#include <filesystem>
#include <exception>
#include <iostream>
#include <cstdint>
#include <cstdlib>
#include <vector>
#include <chrono>
#include <memory>
#include <string>
#include <thread>
#include <array>

LsContext::LsContext(const Hooks::DeviceInfo& info, VkSwapchainKHR swapchain,
        VkExtent2D extent, const std::vector<VkImage>& swapchainImages)
        : swapchain(swapchain), swapchainImages(swapchainImages),
          extent(extent) {
    // get updated configuration
    auto& conf = Config::activeConf;
    if (!conf.config_file.empty()
            && (
                    !std::filesystem::exists(conf.config_file)
                  || conf.timestamp != std::filesystem::last_write_time(conf.config_file)
            )) {
        std::cerr << "lsfg-vk: Rereading configuration, as it is no longer valid.\n";
        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        // reread configuration
        const std::string file = Utils::getConfigFile();
        const auto name = Utils::getProcessName();
        try {
            Config::updateConfig(file);
            conf = Config::getConfig(name);
        } catch (const std::exception& e) {
            std::cerr << "lsfg-vk: Failed to update configuration, continuing using old:\n";
            std::cerr << "- " << e.what() << '\n';
        }

        LSFG_3_1P::finalize();
        LSFG_3_1::finalize();

        // print config
        std::cerr << "lsfg-vk: Reloaded configuration for " << name.second << ":\n";
        if (!conf.dll.empty()) std::cerr << "  Using DLL from: " << conf.dll << '\n';
        std::cerr << "  Multiplier: " << conf.multiplier << '\n';
        std::cerr << "  Flow Scale: " << conf.flowScale << '\n';
        std::cerr << "  Performance Mode: " << (conf.performance ? "Enabled" : "Disabled") << '\n';
        std::cerr << "  HDR Mode: " << (conf.hdr ? "Enabled" : "Disabled") << '\n';
        if (conf.e_present != 2) std::cerr << "  ! Present Mode: " << conf.e_present << '\n';

        if (conf.multiplier <= 1) return;
    }
    // we could take the format from the swapchain,
    // but honestly this is safer.
    const VkFormat format = conf.hdr
        ? VK_FORMAT_R8G8B8A8_UNORM
        : VK_FORMAT_R16G16B16A16_SFLOAT;

#ifdef __ANDROID__
    // Android path: use AHardwareBuffer-backed images for sharing with framegen.
    // Turnip/Mesa on Android doesn't support OPAQUE_FD export, so we use the
    // AHB path (createContextFromAHB + presentContext with -1 + waitIdle).

    this->frame_0 = Mini::Image(info.device, info.physicalDevice,
        extent, format, VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
    this->frame_1 = Mini::Image(info.device, info.physicalDevice,
        extent, format, VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);

    for (size_t i = 0; i < static_cast<size_t>(conf.multiplier - 1); ++i)
        this->out_n.emplace_back(info.device, info.physicalDevice,
            extent, format,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);

    // initialize lsfg
    auto* lsfgInitialize = LSFG_3_1::initialize;
    auto* lsfgDeleteContext = LSFG_3_1::deleteContext;
    if (conf.performance) {
        lsfgInitialize = LSFG_3_1P::initialize;
        lsfgDeleteContext = LSFG_3_1P::deleteContext;
    }

    setenv("DISABLE_LSFG", "1", 1); // NOLINT

    lsfgInitialize(
        Utils::getDeviceUUID(info.physicalDevice),
        conf.hdr, 1.0F / conf.flowScale, conf.multiplier - 1,
        [](const std::string& name) {
            auto dxbc = Extract::getShader(name);
            auto spirv = Extract::translateShader(dxbc);
            return spirv;
        }
    );

    // Create framegen context using AHB sharing
    std::vector<AHardwareBuffer*> outAhbs;
    outAhbs.reserve(conf.multiplier - 1);
    for (size_t i = 0; i < static_cast<size_t>(conf.multiplier - 1); ++i)
        outAhbs.push_back(this->out_n.at(i).getAhb());

    int32_t ctxId;
    if (conf.performance)
        ctxId = LSFG_3_1P::createContextFromAHB(
            this->frame_0.getAhb(), this->frame_1.getAhb(),
            outAhbs, extent, format);
    else
        ctxId = LSFG_3_1::createContextFromAHB(
            this->frame_0.getAhb(), this->frame_1.getAhb(),
            outAhbs, extent, format);

    this->lsfgCtxId = std::shared_ptr<int32_t>(
        new int32_t(ctxId),
        [lsfgDeleteContext = lsfgDeleteContext](const int32_t* id) {
            lsfgDeleteContext(*id);
        }
    );

    unsetenv("DISABLE_LSFG"); // NOLINT

    std::cerr << "lsfg-vk: Android AHB context created (id=" << ctxId << ")\n";

#else
    // Desktop Linux path: use OPAQUE_FD-based image sharing

    std::array<int, 2> fds{};
    this->frame_0 = Mini::Image(info.device, info.physicalDevice,
        extent, format, VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT,
        &fds.at(0));
    this->frame_1 = Mini::Image(info.device, info.physicalDevice,
        extent, format, VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT,
        &fds.at(1));

    std::vector<int> outFds(conf.multiplier - 1);
    for (size_t i = 0; i < (conf.multiplier - 1); ++i)
        this->out_n.emplace_back(info.device, info.physicalDevice,
            extent, format,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT,
            &outFds.at(i));

    // initialize lsfg
    auto* lsfgInitialize = LSFG_3_1::initialize;
    auto* lsfgCreateContext = LSFG_3_1::createContext;
    auto* lsfgDeleteContext = LSFG_3_1::deleteContext;
    if (conf.performance) {
        lsfgInitialize = LSFG_3_1P::initialize;
        lsfgCreateContext = LSFG_3_1P::createContext;
        lsfgDeleteContext = LSFG_3_1P::deleteContext;
    }

    setenv("DISABLE_LSFG", "1", 1); // NOLINT

    lsfgInitialize(
        Utils::getDeviceUUID(info.physicalDevice),
        conf.hdr, 1.0F / conf.flowScale, conf.multiplier - 1,
        [](const std::string& name) {
            auto dxbc = Extract::getShader(name);
            auto spirv = Extract::translateShader(dxbc);
            return spirv;
        }
    );

    this->lsfgCtxId = std::shared_ptr<int32_t>(
        new int32_t(lsfgCreateContext(fds.at(0), fds.at(1), outFds, extent, format)),
        [lsfgDeleteContext = lsfgDeleteContext](const int32_t* id) {
            lsfgDeleteContext(*id);
        }
    );

    unsetenv("DISABLE_LSFG"); // NOLINT
#endif

    // prepare render passes
    this->cmdPool = Mini::CommandPool(info.device, info.queue.first);
    for (size_t i = 0; i < 8; i++) {
        auto& pass = this->passInfos.at(i);
        pass.renderSemaphores.resize(conf.multiplier - 1);
        pass.acquireSemaphores.resize(conf.multiplier - 1);
        pass.postCopyBufs.resize(conf.multiplier - 1);
        pass.postCopySemaphores.resize(conf.multiplier - 1);
        pass.prevPostCopySemaphores.resize(conf.multiplier - 1);
    }

#ifdef __ANDROID__
    this->fenceDevice = info.device;
    for (size_t i = 0; i < 8; i++) {
        const VkFenceCreateInfo fci{ .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        auto res = Layer::ovkCreateFence(info.device, &fci, nullptr, &this->preCopyFences[i]);
        if (res != VK_SUCCESS)
            throw LSFG::vulkan_error(res, "Failed to create LSFG pre-copy fence");
        res = Layer::ovkCreateFence(info.device, &fci, nullptr, &this->postCopyFences[i]);
        if (res != VK_SUCCESS)
            throw LSFG::vulkan_error(res, "Failed to create LSFG post-copy fence");
    }
#endif
}

LsContext::~LsContext() {
#ifdef __ANDROID__
    drainAsync();
    if (fenceDevice != VK_NULL_HANDLE) {
        for (auto& f : preCopyFences) {
            if (f != VK_NULL_HANDLE) {
                Layer::ovkDestroyFence(fenceDevice, f, nullptr);
                f = VK_NULL_HANDLE;
            }
        }
        for (auto& f : postCopyFences) {
            if (f != VK_NULL_HANDLE) {
                Layer::ovkDestroyFence(fenceDevice, f, nullptr);
                f = VK_NULL_HANDLE;
            }
        }
    }
#endif
}

void LsContext::drainAsync() {
#ifdef __ANDROID__
    {
        std::lock_guard<std::mutex> lock(asyncMu);
        asyncStop.store(true);
    }
    asyncCv.notify_all();
    if (asyncWorker.joinable())
        asyncWorker.join();
    asyncStop.store(false);
    asyncBusy.store(false);
    pendingJob = {};
#endif
}

#ifdef __ANDROID__
void LsContext::ensureAsyncWorker() {
    if (asyncWorker.joinable())
        return;
    asyncStop.store(false);
    asyncWorker = std::thread([this] { asyncWorkerMain(); });
}

void LsContext::asyncWorkerMain() {
    while (true) {
        AsyncJob job;
        {
            std::unique_lock<std::mutex> lock(asyncMu);
            asyncCv.wait(lock, [&] {
                return asyncStop.load() || pendingJob.valid;
            });
            if (asyncStop.load() && !pendingJob.valid)
                return;
            job = pendingJob;
            pendingJob = {};
        }
        try {
            finishAsyncJob(job);
        } catch (const std::exception& e) {
            LSFG_CTX_LOGI("LSFG: async worker error: %s", e.what());
        }
        asyncBusy.store(false);
    }
}

VkResult LsContext::presentGenFramesAndroid(const Hooks::DeviceInfo& info, const void* pNext,
        VkQueue queue, uint32_t presentIdx, uint64_t passSlot, VkFence preCopyFence) {
    const auto& conf = Config::activeConf;
    auto& pass = this->passInfos.at(passSlot % 8);

    if (preCopyFence != VK_NULL_HANDLE) {
        auto fres = Layer::ovkWaitForFences(info.device, 1, &preCopyFence,
                VK_TRUE, 2'000'000'000ULL);
        if (fres != VK_SUCCESS)
            throw LSFG::vulkan_error(fres, "LSFG pre-copy fence wait failed");
        Layer::ovkResetFences(info.device, 1, &preCopyFence);
    }

    /* AHB path has no output semaphores — must idle before reading out_n. */
    std::vector<int> noOutSems;
    if (conf.performance)
        LSFG_3_1P::presentContext(*this->lsfgCtxId, -1, noOutSems);
    else
        LSFG_3_1::presentContext(*this->lsfgCtxId, -1, noOutSems);

    if (conf.performance)
        LSFG_3_1P::waitIdle();
    else
        LSFG_3_1::waitIdle();

    /*
     * Burst gen+real presents (~0.2ms apart) look like 60Hz double-flash judder on a
     * 120Hz panel, so space each present by one refresh under MAILBOX/IMMEDIATE.
     *
     * Under FIFO this is counter-productive: the presentation engine already paces
     * every present to its own vblank, and the sleep runs inside the asyncBusy
     * window, burning ~8.3ms of a 16.7ms budget. That is what makes the worker
     * overrun and drop framegen for the next frame. Set presentGapUs=0 on FIFO.
     */
    const auto presentGap = std::chrono::microseconds(conf.presentGapUs);

    constexpr uint64_t acquire_timeout_ns = 2'000'000'000ULL;
    VkResult lastRes = VK_SUCCESS;
    for (size_t i = 0; i < static_cast<size_t>(conf.multiplier - 1); i++) {
        pass.acquireSemaphores.at(i) = Mini::Semaphore(info.device);
        uint32_t imageIdx{};
        auto res = Layer::ovkAcquireNextImageKHR(info.device, this->swapchain,
            acquire_timeout_ns, pass.acquireSemaphores.at(i).handle(), VK_NULL_HANDLE, &imageIdx);
        if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR)
            throw LSFG::vulkan_error(res, "Failed to acquire next swapchain image");

        pass.postCopySemaphores.at(i) = Mini::Semaphore(info.device);
        pass.prevPostCopySemaphores.at(i) = Mini::Semaphore(info.device);
        pass.postCopyBufs.at(i) = Mini::CommandBuffer(info.device, this->cmdPool);
        pass.postCopyBufs.at(i).begin();
        Utils::copyImage(pass.postCopyBufs.at(i).handle(),
            this->out_n.at(i).handle(),
            this->swapchainImages.at(imageIdx),
            this->extent.width, this->extent.height,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            false, true);
        pass.postCopyBufs.at(i).end();
        pass.postCopyBufs.at(i).submit(queue,
            { pass.acquireSemaphores.at(i).handle() },
            { pass.postCopySemaphores.at(i).handle(),
              pass.prevPostCopySemaphores.at(i).handle() },
            VK_NULL_HANDLE);

        std::vector<VkSemaphore> waitSemaphores{ pass.postCopySemaphores.at(i).handle() };
        if (i != 0)
            waitSemaphores.emplace_back(pass.prevPostCopySemaphores.at(i - 1).handle());

        const VkPresentInfoKHR presentInfo{
            .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
            .pNext = i == 0 ? pNext : nullptr,
            .waitSemaphoreCount = static_cast<uint32_t>(waitSemaphores.size()),
            .pWaitSemaphores = waitSemaphores.data(),
            .swapchainCount = 1,
            .pSwapchains = &this->swapchain,
            .pImageIndices = &imageIdx,
        };
        res = Layer::ovkQueuePresentKHR(queue, &presentInfo);
        if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR)
            throw LSFG::vulkan_error(res, "Failed to present swapchain image");
        lastRes = res;
        if (presentGap.count() > 0)
            std::this_thread::sleep_for(presentGap);
    }

    VkSemaphore lastPrev =
        pass.prevPostCopySemaphores.at(conf.multiplier - 1 - 1).handle();
    const VkPresentInfoKHR finalPresentInfo{
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &lastPrev,
        .swapchainCount = 1,
        .pSwapchains = &this->swapchain,
        .pImageIndices = &presentIdx,
    };
    lastRes = Layer::ovkQueuePresentKHR(queue, &finalPresentInfo);
    if (lastRes != VK_SUCCESS && lastRes != VK_SUBOPTIMAL_KHR)
        throw LSFG::vulkan_error(lastRes, "Failed to present swapchain image");
    return lastRes;
}

void LsContext::finishAsyncJob(AsyncJob job) {
    presentGenFramesAndroid(job.info, nullptr, job.queue, job.presentIdx,
            job.passSlot, job.preCopyFence);
}

VkResult LsContext::presentAsyncAndroid(const Hooks::DeviceInfo& info, const void* /*pNext*/,
        VkQueue queue, const std::vector<VkSemaphore>& gameRenderSemaphores, uint32_t presentIdx) {
    if (asyncBusy.load()) {
        return VK_TIMEOUT; /* nether bridge treats as busy → real present */
    }

    ensureAsyncWorker();
    const uint64_t slot = this->frameIdx;
    auto& pass = this->passInfos.at(slot % 8);
    VkFence preFence = this->preCopyFences[slot % 8];

    pass.preCopySemaphores.at(1) = Mini::Semaphore(info.device);
    pass.preCopyBuf = Mini::CommandBuffer(info.device, this->cmdPool);
    pass.preCopyBuf.begin();
    Utils::copyImage(pass.preCopyBuf.handle(),
        this->swapchainImages.at(presentIdx),
        this->frameIdx % 2 == 0 ? this->frame_0.handle() : this->frame_1.handle(),
        this->extent.width, this->extent.height,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        true, false);
    pass.preCopyBuf.end();

    std::vector<VkSemaphore> copyWaits = gameRenderSemaphores;
    if (this->frameIdx > 0)
        copyWaits.emplace_back(this->passInfos.at((this->frameIdx - 1) % 8)
            .preCopySemaphores.at(1).handle());

    Layer::ovkResetFences(info.device, 1, &preFence);
    pass.preCopyBuf.submit(queue, copyWaits,
        { pass.preCopySemaphores.at(1).handle() }, preFence);

    AsyncJob job;
    job.info = info;
    job.queue = queue;
    job.presentIdx = presentIdx;
    job.passSlot = slot;
    job.preCopyFence = preFence;
    job.valid = true;

    {
        std::lock_guard<std::mutex> lock(asyncMu);
        if (pendingJob.valid || asyncBusy.load()) {
            /* Should be rare — wait for snapshot fence then present real only. */
            Layer::ovkWaitForFences(info.device, 1, &preFence, VK_TRUE, 2'000'000'000ULL);
            Layer::ovkResetFences(info.device, 1, &preFence);
            VkSemaphore sem = pass.preCopySemaphores.at(1).handle();
            const VkPresentInfoKHR pi{
                .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &sem,
                .swapchainCount = 1,
                .pSwapchains = &this->swapchain,
                .pImageIndices = &presentIdx,
            };
            this->frameIdx++;
            return Layer::ovkQueuePresentKHR(queue, &pi);
        }
        asyncBusy.store(true);
        pendingJob = job;
    }
    asyncCv.notify_one();
    this->frameIdx++;
    return VK_SUCCESS;
}

VkResult LsContext::presentSyncAndroid(const Hooks::DeviceInfo& info, const void* pNext,
        VkQueue queue, const std::vector<VkSemaphore>& gameRenderSemaphores, uint32_t presentIdx) {
    auto& pass = this->passInfos.at(this->frameIdx % 8);
    const uint64_t slot = this->frameIdx;
    VkFence preFence = this->preCopyFences[slot % 8];

    LSFG_CTX_LOGI("LSFG: present step 1 pre-copy idx=%u waits=%zu (sync)",
            presentIdx, gameRenderSemaphores.size());

    pass.preCopySemaphores.at(1) = Mini::Semaphore(info.device);
    pass.preCopyBuf = Mini::CommandBuffer(info.device, this->cmdPool);
    pass.preCopyBuf.begin();
    Utils::copyImage(pass.preCopyBuf.handle(),
        this->swapchainImages.at(presentIdx),
        this->frameIdx % 2 == 0 ? this->frame_0.handle() : this->frame_1.handle(),
        this->extent.width, this->extent.height,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        true, false);
    pass.preCopyBuf.end();

    std::vector<VkSemaphore> copyWaits = gameRenderSemaphores;
    if (this->frameIdx > 0)
        copyWaits.emplace_back(this->passInfos.at((this->frameIdx - 1) % 8)
            .preCopySemaphores.at(1).handle());

    Layer::ovkResetFences(info.device, 1, &preFence);
    pass.preCopyBuf.submit(queue, copyWaits,
        { pass.preCopySemaphores.at(1).handle() }, preFence);

    auto res = presentGenFramesAndroid(info, pNext, queue, presentIdx, slot, preFence);
    this->frameIdx++;
    return res;
}
#endif /* __ANDROID__ */

VkResult LsContext::present(const Hooks::DeviceInfo& info, const void* pNext, VkQueue queue,
        const std::vector<VkSemaphore>& gameRenderSemaphores, uint32_t presentIdx) {
    const auto& conf = Config::activeConf;
#ifndef __ANDROID__
    auto& pass = this->passInfos.at(this->frameIdx % 8);
#else
    (void)conf;
    if (Config::activeConf.asyncPresent)
        return presentAsyncAndroid(info, pNext, queue, gameRenderSemaphores, presentIdx);
    return presentSyncAndroid(info, pNext, queue, gameRenderSemaphores, presentIdx);
#endif

#ifndef __ANDROID__
    // Desktop Linux path: OPAQUE_FD semaphore-based synchronization

    // 1. copy swapchain image to frame_0/frame_1
    int preCopySemaphoreFd{};
    pass.preCopySemaphores.at(0) = Mini::Semaphore(info.device, &preCopySemaphoreFd);
    pass.preCopySemaphores.at(1) = Mini::Semaphore(info.device);
    pass.preCopyBuf = Mini::CommandBuffer(info.device, this->cmdPool);
    pass.preCopyBuf.begin();

    Utils::copyImage(pass.preCopyBuf.handle(),
        this->swapchainImages.at(presentIdx),
        this->frameIdx % 2 == 0 ? this->frame_0.handle() : this->frame_1.handle(),
        this->extent.width, this->extent.height,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        true, false);

    pass.preCopyBuf.end();

    std::vector<VkSemaphore> gameRenderSemaphores2 = gameRenderSemaphores;
    if (this->frameIdx > 0)
        gameRenderSemaphores2.emplace_back(this->passInfos.at((this->frameIdx - 1) % 8)
            .preCopySemaphores.at(1).handle());
    pass.preCopyBuf.submit(info.queue.second,
        gameRenderSemaphores2,
        { pass.preCopySemaphores.at(0).handle(),
          pass.preCopySemaphores.at(1).handle() });

    // 2. render intermediary frames
    std::vector<int> renderSemaphoreFds(conf.multiplier - 1);
    for (size_t i = 0; i < (conf.multiplier - 1); ++i)
        pass.renderSemaphores.at(i) = Mini::Semaphore(info.device, &renderSemaphoreFds.at(i));

    if (conf.performance)
        LSFG_3_1P::presentContext(*this->lsfgCtxId,
            preCopySemaphoreFd,
            renderSemaphoreFds);
    else
        LSFG_3_1::presentContext(*this->lsfgCtxId,
            preCopySemaphoreFd,
            renderSemaphoreFds);

    for (size_t i = 0; i < (conf.multiplier - 1); i++) {
        // 3. acquire next swapchain image
        pass.acquireSemaphores.at(i) = Mini::Semaphore(info.device);
        uint32_t imageIdx{};
        auto res = Layer::ovkAcquireNextImageKHR(info.device, this->swapchain, UINT64_MAX,
            pass.acquireSemaphores.at(i).handle(), VK_NULL_HANDLE, &imageIdx);
        if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR)
            throw LSFG::vulkan_error(res, "Failed to acquire next swapchain image");

        // 4. copy output image to swapchain image
        pass.postCopySemaphores.at(i) = Mini::Semaphore(info.device);
        pass.prevPostCopySemaphores.at(i) = Mini::Semaphore(info.device);
        pass.postCopyBufs.at(i) = Mini::CommandBuffer(info.device, this->cmdPool);
        pass.postCopyBufs.at(i).begin();

        Utils::copyImage(pass.postCopyBufs.at(i).handle(),
            this->out_n.at(i).handle(),
            this->swapchainImages.at(imageIdx),
            this->extent.width, this->extent.height,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            false, true);

        pass.postCopyBufs.at(i).end();
        pass.postCopyBufs.at(i).submit(info.queue.second,
            { pass.acquireSemaphores.at(i).handle(),
              pass.renderSemaphores.at(i).handle() },
            { pass.postCopySemaphores.at(i).handle(),
              pass.prevPostCopySemaphores.at(i).handle() });

        // 5. present swapchain image
        std::vector<VkSemaphore> waitSemaphores{ pass.postCopySemaphores.at(i).handle() };
        if (i != 0) waitSemaphores.emplace_back(pass.prevPostCopySemaphores.at(i - 1).handle());

        const VkPresentInfoKHR presentInfo{
            .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
            .pNext = i == 0 ? pNext : nullptr, // only set on first present
            .waitSemaphoreCount = static_cast<uint32_t>(waitSemaphores.size()),
            .pWaitSemaphores = waitSemaphores.data(),
            .swapchainCount = 1,
            .pSwapchains = &this->swapchain,
            .pImageIndices = &imageIdx,
        };
        res = Layer::ovkQueuePresentKHR(queue, &presentInfo);
        if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR)
            throw LSFG::vulkan_error(res, "Failed to present swapchain image");
    }

    // 6. present actual next frame
    VkSemaphore lastPrevPostCopySemaphore =
        pass.prevPostCopySemaphores.at(conf.multiplier - 1 - 1).handle();
    const VkPresentInfoKHR presentInfo{
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &lastPrevPostCopySemaphore,
        .swapchainCount = 1,
        .pSwapchains = &this->swapchain,
        .pImageIndices = &presentIdx,
    };
    auto res = Layer::ovkQueuePresentKHR(queue, &presentInfo);
    if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR)
        throw LSFG::vulkan_error(res, "Failed to present swapchain image");

    this->frameIdx++;
    return res;
#endif
}
