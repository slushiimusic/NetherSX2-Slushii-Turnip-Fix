#pragma once
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>

namespace lsfg_android {
// Reset history only for widespread, abrupt changes in scene content. Moving
// window grids can change every pixel while keeping a similar color palette.
// Edge density and localized movement alone must not stop interpolation.
inline bool riskyFramePair(const uint8_t* prev, const uint8_t* cur,
                          uint32_t width, uint32_t height,
                          size_t prevStride, size_t curStride) {
    if (!prev || !cur || !width || !height ||
            prevStride < size_t(width)*4 || curStride < size_t(width)*4) return false;
    constexpr unsigned gxCount=64, gyCount=36, count=gxCount*gyCount;
    std::array<unsigned,64> prevColors{}, curColors{};
    unsigned totalDelta=0, changed=0;
    for (unsigned gy=0;gy<gyCount;++gy) {
        const size_t y=std::min<size_t>((uint64_t(gy)*height)/gyCount,height-1);
        for (unsigned gx=0;gx<gxCount;++gx) {
            const size_t x=std::min<size_t>((uint64_t(gx)*width)/gxCount,width-1);
            const auto* a=prev+y*prevStride+x*4;
            const auto* b=cur+y*curStride+x*4;
            unsigned delta=0;
            for (unsigned c=0;c<3;++c) delta+=std::abs(int(b[c])-int(a[c]));
            totalDelta+=delta;
            if (delta>=3*48) ++changed;
            // Coarse RGB bins tolerate texture motion and small exposure shifts.
            ++prevColors[((a[0]>>6)<<4)|((a[1]>>6)<<2)|(a[2]>>6)];
            ++curColors[((b[0]>>6)<<4)|((b[1]>>6)<<2)|(b[2]>>6)];
        }
    }
    if (totalDelta < 3*48*count || changed*5 < count*4) return false;
    unsigned paletteDelta=0;
    for (unsigned i=0;i<prevColors.size();++i)
        paletteDelta+=std::abs(int(curColors[i])-int(prevColors[i]));
    // Total-variation distance >= 60%, plus large changes across >= 80% of
    // samples. Favor continuous motion over rejecting ambiguous cuts that
    // share a similar palette.
    return paletteDelta*5 >= count*6;
}

class ArtifactRecovery {
    unsigned cleanPairs_ = 2;
public:
    bool suppress(bool enabled, bool risky) {
        if (!enabled) { cleanPairs_=2; return false; }
        if (risky) { cleanPairs_=0; return true; }
        if (cleanPairs_<2) ++cleanPairs_;
        // LSFG's beta input spans three captures. Two pairs without a scene
        // change flush that history; those pairs can contain camera motion.
        return cleanPairs_<2;
    }
};
}
