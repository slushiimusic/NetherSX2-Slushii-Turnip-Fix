#pragma once
#include <cstddef>
#include <cstdint>

struct CaptureSlot {
    bool submitted = false;
    bool published = false;
    int64_t timestampNs = 0;

    bool reusable(bool gpuComplete, bool consumerBusy) const {
        return !consumerBusy && (!submitted || (gpuComplete && published));
    }
};

// Preserve submission order even if a later fence is observed ready first.
template<std::size_t N>
int oldestUnpublishedCapture(const CaptureSlot (&slots)[N]) {
    int oldest = -1;
    for (std::size_t i = 0; i < N; ++i) {
        if (!slots[i].submitted || slots[i].published) continue;
        if (oldest < 0 || slots[i].timestampNs < slots[oldest].timestampNs)
            oldest = static_cast<int>(i);
    }
    return oldest;
}
