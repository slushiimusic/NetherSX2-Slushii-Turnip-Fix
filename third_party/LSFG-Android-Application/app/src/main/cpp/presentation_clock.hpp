#pragma once
#include <algorithm>
#include <cstdint>

// Absolute output slots survive variable copy/compute costs between pairs.
// Missed deadlines rebase to now; they must never cause a catch-up burst.
class PresentationClock {
public:
    int64_t reserve(int64_t nowNs, int64_t intervalNs) {
        intervalNs = std::max<int64_t>(1, intervalNs);
        if (!nextNs_ || !intervalNs_ ||
                intervalNs > intervalNs_*3/2 || intervalNs*3/2 < intervalNs_)
            nextNs_ = nowNs;
        const int64_t deadline = std::max(nowNs, nextNs_);
        nextNs_ = deadline + intervalNs;
        intervalNs_ = intervalNs;
        return deadline;
    }
private:
    int64_t nextNs_ = 0, intervalNs_ = 0;
};
