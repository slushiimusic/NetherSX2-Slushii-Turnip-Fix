#include "../third_party/LSFG-Android-Application/app/src/main/cpp/artifact_guard.hpp"
#include <cassert>
#include <iostream>
#include <vector>
int main() {
    constexpr unsigned w=64,h=36,stride=w*4+16;
    std::vector<uint8_t> a(stride*h,100),b=a;
    auto risky=[&]{return lsfg_android::riskyFramePair(a.data(),b.data(),w,h,stride,stride);};
    assert(!risky());
    for(unsigned y=0;y<h;++y)for(unsigned x=0;x<w;++x)
        for(unsigned c=0;c<3;++c)b[y*stride+x*4+c]=106;
    assert(!risky()); // mild uniform exposure change
    b=a;
    for(unsigned y=0;y<12;++y)for(unsigned x=0;x<32;++x)
        for(unsigned c=0;c<3;++c) {
            a[y*stride+x*4+c]=(x%2)?130:70;
            b[y*stride+x*4+c]=(x%2)?70:130;
        }
    // Moving window pattern: local delta=60, global delta=10 (old threshold misses it).
    assert(risky());
    b=a; assert(!risky()); // stationary fine geometry must retain interpolation
    for(unsigned y=0;y<12;++y)for(unsigned x=0;x<16;++x)
        for(unsigned c=0;c<3;++c)b[y*stride+x*4+c]=200-a[y*stride+x*4+c];
    assert(!risky()); // one small changing HUD tile is insufficient
    a.assign(stride*h,100); b=a;
    // One facade occupies <9% of the screen. Both window axes move, with a
    // mean change too small for the old two-tile or scene-cut tests.
    for(unsigned y=12;y<24;++y)for(unsigned x=16;x<32;++x)
        for(unsigned c=0;c<3;++c) {
            a[y*stride+x*4+c]=((x/2+y/2)%2)?130:70;
            b[y*stride+x*4+c]=(((x+1)/2+y/2)%2)?130:70;
        }
    assert(risky());
    b=a; assert(!risky());
    lsfg_android::ArtifactRecovery recovery;
    assert(!recovery.suppress(true,false));
    for (int i=0;i<12;++i) {
        assert(recovery.suppress(true,true));
        assert(recovery.suppress(true,false)); // no alternating generated flash
    }
    assert(!recovery.suppress(true,false)); // two clean pairs restore generation
    assert(recovery.suppress(true,true));
    assert(!recovery.suppress(false,true)); // guard setting still respected
    assert(!recovery.suppress(true,false));
    std::fill(a.begin(),a.end(),0);std::fill(b.begin(),b.end(),255);
    assert(risky()); // retain existing scene-cut rejection
    assert(!lsfg_android::riskyFramePair(nullptr,b.data(),w,h,stride,stride));
    std::cout<<"PASS: single-facade moving windows, recovery history, stable geometry, small HUD, exposure, scene cut, stride and empty input\n";
}
