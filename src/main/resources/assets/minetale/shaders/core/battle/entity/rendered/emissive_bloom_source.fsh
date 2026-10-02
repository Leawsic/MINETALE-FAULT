#version 330

// Bloom 源只读取 *_s.png 的 B 通道，其他材质信息不得泄漏到此附件。

uniform sampler2D Sampler0;
uniform sampler2D SpecularSampler;

layout(std140) uniform PostProcessingUniform {
    vec4 BloomControl;
    vec4 PyramidStrategy;
    vec4 ResolutionControl;
};

in vec2 texCoord;
in vec4 vertexColor;
flat in int alphaMode;
out vec4 fragColor;

void main() {
    vec4 sampled = texture(Sampler0, texCoord) * vertexColor;
    if (alphaMode == 0) {
        if (sampled.a <= 0.001) discard;
    } else if ((alphaMode == 1 && sampled.a < 0.5) || (alphaMode == 2 && sampled.a <= 0.0)) {
        discard;
    }
    float coverage = alphaMode == 0 ? sampled.a : 1.0;
    float encodedEnergy = pow(
            clamp(texture(SpecularSampler, texCoord).b, 0.0, 1.0),
            max(BloomControl.x, 0.1)
    );
    float emissiveCoverage = coverage * encodedEnergy;

    // 中间 Alpha 记录自发光几何覆盖率，不参与最终 PiP Alpha。
    float emissiveSupport = encodedEnergy > 0.0 ? coverage : 0.0;
    fragColor = vec4(sampled.rgb * emissiveCoverage, emissiveSupport);
}
