#version 330

uniform sampler2D PreviousMinMaxSampler;

layout(std140) uniform ShadowMinMaxUniform {
    vec4 SourceTargetSize;
    vec4 RegionOriginSize;
};

out vec4 fragColor;

float decodeDepth16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

vec2 encodeDepth16(float value, bool roundUp) {
    float encoded = roundUp ? ceil(clamp(value, 0.0, 1.0) * 65535.0)
            : floor(clamp(value, 0.0, 1.0) * 65535.0);
    float highByte = floor(encoded / 256.0);
    float lowByte = encoded - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

void main() {
    ivec2 outputTexel = ivec2(gl_FragCoord.xy);
    ivec2 sourceSize = ivec2(SourceTargetSize.xy);
    ivec2 childOrigin = outputTexel * 4;
    float minimumDepth = 1.0;
    float maximumDepth = 0.0;
    bool hasOccluder = false;
    bool hasEmptyChild = false;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            ivec2 child = childOrigin + ivec2(x, y);
            if (any(greaterThanEqual(child, sourceSize))) {
                hasEmptyChild = true;
                continue;
            }
            vec4 encoded = texelFetch(PreviousMinMaxSampler, child, 0);
            float childMinimum = decodeDepth16(encoded.rg);
            float childMaximum = decodeDepth16(encoded.ba);
            if (childMinimum > childMaximum) {
                hasEmptyChild = true;
                continue;
            }
            minimumDepth = min(minimumDepth, childMinimum);
            maximumDepth = max(maximumDepth, childMaximum);
            hasOccluder = true;
        }
    }
    if (!hasOccluder) {
        fragColor = vec4(encodeDepth16(1.0, false), encodeDepth16(0.0, true));
    } else {
        if (hasEmptyChild) maximumDepth = 1.0;
        fragColor = vec4(encodeDepth16(minimumDepth, false), encodeDepth16(maximumDepth, true));
    }
}
