#version 330

uniform sampler2D PointShadowAtlas;

layout(std140) uniform SoulPointMinMaxUniform {
    vec4 SourceTargetSize;
    vec4 FaceLayout;
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
    int sourceFaceSize = int(FaceLayout.x + 0.5);
    int border = int(FaceLayout.y + 0.5);
    int targetFaceSize = int(FaceLayout.z + 0.5);
    ivec2 face = outputTexel / targetFaceSize;
    ivec2 localOutput = outputTexel - face * targetFaceSize;
    int sourceTileSize = sourceFaceSize + border * 2;
    ivec2 sourceFaceOrigin = face * sourceTileSize + ivec2(border);
    ivec2 blockOrigin = localOutput * 4;

    float minimumDepth = 1.0;
    float maximumDepth = 1.0;
    bool first = true;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            ivec2 localSource = blockOrigin + ivec2(x, y);
            float depth = 1.0;
            if (all(lessThan(localSource, ivec2(sourceFaceSize)))) {
                depth = texelFetch(PointShadowAtlas, sourceFaceOrigin + localSource, 0).r;
            }
            if (first) {
                minimumDepth = depth;
                maximumDepth = depth;
                first = false;
            } else {
                minimumDepth = min(minimumDepth, depth);
                maximumDepth = max(maximumDepth, depth);
            }
        }
    }
    fragColor = vec4(
            encodeDepth16(minimumDepth, false),
            encodeDepth16(maximumDepth, true));
}
