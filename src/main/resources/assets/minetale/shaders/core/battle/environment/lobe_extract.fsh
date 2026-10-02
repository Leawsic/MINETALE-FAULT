#version 330

uniform sampler2D EnvironmentAtlas;
out vec4 fragColor;

#moj_import <minetale:battle/environment/field.glsl>
#moj_import <minetale:battle/environment/atlas.glsl>

const int MINETALE_ENVIRONMENT_SEARCH_STEP = 2;
const float MINETALE_ENVIRONMENT_MIN_BRIGHT_RANGE = 1.0 / 255.0;
const float MINETALE_ENVIRONMENT_GOLDEN_ANGLE = 2.3999632297286533;
const float MINETALE_ENVIRONMENT_SCATTER_PHASE = 0.7137243789447656;
const float MINETALE_ENVIRONMENT_CENTROID_WIDE_SHARPNESS = 2.5;
const float MINETALE_ENVIRONMENT_CENTROID_LOCAL_SHARPNESS = 8.0;
const float MINETALE_ENVIRONMENT_CENTROID_EPSILON = 1.0e-6;
const float MINETALE_ENVIRONMENT_COVERAGE_BASE_WEIGHT = 0.55;
const float MINETALE_ENVIRONMENT_COVERAGE_LUMA_WEIGHT = 0.25;
const float MINETALE_ENVIRONMENT_COVERAGE_COLOR_WEIGHT = 0.20;
const vec3 MINETALE_ENVIRONMENT_LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);

vec3 minetaleEnvironmentSearchColor(
        int faceIndex,
        ivec2 facePixel,
        ivec2 atlasSize
) {
    ivec2 faceSize = minetaleEnvironmentFaceSize(atlasSize);
    vec3 accumulated = vec3(0.0);
    float sampleCount = 0.0;
    for (int offsetY = 0; offsetY < MINETALE_ENVIRONMENT_SEARCH_STEP; ++offsetY) {
        for (int offsetX = 0; offsetX < MINETALE_ENVIRONMENT_SEARCH_STEP; ++offsetX) {
            ivec2 samplePixel = min(
                    facePixel + ivec2(offsetX, offsetY),
                    faceSize - ivec2(1)
            );
            accumulated += texelFetch(
                    EnvironmentAtlas,
                    minetaleEnvironmentFaceTexel(faceIndex, samplePixel, atlasSize),
                    0
            ).rgb;
            sampleCount += 1.0;
        }
    }
    return accumulated / max(sampleCount, 1.0);
}

vec3 minetaleSampleEnvironment(vec3 direction, ivec2 atlasSize) {
    return texture(
            EnvironmentAtlas,
            minetaleEnvironmentAtlasUv(direction, atlasSize)
    ).rgb;
}

float minetaleEnvironmentLuma(vec3 color) {
    return dot(color, MINETALE_ENVIRONMENT_LUMA_WEIGHTS);
}

float minetaleBrightWeight(float luma, float meanLuma, float maximumLuma) {
    float brightRange = maximumLuma - meanLuma;
    if (brightRange <= MINETALE_ENVIRONMENT_MIN_BRIGHT_RANGE) {
        return 1.0;
    }
    float threshold = mix(meanLuma, maximumLuma, 0.45);
    float normalized = smoothstep(
            threshold,
            max(maximumLuma, threshold + MINETALE_ENVIRONMENT_MIN_BRIGHT_RANGE),
            luma
    );
    return normalized * normalized;
}

float minetaleCoverageWeight(
        vec3 color,
        vec3 globalMean,
        float meanLuma,
        float maximumLuma
) {
    float brightRange = max(
            maximumLuma - meanLuma,
            MINETALE_ENVIRONMENT_MIN_BRIGHT_RANGE
    );
    float relativeLuma = clamp(
            (minetaleEnvironmentLuma(color) - meanLuma) / brightRange,
            0.0,
            1.0
    );
    float colorDifference = clamp(length(color - globalMean) * 1.5, 0.0, 1.0);
    return MINETALE_ENVIRONMENT_COVERAGE_BASE_WEIGHT
            + MINETALE_ENVIRONMENT_COVERAGE_LUMA_WEIGHT * relativeLuma
            + MINETALE_ENVIRONMENT_COVERAGE_COLOR_WEIGHT * colorDifference;
}

// 固定 Fibonacci 球面序列为各职责提供可复现的初始采样锚点。
vec3 minetaleStableScatterAnchor(
        int sequenceIndex,
        int sequenceCount,
        float phase
) {
    int permutedIndex = (sequenceIndex * 5 + 3) % sequenceCount;
    float sampleIndex = float(permutedIndex) + 0.5;
    float y = 1.0 - 2.0 * sampleIndex / float(sequenceCount);
    float radius = sqrt(max(1.0 - y * y, 0.0));
    float azimuth = sampleIndex * MINETALE_ENVIRONMENT_GOLDEN_ANGLE
            + phase;
    return vec3(cos(azimuth) * radius, y, sin(azimuth) * radius);
}

vec3 minetaleFindEnvironmentCentroid(
        vec3 anchor,
        float sharpness,
        bool highlightRole,
        vec3 globalMean,
        float meanLuma,
        float maximumLuma,
        ivec2 atlasSize,
        ivec2 faceSize,
        out float centroidLuma
) {
    vec3 weightedDirection = vec3(0.0);
    float weightedLuma = 0.0;
    float accumulatedWeight = 0.0;
    for (int faceIndex = 0; faceIndex < 6; ++faceIndex) {
        for (int y = 0; y < faceSize.y; y += MINETALE_ENVIRONMENT_SEARCH_STEP) {
            for (int x = 0; x < faceSize.x; x += MINETALE_ENVIRONMENT_SEARCH_STEP) {
                vec3 color = minetaleEnvironmentSearchColor(
                        faceIndex, ivec2(x, y), atlasSize
                );
                float luma = minetaleEnvironmentLuma(color);
                float contentWeight = highlightRole
                        ? minetaleBrightWeight(luma, meanLuma, maximumLuma)
                        : minetaleCoverageWeight(
                                color, globalMean, meanLuma, maximumLuma
                        );
                vec2 faceUv = (
                        (vec2(x, y) + vec2(
                                float(MINETALE_ENVIRONMENT_SEARCH_STEP) * 0.5
                        ))
                        / vec2(faceSize)
                ) * 2.0 - 1.0;
                vec3 direction = minetaleEnvironmentFaceDirection(faceIndex, faceUv);
                float anchorWeight = exp2(
                        sharpness
                        * (clamp(dot(anchor, direction), -1.0, 1.0) - 1.0)
                );
                float weight = contentWeight * anchorWeight;
                weightedDirection += direction * weight;
                weightedLuma += luma * weight;
                accumulatedWeight += weight;
            }
        }
    }

    float directionLengthSquared = dot(weightedDirection, weightedDirection);
    if (accumulatedWeight <= MINETALE_ENVIRONMENT_CENTROID_EPSILON
            || directionLengthSquared <= MINETALE_ENVIRONMENT_CENTROID_EPSILON) {
        centroidLuma = meanLuma;
        return anchor;
    }
    centroidLuma = weightedLuma / accumulatedWeight;
    return weightedDirection * inversesqrt(directionLengthSquared);
}

void main() {
    int lobeIndex = int(floor(gl_FragCoord.x));
    if (lobeIndex < 0 || lobeIndex >= MINETALE_ENVIRONMENT_LOBE_COUNT) {
        fragColor = vec4(0.0);
        return;
    }

    ivec2 atlasSize = textureSize(EnvironmentAtlas, 0);
    ivec2 faceSize = minetaleEnvironmentFaceSize(atlasSize);
    vec3 accumulatedColor = vec3(0.0);
    float accumulatedLuma = 0.0;
    float maximumLuma = 0.0;
    float sampleCount = 0.0;
    for (int faceIndex = 0; faceIndex < 6; ++faceIndex) {
        for (int y = 0; y < faceSize.y; y += MINETALE_ENVIRONMENT_SEARCH_STEP) {
            for (int x = 0; x < faceSize.x; x += MINETALE_ENVIRONMENT_SEARCH_STEP) {
                vec3 color = minetaleEnvironmentSearchColor(
                        faceIndex, ivec2(x, y), atlasSize
                );
                float luma = minetaleEnvironmentLuma(color);
                accumulatedColor += color;
                accumulatedLuma += luma;
                maximumLuma = max(maximumLuma, luma);
                sampleCount += 1.0;
            }
        }
    }
    vec3 globalMean = accumulatedColor / max(sampleCount, 1.0);
    float meanLuma = accumulatedLuma / max(sampleCount, 1.0);

    // 高亮槽追踪稀有亮峰，覆盖槽保留环境基底，防止单峰耗尽采样预算。
    bool highlightRole = lobeIndex < MINETALE_ENVIRONMENT_HIGHLIGHT_LOBE_COUNT;
    int roleIndex = highlightRole
            ? lobeIndex
            : lobeIndex - MINETALE_ENVIRONMENT_HIGHLIGHT_LOBE_COUNT;
    int roleCount = highlightRole
            ? MINETALE_ENVIRONMENT_HIGHLIGHT_LOBE_COUNT
            : MINETALE_ENVIRONMENT_COVERAGE_LOBE_COUNT;
    float rolePhase = highlightRole
            ? MINETALE_ENVIRONMENT_SCATTER_PHASE + 1.5707963267948966
            : MINETALE_ENVIRONMENT_SCATTER_PHASE;
    vec3 stableAnchor = minetaleStableScatterAnchor(
            roleIndex, roleCount, rolePhase
    );
    float wideCentroidLuma;
    vec3 wideCentroid = minetaleFindEnvironmentCentroid(
            stableAnchor,
            MINETALE_ENVIRONMENT_CENTROID_WIDE_SHARPNESS,
            highlightRole,
            globalMean,
            meanLuma,
            maximumLuma,
            atlasSize,
            faceSize,
            wideCentroidLuma
    );
    float localCentroidLuma;
    vec3 selectedDirection = minetaleFindEnvironmentCentroid(
            wideCentroid,
            MINETALE_ENVIRONMENT_CENTROID_LOCAL_SHARPNESS,
            highlightRole,
            globalMean,
            meanLuma,
            maximumLuma,
            atlasSize,
            faceSize,
            localCentroidLuma
    );
    float selectedLuma = max(
            localCentroidLuma,
            minetaleEnvironmentLuma(minetaleSampleEnvironment(
                    selectedDirection, atlasSize
            ))
    );

    fragColor = vec4(
            minetaleEncodeEnvironmentDirection(selectedDirection),
            selectedLuma
    );
}
