#pragma once
#include <mutex>
#include <unordered_map>

// AHardwareBuffer_acquire protects allocation lifetime, not the pixels. Keep
// a separate lease from enqueue through completion of the consumer GPU copy.
class CaptureUseTracker {
public:
    void acquire(const void* buffer) {
        std::lock_guard<std::mutex> lock(mu_);
        ++uses_[buffer];
    }
    void release(const void* buffer) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = uses_.find(buffer);
        if (it != uses_.end() && --it->second == 0) uses_.erase(it);
    }
    bool inUse(const void* buffer) const {
        std::lock_guard<std::mutex> lock(mu_);
        return uses_.find(buffer) != uses_.end();
    }
private:
    mutable std::mutex mu_;
    std::unordered_map<const void*, unsigned> uses_;
};
