#pragma once
#include <array>
#include <cstdint>
#include <cstring>
#include "artifact_guard.hpp"

namespace lsfg_android {
struct FrameSample {
    static constexpr uint32_t width = 64, height = 36;
    std::array<uint8_t, width * height * 4> pixels{};
    uint32_t sourceWidth = 0, sourceHeight = 0, hash = 0;
    bool valid = false;
};

// Cache the same grid used by riskyFramePair; never remap the previous GPU
// buffer just to read those pixels again. The hash shares this one CPU mapping.
inline FrameSample sampleFrame(const uint8_t* rgba, uint32_t w, uint32_t h, size_t stride) {
    FrameSample out;
    if (!rgba || !w || !h || stride < size_t(w)*4) return out;
    out.sourceWidth = w; out.sourceHeight = h;
    for (uint32_t y = 0; y < out.height; ++y)
        for (uint32_t x = 0; x < out.width; ++x)
            std::memcpy(out.pixels.data()+(y*out.width+x)*4,
                rgba+(uint64_t(y)*h/out.height)*stride+(uint64_t(x)*w/out.width)*4, 4);
    uint32_t hash = 0x811c9dc5u;
    for (uint32_t y = 0; y < 8; ++y) for (uint32_t x = 0; x < 8; ++x) {
        const uint8_t* p = rgba+(uint64_t(y)*h/8)*stride+(uint64_t(x)*w/8)*4;
        hash = (hash ^ ((77u*p[0]+150u*p[1]+29u*p[2])>>10))*0x01000193u;
    }
    out.hash = hash ? hash : 1; out.valid = true;
    return out;
}

inline bool riskySamples(const FrameSample& prev, const FrameSample& cur) {
    if (!prev.valid || !cur.valid) return false;
    if (prev.sourceWidth != cur.sourceWidth || prev.sourceHeight != cur.sourceHeight) return true;
    return riskyFramePair(prev.pixels.data(), cur.pixels.data(),
        FrameSample::width, FrameSample::height, FrameSample::width*4, FrameSample::width*4);
}
}
