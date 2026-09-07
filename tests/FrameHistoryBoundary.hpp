#pragma once
// Host doubles for GPU/shader boundaries; the test compiles the real Context
// implementation to exercise its submissions, slot reuse and temporal indexing.
#include <cassert>
#include <cstdint>
#include <vector>
#include <array>
#include <optional>
#include <memory>
#include <stdexcept>
using VkFormat=int;
struct VkExtent2D { uint32_t width,height; };
constexpr int VK_IMAGE_USAGE_STORAGE_BIT=1, VK_IMAGE_USAGE_SAMPLED_BIT=2;
constexpr int VK_IMAGE_ASPECT_COLOR_BIT=1, VK_TIMEOUT=2;
namespace Boundary {
inline std::array<unsigned,6> calls{};
inline uint64_t expectedFrame=0;
inline unsigned submits=0, fenceWaits=0;
}
namespace LSFG {
struct vulkan_error : std::runtime_error {
    vulkan_error(int,const char* s):std::runtime_error(s){}
};
namespace Core {
struct Device { int getComputeQueue() const { return 0; } };
struct Image {
    Image()=default;
    template<class... A> Image(A&&...) {}
};
struct Semaphore {
    std::shared_ptr<bool> signaled;
    Semaphore()=default;
    explicit Semaphore(Device&):signaled(std::make_shared<bool>(false)){}
    Semaphore(Device&,int):signaled(std::make_shared<bool>(true)){}
};
struct Fence {
    std::shared_ptr<bool> signaled;
    Fence()=default;
    explicit Fence(Device&):signaled(std::make_shared<bool>(false)){}
    bool wait(Device&,uint64_t) {
        ++Boundary::fenceWaits;
        assert(signaled && *signaled); // never wait on an unsubmitted fence
        return true;
    }
};
struct CommandBuffer {
    CommandBuffer()=default;
    CommandBuffer(Device&,int){}
    void begin(){} void end(){}
    void submit(int,std::optional<Fence> fence,const std::vector<Semaphore>& waits,
                std::nullopt_t,const std::vector<Semaphore>& signals,std::nullopt_t) {
        ++Boundary::submits;
        for(auto& s:waits) { assert(s.signaled && *s.signaled); *s.signaled=false; }
        for(auto& s:signals) { assert(s.signaled && !*s.signaled); *s.signaled=true; }
        if(fence) { assert(fence->signaled && !*fence->signaled); *fence->signaled=true; }
    }
};
}
struct TestVulkan { Core::Device device; int commandPool=0; size_t generationCount=1; };
template<unsigned Kind> struct TestShader {
    std::vector<Core::Image> images=std::vector<Core::Image>(7);
    TestShader()=default;
    template<class... A> TestShader(A&&...) {}
    const std::vector<Core::Image>& getOutImages() const { return images; }
    Core::Image getOutImage() const { return {}; }
    Core::Image getOutImage1() const { return {}; }
    Core::Image getOutImage2() const { return {}; }
    void Dispatch(Core::CommandBuffer&,uint64_t frame,uint64_t=0) {
        assert(frame==Boundary::expectedFrame); ++Boundary::calls[Kind];
    }
};
}
#define HISTORY_NAMESPACE(NS) namespace NS { \
using Vulkan=LSFG::TestVulkan; namespace Shaders { \
using Mipmaps=LSFG::TestShader<0>; using Alpha=LSFG::TestShader<1>; \
using Beta=LSFG::TestShader<2>; using Gamma=LSFG::TestShader<3>; \
using Delta=LSFG::TestShader<4>; using Generate=LSFG::TestShader<5>; } }
HISTORY_NAMESPACE(LSFG_3_1)
HISTORY_NAMESPACE(LSFG_3_1P)
