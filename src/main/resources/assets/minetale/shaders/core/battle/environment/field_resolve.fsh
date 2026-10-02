#version 330

uniform sampler2D EnvironmentAtlas;
uniform sampler2D RawLobeSampler;
out vec4 fragColor;

#moj_import <minetale:battle/environment/atlas.glsl>
#moj_import <minetale:battle/environment/field.glsl>

const int MINETALE_ENVIRONMENT_GLOBAL_STEP = 4;
const vec3 MINETALE_ENVIRONMENT_LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);
const float MINETALE_ENVIRONMENT_NEIGHBOR_EPSILON = 1.0e-5;
const float MINETALE_ENVIRONMENT_RING_4_COS = 0.9975640502598242;
const float MINETALE_ENVIRONMENT_RING_4_SIN = 0.0697564737441253;
const float MINETALE_ENVIRONMENT_RING_9_COS = 0.9876883405951378;
const float MINETALE_ENVIRONMENT_RING_9_SIN = 0.1564344650402309;
const float MINETALE_ENVIRONMENT_RING_18_COS = 0.9510565162951535;
const float MINETALE_ENVIRONMENT_RING_18_SIN = 0.3090169943749474;

vec4 minetaleRawLobe(int index) {
    return texelFetch(RawLobeSampler, ivec2(index, 0), 0);
}

vec3 minetaleSampleEnvironment(vec3 direction) {
    return texture(
            EnvironmentAtlas,
            minetaleEnvironmentAtlasUv(direction, textureSize(EnvironmentAtlas, 0))
    ).rgb;
}

vec3 minetaleEnvironmentGlobalMean() {
    ivec2 atlasSize = textureSize(EnvironmentAtlas, 0);
    ivec2 faceSize = minetaleEnvironmentFaceSize(atlasSize);
    vec3 accumulated = vec3(0.0);
    float sampleCount = 0.0;
    for (int faceIndex = 0; faceIndex < 6; ++faceIndex) {
        for (int y = 0; y < faceSize.y; y += MINETALE_ENVIRONMENT_GLOBAL_STEP) {
            for (int x = 0; x < faceSize.x; x += MINETALE_ENVIRONMENT_GLOBAL_STEP) {
                accumulated += texelFetch(
                        EnvironmentAtlas,
                        minetaleEnvironmentFaceTexel(faceIndex, ivec2(x, y), atlasSize),
                        0
                ).rgb;
                sampleCount += 1.0;
            }
        }
    }
    return accumulated / max(sampleCount, 1.0);
}

vec2 minetaleRingAzimuth(int index) {
    if (index == 0) return vec2(1.0, 0.0);
    if (index == 1) return vec2(0.7071067811865476, 0.7071067811865476);
    if (index == 2) return vec2(0.0, 1.0);
    if (index == 3) return vec2(-0.7071067811865476, 0.7071067811865476);
    if (index == 4) return vec2(-1.0, 0.0);
    if (index == 5) return vec2(-0.7071067811865476, -0.7071067811865476);
    if (index == 6) return vec2(0.0, -1.0);
    return vec2(0.7071067811865476, -0.7071067811865476);
}

vec3 minetaleRingDirection(
        vec3 center,
        vec3 tangent,
        vec3 bitangent,
        vec2 azimuth,
        float ringCos,
        float ringSin
) {
    return normalize(
            center * ringCos
                    + (tangent * azimuth.x + bitangent * azimuth.y) * ringSin
    );
}

void minetaleAccumulateBrightNeighbor(
        vec3 direction,
        float spatialWeight,
        float selectedLuma,
        inout vec3 weightedColor,
        inout vec3 weightedDirection,
        inout float brightWeight,
        inout float spatialWeightSum
) {
    vec3 color = minetaleSampleEnvironment(direction);
    float luma = dot(color, MINETALE_ENVIRONMENT_LUMA_WEIGHTS);
    float threshold = selectedLuma * 0.45;
    float upper = max(selectedLuma * 0.95, threshold + 1.0 / 255.0);
    float localBrightWeight = smoothstep(threshold, upper, luma);
    localBrightWeight *= localBrightWeight;
    float weight = spatialWeight * localBrightWeight;
    weightedColor += color * weight;
    weightedDirection += direction * weight;
    brightWeight += weight;
    spatialWeightSum += spatialWeight;
}

void minetaleResolveBrightNeighborhood(
        int lobeIndex,
        out vec3 mixedColor,
        out vec3 mixedDirection,
        out float brightCoverage
) {
    vec4 rawLobe = minetaleRawLobe(lobeIndex);
    vec3 center = minetaleDecodeEnvironmentDirection(rawLobe.rgb);
    vec3 referenceAxis = abs(center.y) < 0.95
            ? vec3(0.0, 1.0, 0.0)
            : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(referenceAxis, center));
    vec3 bitangent = cross(center, tangent);

    vec3 weightedColor = vec3(0.0);
    vec3 weightedDirection = vec3(0.0);
    float brightWeight = 0.0;
    float spatialWeightSum = 0.0;
    minetaleAccumulateBrightNeighbor(
            center, 1.0, rawLobe.a,
            weightedColor, weightedDirection, brightWeight, spatialWeightSum
    );
    for (int index = 0; index < 8; ++index) {
        vec2 azimuth = minetaleRingAzimuth(index);
        minetaleAccumulateBrightNeighbor(
                minetaleRingDirection(
                        center, tangent, bitangent, azimuth,
                        MINETALE_ENVIRONMENT_RING_4_COS,
                        MINETALE_ENVIRONMENT_RING_4_SIN
                ),
                0.85,
                rawLobe.a,
                weightedColor, weightedDirection, brightWeight, spatialWeightSum
        );
        minetaleAccumulateBrightNeighbor(
                minetaleRingDirection(
                        center, tangent, bitangent, azimuth,
                        MINETALE_ENVIRONMENT_RING_9_COS,
                        MINETALE_ENVIRONMENT_RING_9_SIN
                ),
                0.55,
                rawLobe.a,
                weightedColor, weightedDirection, brightWeight, spatialWeightSum
        );
        minetaleAccumulateBrightNeighbor(
                minetaleRingDirection(
                        center, tangent, bitangent, azimuth,
                        MINETALE_ENVIRONMENT_RING_18_COS,
                        MINETALE_ENVIRONMENT_RING_18_SIN
                ),
                0.25,
                rawLobe.a,
                weightedColor, weightedDirection, brightWeight, spatialWeightSum
        );
    }

    if (brightWeight <= MINETALE_ENVIRONMENT_NEIGHBOR_EPSILON) {
        mixedColor = minetaleSampleEnvironment(center);
        mixedDirection = center;
        brightCoverage = 0.0;
        return;
    }
    mixedColor = weightedColor / brightWeight;
    float directionLengthSquared = dot(weightedDirection, weightedDirection);
    mixedDirection = directionLengthSquared > MINETALE_ENVIRONMENT_NEIGHBOR_EPSILON
            ? weightedDirection * inversesqrt(directionLengthSquared)
            : center;
    brightCoverage = clamp(brightWeight / max(spatialWeightSum, 1.0), 0.0, 1.0);
}

void main() {
    int fieldIndex = int(floor(gl_FragCoord.x));

    if (fieldIndex == MINETALE_ENVIRONMENT_GLOBAL_TEXEL) {
        vec3 globalMean = minetaleEnvironmentGlobalMean();
        float globalLuma = dot(globalMean, MINETALE_ENVIRONMENT_LUMA_WEIGHTS);
        fragColor = vec4(globalMean, globalLuma);
        return;
    }

    int colorIndex = fieldIndex - MINETALE_ENVIRONMENT_LOBE_COLOR_BASE_TEXEL;
    if (colorIndex >= 0 && colorIndex < MINETALE_ENVIRONMENT_LOBE_COUNT) {
        vec3 mixedColor;
        vec3 mixedDirection;
        float brightCoverage;
        minetaleResolveBrightNeighborhood(
                colorIndex, mixedColor, mixedDirection, brightCoverage
        );

        vec3 globalMean = minetaleEnvironmentGlobalMean();
        float globalLuma = dot(globalMean, MINETALE_ENVIRONMENT_LUMA_WEIGHTS);
        float mixedLuma = dot(mixedColor, MINETALE_ENVIRONMENT_LUMA_WEIGHTS);
        float colorDistance = length(mixedColor - globalMean);
        float lumaDifference = max(mixedLuma - globalLuma, 0.0);
        float salience = clamp(
                0.15 + 1.60 * colorDistance + 1.80 * lumaDifference,
                0.0,
                1.0
        );
        float roleEnergyScale = colorIndex < MINETALE_ENVIRONMENT_HIGHLIGHT_LOBE_COUNT
                ? 1.0 / float(MINETALE_ENVIRONMENT_HIGHLIGHT_LOBE_COUNT)
                : 1.0;
        vec3 lobeEnergy = mixedColor * salience * roleEnergyScale;
        float broadness = smoothstep(0.20, 0.85, brightCoverage);
        float lambda = mix(
                MINETALE_ENVIRONMENT_LAMBDA_MAX,
                MINETALE_ENVIRONMENT_LAMBDA_MIN,
                broadness
        );
        fragColor = vec4(
                lobeEnergy,
                minetaleEncodeEnvironmentLambda(lambda)
        );
        return;
    }

    int directionIndex = fieldIndex - MINETALE_ENVIRONMENT_LOBE_DIRECTION_BASE_TEXEL;
    if (directionIndex >= 0 && directionIndex < MINETALE_ENVIRONMENT_LOBE_COUNT) {
        vec3 mixedColor;
        vec3 mixedDirection;
        float brightCoverage;
        minetaleResolveBrightNeighborhood(
                directionIndex, mixedColor, mixedDirection, brightCoverage
        );
        fragColor = vec4(
                minetaleEncodeEnvironmentDirection(mixedDirection),
                brightCoverage
        );
        return;
    }

    fragColor = vec4(0.0);
}
