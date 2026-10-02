#version 330

uniform sampler2D Sampler0;

layout(std140) uniform PostProcessingUniform {
    vec4 BloomControl;
    vec4 PyramidStrategy;
    vec4 ResolutionControl;
};

in vec2 texCoord;
out vec4 fragColor;

vec4 sampleSafe(vec2 uv, vec2 texel) {
    return texture(Sampler0, clamp(uv, texel * 0.5, vec2(1.0) - texel * 0.5));
}

vec4 tentFourTap(vec2 texel) {
    vec2 offset = texel * 0.5;
    return (
            sampleSafe(texCoord + vec2(-offset.x, -offset.y), texel)
            + sampleSafe(texCoord + vec2( offset.x, -offset.y), texel)
            + sampleSafe(texCoord + vec2(-offset.x,  offset.y), texel)
            + sampleSafe(texCoord + vec2( offset.x,  offset.y), texel)
    ) * 0.25;
}

vec4 tentNineTap(vec2 texel) {
    vec4 center = sampleSafe(texCoord, texel) * 4.0;
    vec4 cardinal = (
            sampleSafe(texCoord + vec2( texel.x, 0.0), texel)
            + sampleSafe(texCoord + vec2(-texel.x, 0.0), texel)
            + sampleSafe(texCoord + vec2(0.0,  texel.y), texel)
            + sampleSafe(texCoord + vec2(0.0, -texel.y), texel)
    ) * 2.0;
    vec4 diagonal =
            sampleSafe(texCoord + vec2( texel.x,  texel.y), texel)
            + sampleSafe(texCoord + vec2(-texel.x,  texel.y), texel)
            + sampleSafe(texCoord + vec2( texel.x, -texel.y), texel)
            + sampleSafe(texCoord + vec2(-texel.x, -texel.y), texel);
    return (center + cardinal + diagonal) * (1.0 / 16.0);
}

void main() {
    ivec2 sourceSize = textureSize(Sampler0, 0);
    vec2 texel = 1.0 / vec2(sourceSize);
    bool firstLevel = all(equal(sourceSize, ivec2(ResolutionControl.xy)));
    fragColor = firstLevel && PyramidStrategy.x > 4.5
            ? tentNineTap(texel)
            : tentFourTap(texel);
}
