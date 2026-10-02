#version 330

uniform sampler2D PreviousPointMinMax;

layout(std140) uniform SoulPointMinMaxUniform {
    vec4 SourceTargetSize;
    vec4 FaceLayout;
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
    int sourceFaceSize = int(FaceLayout.x + 0.5);
    int targetFaceSize = int(FaceLayout.z + 0.5);
    ivec2 face = outputTexel / targetFaceSize;
    ivec2 localOutput = outputTexel - face * targetFaceSize;
    ivec2 sourceFaceOrigin = face * sourceFaceSize;
    ivec2 childOrigin = localOutput * 4;

    float minimumDepth = 1.0;
    float maximumDepth = 1.0;
    bool first = true;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            ivec2 localChild = childOrigin + ivec2(x, y);
            float childMinimum = 1.0;
            float childMaximum = 1.0;
            if (all(lessThan(localChild, ivec2(sourceFaceSize)))) {
                vec4 encoded = texelFetch(
                        PreviousPointMinMax,
                        sourceFaceOrigin + localChild,
                        0);
                childMinimum = decodeDepth16(encoded.rg);
                childMaximum = decodeDepth16(encoded.ba);
            }
            if (first) {
                minimumDepth = childMinimum;
                maximumDepth = childMaximum;
                first = false;
            } else {
                minimumDepth = min(minimumDepth, childMinimum);
                maximumDepth = max(maximumDepth, childMaximum);
            }
        }
    }
    fragColor = vec4(
            encodeDepth16(minimumDepth, false),
            encodeDepth16(maximumDepth, true));
}
