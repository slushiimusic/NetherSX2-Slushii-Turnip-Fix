// CPU-side FSR1 constant generation (AMD FidelityFX FSR 1.x, MIT license).
#include <stdint.h>
#include <math.h>

#define A_CPU 1
#include "vendor/ffx_a.h"
#define FSR_EASU_F 1
#define FSR_RCAS_F 1
#include "vendor/ffx_fsr1.h"

extern "C" void fsr_cpu_populate_easu(uint32_t con[4][4],
                                      float input_w, float input_h,
                                      float output_w, float output_h) {
    AU1 c0[4], c1[4], c2[4], c3[4];
    FsrEasuCon(c0, c1, c2, c3,
               input_w, input_h,
               input_w, input_h,
               output_w, output_h);
    for (int i = 0; i < 4; i++) {
        con[0][i] = c0[i];
        con[1][i] = c1[i];
        con[2][i] = c2[i];
        con[3][i] = c3[i];
    }
}

extern "C" void fsr_cpu_populate_rcas(uint32_t con[4], float sharpness) {
    AU1 c[4];
    /* FsrRcasCon's argument is stops of sharpness *reduction*, not sharpness:
     * ffx_fsr1.h:667 does sharpness = AExp2F1(-sharpness), so 0 stops is
     * maximum sharpening and each stop halves it. Our config slider runs the
     * other way (0 = soft, 1 = sharp), so it has to be inverted first.
     * Passing it straight through made a higher slider value LESS sharp. */
    float stops = (1.0f - sharpness) * 2.0f;
    FsrRcasCon(c, stops);
    for (int i = 0; i < 4; i++)
        con[i] = c[i];
}
