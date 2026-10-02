#version 330

uniform sampler2D SceneSampler;
uniform sampler2D BloomSampler;

layout(std140) uniform PostProcessingUniform {
    vec4 BloomControl;
    vec4 PyramidStrategy;
    vec4 ResolutionControl;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    vec4 filteredBloom = texture(BloomSampler, texCoord);

    // 仅补偿跨过多个 RGBA8 量化级的稀疏覆盖，并将增益限制在 1.5 倍。
    float support = clamp(filteredBloom.a, 0.0, 1.0);
    float quantizationGate = smoothstep(1.0 / 255.0, 4.0 / 255.0, support);
    float sparseFactor = 1.0 - smoothstep(2.0 / 255.0, 0.25, support);
    float coverageCompensation = 1.0 + 0.5 * quantizationGate * sparseFactor;
    vec3 bloomEnergy = min(filteredBloom.rgb * coverageCompensation, vec3(1.0))
            * max(BloomControl.y, 0.0);

    // LDR 映射只作用于 Bloom 新增能量，原场景 Alpha 与透明轮廓保持不变。
    vec3 mapped = scene.rgb + (vec3(scene.a) - scene.rgb) * (1.0 - exp(-bloomEnergy));
    fragColor = vec4(min(mapped, vec3(scene.a)), scene.a);
}
