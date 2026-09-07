#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

// Observe EVERY incoming capture before queue eviction. Measuring consumed
// frames instead creates positive feedback: a slow worker sees a larger gap,
// sleeps longer, drops more frames and eventually collapses to ~1 fps.
class SourceCadence {
    int64_t previousNs_=0;
    double estimateNs_=16'666'667.0;
    unsigned outliers_=0;
    bool learned_=false;
public:
    void reset() { *this=SourceCadence{}; }
    int64_t observe(int64_t timestampNs, double alpha=0.125, double ratio=4.0) {
        if (timestampNs<=0 || timestampNs<=previousNs_) return intervalNs();
        const int64_t previous=previousNs_;
        previousNs_=timestampNs;
        if (!previous) return intervalNs();
        const double delta=std::clamp<double>(timestampNs-previous,8'000'000,1'000'000'000);
        alpha=std::clamp(alpha,0.05,0.5); ratio=std::clamp(ratio,2.0,8.0);
        if (!learned_ && delta<=100'000'000) {
            estimateNs_=delta; learned_=true; outliers_=0;
        } else if (delta>estimateNs_*ratio || delta*ratio<estimateNs_) {
            // One initialization/pause gap is not a cadence. A genuine slow
            // source still converges after three consecutive incoming gaps.
            if (++outliers_>=3) { estimateNs_=delta; learned_=true; outliers_=0; }
        } else {
            estimateNs_+=alpha*(delta-estimateNs_); outliers_=0; learned_=true;
        }
        return intervalNs();
    }
    int64_t intervalNs() const { return static_cast<int64_t>(std::llround(estimateNs_)); }
};
