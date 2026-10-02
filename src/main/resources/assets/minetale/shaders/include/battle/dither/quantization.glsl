#ifndef MINETALE_QUANTIZATION_DITHER_GLSL
#define MINETALE_QUANTIZATION_DITHER_GLSL

float minetaleBlueNoise64(sampler2D noiseSampler, vec2 fragmentCoordinate) {
    ivec2 noiseCoordinate = ivec2(floor(fragmentCoordinate)) & ivec2(63);
    return texelFetch(noiseSampler, noiseCoordinate, 0).r;
}

float minetaleInterleavedGradientNoise(vec2 fragmentCoordinate) {
    vec2 pixel = floor(fragmentCoordinate);
    return fract(
            52.9829189
            * fract(dot(pixel, vec2(0.06711056, 0.00583715)))
    );
}

float minetaleQuantizationDitherOffset(
        float unitNoise,
        float quantizationStep,
        float strength
) {
    return (unitNoise - 0.5) * quantizationStep * strength;
}

vec3 minetaleApplyQuantizationDither(
        vec3 value,
        float unitNoise,
        float quantizationStep,
        float strength
) {
    // RGB 共用同一标量误差，保持色相并避免暗部出现彩色噪点。
    return value + vec3(minetaleQuantizationDitherOffset(
            unitNoise,
            quantizationStep,
            strength
    ));
}

#endif
