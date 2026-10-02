#ifndef MINETALE_MATH_GLSL
#define MINETALE_MATH_GLSL

float mtDivide(float a, float b) { return b == 0.0 ? 0.0 : a / b; }
vec3 mtDivide(vec3 a, vec3 b) {
    return vec3(mtDivide(a.x, b.x), mtDivide(a.y, b.y), mtDivide(a.z, b.z));
}
vec3 mtNormalize(vec3 v) { return dot(v, v) > 0.0 ? normalize(v) : vec3(0.0); }
float mtPower(float a, float b) {
    if (a == 0.0 && b < 0.0) return 0.0;
    if (a < 0.0) {
        if (fract(b) != 0.0) return 0.0;
        return pow(-a, b) * (mod(abs(b), 2.0) == 0.0 ? 1.0 : -1.0);
    }
    return pow(a, b);
}
float mtSmooth(float t) { return t * t * (3.0 - 2.0 * t); }
vec3 mtSmooth(vec3 t) { return t * t * (3.0 - 2.0 * t); }
float mtSmoother(float t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }
vec3 mtSmoother(vec3 t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

// 解析重建 Blender 257 点 Color Ramp 的相邻样本，保留端点附近的查表插值。
// 线性双端点仅需重建 factor，无需额外 palette 纹理。
float mtRampFactor(float f, float low, float high) {
    float index = clamp(f, 0.0, 1.0) * 256.0;
    float left = floor(index) / 256.0;
    vec2 factors = clamp((vec2(left, left + 1.0 / 256.0) - low) / (high - low), 0.0, 1.0);
    return mix(factors.x, factors.y, fract(index));
}

vec3 mtRgbToHsv(vec3 c) {
    float hi = max(max(c.r, c.g), c.b), lo = min(min(c.r, c.g), c.b);
    float range = hi - lo;
    if (range == 0.0) return vec3(0.0, 0.0, hi);
    float h = hi == c.r ? (c.g - c.b) / range :
              hi == c.g ? 2.0 + (c.b - c.r) / range : 4.0 + (c.r - c.g) / range;
    return vec3(fract(h / 6.0), hi == 0.0 ? 0.0 : range / hi, hi);
}
vec3 mtHsvToRgb(vec3 c) {
    vec3 hue = clamp(abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return mix(vec3(1.0), hue, c.y) * c.z;
}
vec3 mtRampHsv(float f, float low, float high, vec3 a, vec3 b) {
    float index = clamp(f, 0.0, 1.0) * 256.0;
    float left = floor(index) / 256.0;
    vec2 factors = clamp((vec2(left, left + 1.0 / 256.0) - low) / (high - low), 0.0, 1.0);
    return mix(mtHsvToRgb(mix(a, b, factors.x)), mtHsvToRgb(mix(a, b, factors.y)), fract(index));
}
vec3 mtMixMix(float f, vec3 a, vec3 b) { return mix(a, b, f); }
float mtMixMix(float f, float a, float b) { return mix(a, b, f); }
vec3 mtMixAdd(float f, vec3 a, vec3 b) { return a + f * b; }
vec3 mtMixMultiply(float f, vec3 a, vec3 b) { return a * mix(vec3(1.0), b, f); }
vec3 mtMixLinearLight(float f, vec3 a, vec3 b) { return a + f * (2.0 * b - 1.0); }
vec3 mtMixOverlay(float f, vec3 a, vec3 b) {
    vec3 light = 1.0 - (1.0 - f + 2.0 * f * (1.0 - b)) * (1.0 - a);
    vec3 dark = a * (1.0 - f + 2.0 * f * b);
    return mix(light, dark, lessThan(a, vec3(0.5)));
}
vec3 mtMixSaturation(float f, vec3 a, vec3 b) {
    vec3 h = mtRgbToHsv(a);
    if (h.y == 0.0) return a;
    h.y = mix(h.y, mtRgbToHsv(b).y, f);
    return mtHsvToRgb(h);
}
vec3 mtMixValue(float f, vec3 a, vec3 b) {
    vec3 h = mtRgbToHsv(a);
    h.z = mix(h.z, max(max(b.r, b.g), b.b), f);
    return mtHsvToRgb(h);
}

#endif
