#pragma once
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdlib>

namespace lsfg_android {
// Detect localized, high-contrast changes without changing the optical-flow scale.
// Two adjacent risky tiles reject large moving structures while a small HUD change
// or a uniform brightness change can continue through interpolation.
inline bool riskyFramePair(const uint8_t* prev, const uint8_t* cur,
                          uint32_t width, uint32_t height,
                          size_t prevStride, size_t curStride) {
    if (!prev || !cur || !width || !height) return false;
    constexpr unsigned gxCount=64, gyCount=36, tileW=16, tileH=12;
    constexpr unsigned cols=4, rows=3, perTile=tileW*tileH;
    std::array<unsigned, cols*rows> delta{}, changed{}, edges{}, verticalEdges{};
    std::array<int, gxCount> aboveA{}, aboveB{};
    unsigned total=0;
    for (unsigned gy=0;gy<gyCount;++gy) {
        const size_t y=std::min<size_t>((uint64_t(gy)*height)/gyCount,height-1);
        int lastA=0,lastB=0;
        for (unsigned gx=0;gx<gxCount;++gx) {
            const size_t x=std::min<size_t>((uint64_t(gx)*width)/gxCount,width-1);
            const auto* a=prev+y*prevStride+x*4;
            const auto* b=cur+y*curStride+x*4;
            const int la=(77*a[0]+150*a[1]+29*a[2])>>8;
            const int lb=(77*b[0]+150*b[1]+29*b[2])>>8;
            const unsigned d=std::abs(lb-la), tile=(gy/tileH)*cols+gx/tileW;
            delta[tile]+=d; total+=d;
            if(d>=24) ++changed[tile];
            if(gx%tileW && (std::abs(la-lastA)>=40 || std::abs(lb-lastB)>=40)) ++edges[tile];
            if(gy%tileH && (std::abs(la-aboveA[gx])>=40 || std::abs(lb-aboveB[gx])>=40))
                ++verticalEdges[tile];
            aboveA[gx]=la; aboveB[gx]=lb;
            lastA=la;lastB=lb;
        }
    }
    if(total >= 26*gxCount*gyCount) return true; // retain the scene-cut guard
    std::array<bool,cols*rows> risky{};
    for(unsigned i=0;i<risky.size();++i) {
        // A single moving window grid can confuse flow even when most of the
        // screen is sky. Require dense edges on BOTH axes, so a roof line,
        // exposure change or small HUD badge alone does not trigger this path.
        if(delta[i]>=8*perTile && changed[i]>=perTile/8 &&
                edges[i]>=perTile/6 && verticalEdges[i]>=perTile/6) return true;
        risky[i]=delta[i]>=18*perTile && changed[i]>=perTile/4 && edges[i]>=perTile/8;
    }
    for(unsigned y=0;y<rows;++y) for(unsigned x=0;x<cols;++x) {
        const unsigned i=y*cols+x;
        if(risky[i] && ((x+1<cols && risky[i+1]) || (y+1<rows && risky[i+cols]))) return true;
    }
    return false;
}

class ArtifactRecovery {
    unsigned cleanPairs_ = 2;
public:
    bool suppress(bool enabled, bool risky) {
        if (!enabled) { cleanPairs_=2; return false; }
        if (risky) { cleanPairs_=0; return true; }
        if (cleanPairs_<2) ++cleanPairs_;
        // LSFG's beta input spans three captures. Wait for two clean pairs
        // so rejected motion has left that history before synthesizing again.
        return cleanPairs_<2;
    }
};
}
