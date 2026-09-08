#include "../third_party/LSFG-Android-Application/app/src/main/cpp/frame_sample.hpp"
#include "../third_party/LSFG-Android-Application/app/src/main/cpp/presentation_clock.hpp"
#include "../third_party/LSFG-Android-Application/app/src/main/cpp/source_cadence.hpp"
#include <cassert>
#include <iostream>
#include <vector>
int main() {
    using namespace lsfg_android;
    SourceCadence source;
    // Actual Thor boot timestamps: capture 2 followed a 4.136-second init gap.
    const int64_t boot[]={218950020457204,218954157080328,218954173207463,
        218954189846994,218954206425796,218954223228505,218954239780796};
    for(auto t:boot) assert(source.observe(t)<20'000'000);
    // A one-slot queue may drop 29/30 frames while the GPU stalls. Source
    // cadence must remain 30 fps regardless of which capture the worker consumes.
    int64_t incoming=boot[6], pendingInterval=0;
    for(int i=0;i<900;++i) {
        incoming+=33'333'333;
        pendingInterval=source.observe(incoming);
        if(i>60 && i%30==0) assert(pendingInterval>33'000'000 && pendingInterval<33'400'000);
    }
    auto steady=source.intervalNs();
    assert(source.observe(incoming+5'000'000'000)==steady); // pause is not slow motion
    incoming+=5'000'000'000;
    for(int i=0;i<60;++i) source.observe(incoming+=(16'666'667));
    assert(source.intervalNs()>16'600'000 && source.intervalNs()<16'800'000); // real 30->60 change
    assert(source.observe(incoming-100)==source.intervalNs()); // stale capture cannot rewind
    source.reset(); incoming=1;
    for(int i=0;i<8;++i) source.observe(incoming+=1'000'000'000);
    assert(source.intervalNs()==1'000'000'000); // genuine low-rate sources still supported
    for (unsigned w : {31u,64u,512u,1024u}) {
        const unsigned h=w*7/8+1, stride=w*4+32;
        std::vector<uint8_t> a(stride*h), b(stride*h);
        for (size_t i=0;i<a.size();++i) { a[i]=(i*11+i/47)%256; b[i]=(i*13+i/59)%256; }
        for (unsigned shift : {0u,1u,12u,100u}) {
            for (size_t i=0;i<b.size();++i) b[i]=(a[i]+shift)&255;
            const auto sa=sampleFrame(a.data(),w,h,stride), sb=sampleFrame(b.data(),w,h,stride);
            assert(sa.valid && sb.valid);
            if (!shift) assert(sa.hash==sb.hash);
        }
    }
    assert(!sampleFrame(nullptr,64,36,256).valid);
    assert(!sampleFrame(reinterpret_cast<const uint8_t*>(1),64,36,100).valid);
    PresentationClock pacer;
    constexpr int64_t slot=8333333;
    int64_t prev=-slot, now=1000000;
    for (int i=0;i<1200;++i) {
        // Alternating cheap/expensive compute and late arrivals cannot bunch posts.
        if (!(i%2)) now += (i%10==0 ? 11000000 : 3000000);
        auto deadline=pacer.reserve(now,slot);
        assert(deadline>=now && deadline-prev>=slot);
        prev=deadline; now=deadline+500000;
    }
    const auto afterPause=pacer.reserve(now+2000000000,slot);
    assert(afterPause==now+2000000000);
    assert(pacer.reserve(afterPause,slot)==afterPause+slot); // no catch-up burst
    PresentationClock stable;
    int64_t first=0,last=0;
    for (int i=0;i<120;++i) {
        last=stable.reserve(i*slot,slot);
        if (!i) first=last;
    }
    assert(last-first==119*slot);
    assert(stable.reserve(last+1000000,slot*2)==last+1000000); // 60 -> 30 source regime
    std::cout << "PASS: recorded Thor startup gap, queue-drop feedback prevention, source rate transitions, capture hash sampling, stride/size safety, hash stability, "
                 "variable compute, missed deadlines, pause recovery and 120-Hz output slots\n";
}
