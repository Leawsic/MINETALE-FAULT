#version 330

uniform sampler2D EnvironmentBackgroundSampler;
uniform sampler2D ActivationFrameSampler;
uniform sampler2D BlueNoiseSampler;

in vec2 texCoord;
out vec4 fragColor;

#moj_import <minetale:battle/environment/background.glsl>
#moj_import <minetale:battle/encoding/rgb10_rgba8.glsl>
#moj_import <minetale:battle/dither/quantization.glsl>

vec3 minetaleFetchBackgroundIntermediate(ivec2 coordinate, ivec2 targetSize) {
    return minetaleUnpackRgb10FromRgba8(
            texelFetch(
                    EnvironmentBackgroundSampler,
                    clamp(coordinate, ivec2(0), targetSize - ivec2(1)),
                    0
            )
    );
}

vec3 minetaleSampleBackgroundIntermediate(vec2 coordinate) {
    ivec2 targetSize = textureSize(EnvironmentBackgroundSampler, 0);
    vec2 sourcePosition = coordinate * vec2(targetSize) - 0.5;
    ivec2 sourceBase = ivec2(floor(sourcePosition));
    vec2 sourceFraction = fract(sourcePosition);

    vec3 encoded00 =
            minetaleFetchBackgroundIntermediate(sourceBase, targetSize);
    vec3 encoded10 =
            minetaleFetchBackgroundIntermediate(sourceBase + ivec2(1, 0), targetSize);
    vec3 encoded01 =
            minetaleFetchBackgroundIntermediate(sourceBase + ivec2(0, 1), targetSize);
    vec3 encoded11 =
            minetaleFetchBackgroundIntermediate(sourceBase + ivec2(1, 1), targetSize);
    return mix(
            mix(encoded00, encoded10, sourceFraction.x),
            mix(encoded01, encoded11, sourceFraction.x),
            sourceFraction.y
    );
}

void main() {
    vec3 encodedBackground =
            minetaleSampleBackgroundIntermediate(texCoord);
    vec3 backgroundColor =
            minetaleDecodeEnvironmentBackground(encodedBackground);
    backgroundColor = minetaleApplyQuantizationDither(
            backgroundColor,
            minetaleBlueNoise64(BlueNoiseSampler, gl_FragCoord.xy),
            1.0 / 255.0,
            EnvironmentBackgroundDither.x
    );
    float opacity = clamp(EnvironmentBackgroundAdaptation.z, 0.0, 1.0);
    vec3 activationColor = texture(ActivationFrameSampler, texCoord).rgb;
    fragColor = vec4(mix(activationColor, backgroundColor, opacity), 1.0);
}
