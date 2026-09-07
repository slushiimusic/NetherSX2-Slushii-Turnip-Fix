#include CONTEXT_HEADER
#include <iostream>
int main() {
    using namespace TEST_NAMESPACE;
    for(unsigned multiplier : {1u,2u,3u}) {
        Vulkan vk; vk.generationCount=multiplier;
        Context ctx(vk,0,1,std::vector<int>(multiplier),{64,36},0);
        std::array<unsigned,8> pending{};
        for(unsigned frame=0;frame<96;++frame) {
            // Every ring slot changes between full synthesis and history-only,
            // including consecutive skipped pairs and a long fallback run.
            const bool historyOnly=(frame/8)%3==1 || frame%5==0;
            Boundary::calls.fill(0); Boundary::submits=0; Boundary::fenceWaits=0;
            Boundary::expectedFrame=frame;
            ctx.present(vk,-1,{},historyOnly);
            assert(Boundary::calls[0]==1 && Boundary::calls[1]==7 && Boundary::calls[2]==1);
            const unsigned outputs=historyOnly?0:multiplier;
            assert(Boundary::calls[3]==7*outputs);
            assert(Boundary::calls[4]==3*outputs);
            assert(Boundary::calls[5]==outputs);
            assert(Boundary::submits==(historyOnly?1:1+multiplier));
            assert(Boundary::fenceWaits==pending[frame%8]);
            pending[frame%8]=historyOnly?1:multiplier;
        }
    }
    std::cout<<"PASS: real Context history/synthesis transitions, three-capture indexing, "
                "8-slot fence reuse and 1/2/3 generated outputs\n";
}
