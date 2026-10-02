#ifndef MINETALE_COVERAGE_DITHER_GLSL
#define MINETALE_COVERAGE_DITHER_GLSL

float minetaleOrderedCoverageThreshold4x4(vec2 pixel) {
    ivec2 cell = ivec2(mod(floor(pixel), 4.0));
    int index = cell.x + cell.y * 4;
    const float thresholds[16] = float[16](
         0.5,  8.5,  2.5, 10.5,
        12.5,  4.5, 14.5,  6.5,
         3.5, 11.5,  1.5,  9.5,
        15.5,  7.5, 13.5,  5.5
    );
    return thresholds[index] / 16.0;
}

void minetaleApplyOrderedCoverage4x4(float opacity, vec2 pixel) {
    if (opacity <= minetaleOrderedCoverageThreshold4x4(pixel)) discard;
}

#endif
