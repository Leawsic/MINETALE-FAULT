#version 330

// AlphaMode：0 为 translucent，1 为标准 cutout，2 为任意非零 Alpha 的挤出表面。

uniform sampler2D Sampler0;
in vec2 texCoord;
in vec4 vertexColor;
flat in int alphaMode;
out vec4 fragColor;

void main() {
    vec4 sampled = texture(Sampler0, texCoord) * vertexColor;
    if (alphaMode == 0) {
        if (sampled.a <= 0.001) discard;
        fragColor = sampled;
        return;
    }
    if ((alphaMode == 1 && sampled.a < 0.5) || (alphaMode == 2 && sampled.a <= 0.0)) discard;
    fragColor = vec4(sampled.rgb, 1.0);
}
