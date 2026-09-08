#pragma once
#include <cstddef>
#include <cstdint>

namespace lsfg_android {
struct FrameSample {
    uint32_t sourceWidth = 0, sourceHeight = 0, hash = 0;
    bool valid = false;
};

// Sample only the small content hash used by the real-frame FPS counter.
inline FrameSample sampleFrame(const uint8_t* rgba, uint32_t w, uint32_t h, size_t stride) {
    FrameSample out;
    if (!rgba || !w || !h || stride < size_t(w)*4) return out;
    out.sourceWidth = w; out.sourceHeight = h;
    uint32_t hash = 0x811c9dc5u;
    for (uint32_t y = 0; y < 8; ++y) for (uint32_t x = 0; x < 8; ++x) {
        const uint8_t* p = rgba+(uint64_t(y)*h/8)*stride+(uint64_t(x)*w/8)*4;
        hash = (hash ^ ((77u*p[0]+150u*p[1]+29u*p[2])>>10))*0x01000193u;
    }
    out.hash = hash ? hash : 1; out.valid = true;
    return out;
}

}
