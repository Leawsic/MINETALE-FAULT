#ifndef MINETALE_ENVIRONMENT_FIELD_GLSL
#define MINETALE_ENVIRONMENT_FIELD_GLSL

// 稀疏环境 Field 的共享布局，以及动态 lobe 方向与 lambda 编解码。
const int MINETALE_ENVIRONMENT_LOBE_COUNT = 12;
const int MINETALE_ENVIRONMENT_HIGHLIGHT_LOBE_COUNT = 3;
const int MINETALE_ENVIRONMENT_COVERAGE_LOBE_COUNT =
        MINETALE_ENVIRONMENT_LOBE_COUNT - MINETALE_ENVIRONMENT_HIGHLIGHT_LOBE_COUNT;
const int MINETALE_ENVIRONMENT_GLOBAL_TEXEL = 0;
const int MINETALE_ENVIRONMENT_LOBE_COLOR_BASE_TEXEL = 1;
const int MINETALE_ENVIRONMENT_LOBE_DIRECTION_BASE_TEXEL = 13;
const float MINETALE_ENVIRONMENT_LAMBDA_MIN = 1.5;
const float MINETALE_ENVIRONMENT_LAMBDA_MAX = 6.8;

vec3 minetaleEncodeEnvironmentDirection(vec3 direction) {
    return normalize(direction) * 0.5 + 0.5;
}

vec3 minetaleDecodeEnvironmentDirection(vec3 encodedDirection) {
    vec3 direction = encodedDirection * 2.0 - 1.0;
    float lengthSquared = dot(direction, direction);
    if (isnan(lengthSquared) || isinf(lengthSquared) || lengthSquared <= 1.0e-6) {
        return vec3(0.0, 1.0, 0.0);
    }
    return direction * inversesqrt(lengthSquared);
}

float minetaleEncodeEnvironmentLambda(float lambda) {
    return clamp(
            (lambda - MINETALE_ENVIRONMENT_LAMBDA_MIN)
                    / (MINETALE_ENVIRONMENT_LAMBDA_MAX - MINETALE_ENVIRONMENT_LAMBDA_MIN),
            0.0,
            1.0
    );
}

float minetaleDecodeEnvironmentLambda(float encodedLambda) {
    return mix(
            MINETALE_ENVIRONMENT_LAMBDA_MIN,
            MINETALE_ENVIRONMENT_LAMBDA_MAX,
            clamp(encodedLambda, 0.0, 1.0)
    );
}

#endif
