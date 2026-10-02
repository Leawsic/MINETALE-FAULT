#ifndef MINETALE_ENVIRONMENT_BACKGROUND_GLSL
#define MINETALE_ENVIRONMENT_BACKGROUND_GLSL

// Background Evaluate 与 Composite 必须共用该 std140 ABI。
layout(std140) uniform EnvironmentBackgroundUniform {
    vec4 BattleViewDirectionAspect;
    vec4 BattleCameraRight;
    vec4 BattleCameraUp;
    vec4 EnvironmentCaptureRight;
    vec4 EnvironmentCaptureUp;
    vec4 EnvironmentCaptureForward;
    vec4 EnvironmentBackgroundControls;
    vec4 EnvironmentBackgroundAdaptation;
    vec4 EnvironmentBackgroundDither;
};

// RGB10 写入 RGBA8 前做可逆压扩，提高背景暗部的有效线性精度。
float minetaleEncodeEnvironmentBackground(float value) {
    float nonNegative = max(value, 0.0);
    return nonNegative <= 0.0031308
            ? nonNegative * 12.92
            : 1.055 * pow(nonNegative, 1.0 / 2.4) - 0.055;
    //标准的 sRGB 分段传递函数，即 IEC 61966-2-1 定义的编码曲线
}

vec3 minetaleEncodeEnvironmentBackground(vec3 color) {
    return vec3(
            minetaleEncodeEnvironmentBackground(color.r),
            minetaleEncodeEnvironmentBackground(color.g),
            minetaleEncodeEnvironmentBackground(color.b)
    );
}

float minetaleDecodeEnvironmentBackground(float value) {
    float nonNegative = max(value, 0.0);
    return nonNegative <= 0.04045
            ? nonNegative / 12.92
            : pow((nonNegative + 0.055) / 1.055, 2.4);
}

vec3 minetaleDecodeEnvironmentBackground(vec3 color) {
    return vec3(
            minetaleDecodeEnvironmentBackground(color.r),
            minetaleDecodeEnvironmentBackground(color.g),
            minetaleDecodeEnvironmentBackground(color.b)
    );
}

#endif
