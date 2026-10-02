#version 330

uniform sampler2D EnvironmentFieldSampler;

in vec2 texCoord;
out vec4 fragColor;

#moj_import <minetale:battle/environment/background.glsl>
#moj_import <minetale:battle/environment/field.glsl>
#moj_import <minetale:battle/encoding/rgb10_rgba8.glsl>

const float MINETALE_LOG2_E = 1.4426950408889634;
const vec3 MINETALE_ENVIRONMENT_LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);
const float MINETALE_ENVIRONMENT_BACKGROUND_MIN_EXPOSURE = 0.35;
const float MINETALE_ENVIRONMENT_BACKGROUND_MAX_EXPOSURE = 3.5;

vec3 minetaleCaptureLocalDirection(vec3 canonicalDirection) {
    // canonical +X/-Z/-Y 必须映射到捕获时冻结的 right/up/forward 基。
    vec3 worldDirection =
            EnvironmentCaptureRight.xyz * canonicalDirection.x
            - EnvironmentCaptureUp.xyz * canonicalDirection.z
            - EnvironmentCaptureForward.xyz * canonicalDirection.y;
    return normalize(vec3(
            dot(worldDirection, EnvironmentCaptureRight.xyz),
            dot(worldDirection, EnvironmentCaptureUp.xyz),
            dot(worldDirection, EnvironmentCaptureForward.xyz)
    ));
}

void main() {
    vec2 screen = texCoord * 2.0 - 1.0;
    vec3 canonicalDirection = normalize(
            BattleViewDirectionAspect.xyz
            + BattleCameraRight.xyz * (screen.x * BattleViewDirectionAspect.w)
            + BattleCameraUp.xyz * screen.y
    );
    vec3 localViewDirection = minetaleCaptureLocalDirection(canonicalDirection);

    float backgroundIntensity = EnvironmentBackgroundControls.x;
    float backgroundWidthScale = max(EnvironmentBackgroundControls.y, 0.0001);
    float backgroundBaseLevel = EnvironmentBackgroundControls.z;
    float backgroundColorStrength = EnvironmentBackgroundControls.w;

    vec4 fieldGlobal = texelFetch(
            EnvironmentFieldSampler,
            ivec2(MINETALE_ENVIRONMENT_GLOBAL_TEXEL, 0),
            0
    );
    vec3 accumulated = fieldGlobal.rgb * backgroundBaseLevel;
    float inverseWidthSquared = 1.0 / (backgroundWidthScale * backgroundWidthScale);
    for (int index = 0; index < MINETALE_ENVIRONMENT_LOBE_COUNT; ++index) {
        vec4 fieldLobe = texelFetch(
                EnvironmentFieldSampler,
                ivec2(MINETALE_ENVIRONMENT_LOBE_COLOR_BASE_TEXEL + index, 0),
                0
        );
        vec3 lobeDirection = minetaleDecodeEnvironmentDirection(texelFetch(
                EnvironmentFieldSampler,
                ivec2(MINETALE_ENVIRONMENT_LOBE_DIRECTION_BASE_TEXEL + index, 0),
                0
        ).rgb);
        float effectiveLambda =
                minetaleDecodeEnvironmentLambda(fieldLobe.a)
                * inverseWidthSquared;
        float weight = exp2(
                effectiveLambda
                * MINETALE_LOG2_E
                * (
                    clamp(
                            dot(localViewDirection, lobeDirection),
                            -1.0,
                            1.0
                    )
                    - 1.0
                )
        );
        accumulated += fieldLobe.rgb * weight;
    }

    float sceneLuma = max(fieldGlobal.a, 0.0001);
    float targetLuma = max(EnvironmentBackgroundAdaptation.x, 0.0001);
    float adaptationStrength = clamp(
            EnvironmentBackgroundAdaptation.y,
            0.0,
            1.0
    );
    float requestedExposure = targetLuma / sceneLuma;
    float adaptiveExposure = clamp(
            pow(max(requestedExposure, 0.0001), adaptationStrength),
            MINETALE_ENVIRONMENT_BACKGROUND_MIN_EXPOSURE,
            MINETALE_ENVIRONMENT_BACKGROUND_MAX_EXPOSURE
    );
    float effectiveIntensity = backgroundIntensity * adaptiveExposure;
    vec3 compressed =
            vec3(1.0)
            - exp(-max(accumulated, vec3(0.0)) * effectiveIntensity);
    float luma = dot(compressed, MINETALE_ENVIRONMENT_LUMA_WEIGHTS);
    vec3 backgroundColor = mix(
            vec3(luma),
            compressed,
            backgroundColorStrength
    );
    fragColor = minetalePackRgb10ToRgba8(
            minetaleEncodeEnvironmentBackground(backgroundColor)
    );
}
