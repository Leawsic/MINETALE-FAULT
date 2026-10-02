#version 330

uniform sampler2D CoarseSampler;
uniform sampler2D FineSampler;

layout(std140) uniform PostProcessingUniform {
    vec4 BloomControl;
    vec4 PyramidStrategy;
    vec4 ResolutionControl;
};

in vec2 texCoord;
out vec4 fragColor;

vec4 sampleCoarseSafe(vec2 uv, vec2 texel) {
    return texture(CoarseSampler, clamp(uv, texel * 0.5, vec2(1.0) - texel * 0.5));
}

void main() {
    ivec2 coarseSize = textureSize(CoarseSampler, 0);
    ivec2 fineSize = textureSize(FineSampler, 0);
    vec2 coarseTexel = 1.0 / vec2(coarseSize);
    vec2 offset = coarseTexel * 0.5;

    // 四次双线性读取合成为归一化 3×3 [1 2 1] 帐篷核。
    vec4 reconstructedCoarse = (
            sampleCoarseSafe(texCoord + vec2(-offset.x, -offset.y), coarseTexel)
            + sampleCoarseSafe(texCoord + vec2( offset.x, -offset.y), coarseTexel)
            + sampleCoarseSafe(texCoord + vec2(-offset.x,  offset.y), coarseTexel)
            + sampleCoarseSafe(texCoord + vec2( offset.x,  offset.y), coarseTexel)
    ) * 0.25;
    vec4 fine = texture(
            FineSampler,
            clamp(texCoord, 0.5 / vec2(fineSize), vec2(1.0) - 0.5 / vec2(fineSize))
    );

    float fineScale = max(
            ResolutionControl.x / float(fineSize.x),
            ResolutionControl.y / float(fineSize.y)
    );
    float coarseScale = max(
            ResolutionControl.x / float(coarseSize.x),
            ResolutionControl.y / float(coarseSize.y)
    );
    float coarseWeight = smoothstep(fineScale, coarseScale, BloomControl.z);

    // 各级保持归一化，半径只选择相邻尺度贡献。
    fragColor = mix(fine, reconstructedCoarse, coarseWeight);
}
