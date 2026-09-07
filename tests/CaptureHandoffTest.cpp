#include "../turnip-shim/capture_slots.hpp"
#include "../third_party/LSFG-Android-Application/app/src/main/cpp/capture_use_tracker.hpp"
#include <atomic>
#include <cassert>
#include <iostream>
#include <thread>

int main() {
    CaptureUseTracker uses;
    int buffer = 0;
    CaptureSlot slot{true, false, 100};
    assert(!slot.reusable(false, false)); // producer copy still running
    assert(!slot.reusable(true, false));  // complete, but not published yet
    slot.published = true;
    uses.acquire(&buffer); // pending queue reference
    assert(!slot.reusable(true, uses.inUse(&buffer)));
    // Moving a queue entry onto the worker does not release its lease. Model a
    // delayed GPU copy while the producer repeatedly considers reusing it.
    std::atomic<bool> reading{false}, finish{false};
    std::thread worker([&] {
        reading.store(true);
        while (!finish.load()) std::this_thread::yield();
        uses.release(&buffer);
    });
    while (!reading.load()) std::this_thread::yield();
    for (int i = 0; i < 10000; ++i)
        assert(!slot.reusable(true, uses.inUse(&buffer)));
    finish.store(true);
    worker.join();
    assert(slot.reusable(true, uses.inUse(&buffer)));
    assert(!slot.reusable(false, false)); // pause cannot waive GPU completion

    uses.acquire(&buffer);
    uses.acquire(&buffer);
    uses.release(&buffer); // dropping one queued duplicate must retain worker use
    assert(uses.inUse(&buffer));
    uses.release(&buffer); // shutdown / failed import returns its lease too
    assert(!uses.inUse(&buffer));

    CaptureSlot pool[4] = {{true, false, 300}, {true, false, 100},
                           {true, false, 200}, {}};
    assert(oldestUnpublishedCapture(pool) == 1);
    pool[1].published = true;
    assert(oldestUnpublishedCapture(pool) == 2);
    pool[2].published = true;
    assert(oldestUnpublishedCapture(pool) == 0);
    pool[0].published = true;
    assert(oldestUnpublishedCapture(pool) == -1);
    assert(pool[3].reusable(false, false)); // unused fourth buffer never waits
    std::cout << "PASS: producer completion, active GPU reader, duplicate leases, "
                 "pause safety, ordered publication and spare capture slot\n";
}
