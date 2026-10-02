#version 330

uniform sampler2D SourceSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 sourceSize = vec2(textureSize(SourceSampler, 0));
    float squareSize = min(sourceSize.x, sourceSize.y);
    vec2 squareScale = vec2(squareSize) / sourceSize;
    vec2 squareOffset = (vec2(1.0) - squareScale) * 0.5;
    vec2 sourceUv = squareOffset + texCoord * squareScale;

    // 16 taps 覆盖当前输出像素的源区域。
    vec2 footprint = fwidth(sourceUv);
    vec3 accumulated = vec3(0.0);
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            vec2 offset = (vec2(x, y) + vec2(0.5)) * 0.25 - vec2(0.5);
            accumulated += texture(SourceSampler, sourceUv + footprint * offset).rgb;
        }
    }
    fragColor = vec4(accumulated * (1.0 / 16.0), 1.0);
}
