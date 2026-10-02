#version 330

uniform sampler2D ShadowSampler;

layout(std140) uniform ShadowMinMaxUniform {
    vec4 SourceTargetSize;
    vec4 RegionOriginSize;
};

out vec4 fragColor;

vec2 encodeDepth16(float value, bool roundUp) {
    float encoded = roundUp ? ceil(clamp(value, 0.0, 1.0) * 65535.0)
            : floor(clamp(value, 0.0, 1.0) * 65535.0);
    float highByte = floor(encoded / 256.0);
    float lowByte = encoded - highByte * 256.0;
    return vec2(highByte, lowByte) / 255.0;
}

void main() {
    ivec2 outputTexel = ivec2(gl_FragCoord.xy);
    ivec2 regionOrigin = ivec2(RegionOriginSize.xy);
    ivec2 regionSize = ivec2(RegionOriginSize.zw);
    ivec2 sourceSize = ivec2(SourceTargetSize.xy);
    ivec2 blockOrigin = regionOrigin + outputTexel * 4;
    float minimumDepth = 1.0;
    float maximumDepth = 0.0;
    bool hasOccluder = false;
    bool hasEmptyTexel = false;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            ivec2 sourceTexel = blockOrigin + ivec2(x, y);
            ivec2 relative = sourceTexel - regionOrigin;
            if (any(lessThan(sourceTexel, ivec2(0))) || any(greaterThanEqual(sourceTexel, sourceSize))
                    || any(lessThan(relative, ivec2(0))) || any(greaterThanEqual(relative, regionSize))) {
                hasEmptyTexel = true;
                continue;
            }
            float depth = texelFetch(ShadowSampler, sourceTexel, 0).r;
            if (depth >= 1.0 - 1.0e-7) {
                hasEmptyTexel = true;
                continue;
            }
            minimumDepth = min(minimumDepth, depth);
            maximumDepth = max(maximumDepth, depth);
            hasOccluder = true;
        }
    }
    if (!hasOccluder) {
        fragColor = vec4(encodeDepth16(1.0, false), encodeDepth16(0.0, true));
    } else {
        if (hasEmptyTexel) maximumDepth = 1.0;
        fragColor = vec4(encodeDepth16(minimumDepth, false), encodeDepth16(maximumDepth, true));
    }
}
