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
    // Adjacent moving window tiles must keep framegen active.
    assert(!risky());
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
    assert(!risky()); // a local facade must not reject the whole frame
    b=a; assert(!risky());
    // Full-screen grid motion changes every pixel by 160, but keeps its palette.
    for(unsigned y=0;y<h;++y)for(unsigned x=0;x<w;++x)
        for(unsigned c=0;c<3;++c) {
            a[y*stride+x*4+c]=((x+y)%2)?210:50;
            b[y*stride+x*4+c]=((x+y)%2)?50:210;
        }
    assert(!risky());
    auto pan=[&](std::vector<uint8_t>& frame,unsigned dx,unsigned dy,bool bright) {
        for(unsigned y=0;y<h;++y)for(unsigned x=0;x<w;++x)
            for(unsigned c=0;c<3;++c) {
                unsigned wx=(x+dx)%w,wy=(y+dy)%h;
                frame[y*stride+x*4+c]=(bright?170:20)+(wx*17+wy*29+c*7+(wx*wy)%31)%60;
            }
    };
    lsfg_android::ArtifactRecovery recovery;
    for(unsigned speed : {1u,2u,5u,13u}) {
        pan(a,0,0,false);
        for(unsigned frame=1;frame<=240;++frame) {
            pan(b,frame*speed,frame*(speed/2+1),false);
            assert(!recovery.suppress(true,risky())); // no stationary frames needed
            a.swap(b);
        }
    }
    pan(b,0,0,true);
    assert(risky()); // abrupt whole-scene palette change still resets history
    assert(recovery.suppress(true,risky()));
    a.swap(b);pan(b,1,1,true);
    assert(!risky());assert(recovery.suppress(true,risky()));
    a.swap(b);pan(b,2,2,true);
    assert(!recovery.suppress(true,risky())); // resumes during continued movement
    assert(!recovery.suppress(false,true)); // explicit guard OFF is respected
    assert(!recovery.suppress(true,false));
    std::fill(a.begin(),a.end(),0);std::fill(b.begin(),b.end(),255);
    assert(risky()); // retain existing scene-cut rejection
    assert(!lsfg_android::riskyFramePair(nullptr,b.data(),w,h,stride,stride));
    assert(!lsfg_android::riskyFramePair(a.data(),b.data(),w,h,1,stride));
    std::cout<<"PASS: continuous pans, moving windows, HUD/exposure, abrupt scene changes, recovery during motion, stride and empty input\n";
}
