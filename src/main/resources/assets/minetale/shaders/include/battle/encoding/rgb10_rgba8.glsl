#ifndef MINETALE_RGB10_RGBA8_GLSL
#define MINETALE_RGB10_RGBA8_GLSL

// RGB 保存三通道高 8 位，Alpha 低 6 位保存各低 2 位；须 texelFetch 后解码再手动过滤。
vec4 minetalePackRgb10ToRgba8(vec3 value) {
    vec3 code = floor(clamp(value, vec3(0.0), vec3(1.0)) * 1023.0 + 0.5);
    vec3 highBits = floor(code * 0.25);
    vec3 lowBits = code - highBits * 4.0;
    float packedLowBits = lowBits.r + lowBits.g * 4.0 + lowBits.b * 16.0;
    return vec4(highBits, packedLowBits) / 255.0;
}

vec3 minetaleUnpackRgb10FromRgba8(vec4 packedValue) {
    vec4 packedBytes = floor(packedValue * 255.0 + 0.5);
    float packedLowBits = packedBytes.a;
    vec3 lowBits = vec3(
            mod(packedLowBits, 4.0),
            mod(floor(packedLowBits * 0.25), 4.0),
            mod(floor(packedLowBits * 0.0625), 4.0)
    );
    return (packedBytes.rgb * 4.0 + lowBits) / 1023.0;
}

#endif
