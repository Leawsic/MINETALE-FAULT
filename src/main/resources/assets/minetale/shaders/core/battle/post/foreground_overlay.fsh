#version 330

uniform sampler2D SceneSampler;
uniform sampler2D EnvironmentBackgroundSampler;
uniform sampler2D BlueNoiseSampler;

layout(std140) uniform ForegroundOverlayUniform {
    vec4 ForegroundColorOpacity;
    vec4 ForegroundSourceSettings;
};

in vec2 texCoord;
out vec4 fragColor;

#moj_import <minetale:battle/encoding/rgb10_rgba8.glsl>
#moj_import <minetale:battle/dither/quantization.glsl>

float minetaleDecodeForegroundEnvironment(float value) {
    float nonNegative = max(value, 0.0);
    return nonNegative <= 0.04045
            ? nonNegative / 12.92
            : pow((nonNegative + 0.055) / 1.055, 2.4);
}

vec3 minetaleDecodeForegroundEnvironment(vec3 color) {
    return vec3(
            minetaleDecodeForegroundEnvironment(color.r),
            minetaleDecodeForegroundEnvironment(color.g),
            minetaleDecodeForegroundEnvironment(color.b)
    );
}

vec3 minetaleFetchForegroundEnvironment(ivec2 coordinate, ivec2 targetSize) {
    return minetaleUnpackRgb10FromRgba8(
            texelFetch(
                    EnvironmentBackgroundSampler,
                    clamp(coordinate, ivec2(0), targetSize - ivec2(1)),
                    0
            )
    );
}

vec3 minetaleSampleForegroundEnvironment(vec2 coordinate) {
    ivec2 targetSize = textureSize(EnvironmentBackgroundSampler, 0);
    vec2 sourcePosition = coordinate * vec2(targetSize) - 0.5;
    ivec2 sourceBase = ivec2(floor(sourcePosition));
    vec2 sourceFraction = fract(sourcePosition);
    vec3 encoded00 = minetaleFetchForegroundEnvironment(sourceBase, targetSize);
    vec3 encoded10 = minetaleFetchForegroundEnvironment(sourceBase + ivec2(1, 0), targetSize);
    vec3 encoded01 = minetaleFetchForegroundEnvironment(sourceBase + ivec2(0, 1), targetSize);
    vec3 encoded11 = minetaleFetchForegroundEnvironment(sourceBase + ivec2(1, 1), targetSize);
    return minetaleDecodeForegroundEnvironment(mix(
            mix(encoded00, encoded10, sourceFraction.x),
            mix(encoded01, encoded11, sourceFraction.x),
            sourceFraction.y
    ));
}

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    bool usesEnvironment = ForegroundSourceSettings.x > 0.5;
    bool environmentAvailable = ForegroundSourceSettings.y > 0.5;
    float opacity = clamp(ForegroundColorOpacity.a, 0.0, 1.0);
    if (usesEnvironment && !environmentAvailable) {
        opacity = 0.0;
    }

    vec3 overlayColor = ForegroundColorOpacity.rgb;
    if (usesEnvironment && environmentAvailable) {
        overlayColor = minetaleApplyQuantizationDither(
                minetaleSampleForegroundEnvironment(texCoord),
                minetaleBlueNoise64(BlueNoiseSampler, gl_FragCoord.xy),
                1.0 / 255.0,
                ForegroundSourceSettings.z
        );
    }
    fragColor = mix(scene, vec4(overlayColor, 1.0), opacity);
}
