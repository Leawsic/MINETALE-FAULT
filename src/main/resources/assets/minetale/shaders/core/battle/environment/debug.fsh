#version 330

uniform sampler2D EnvironmentDebugSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(EnvironmentDebugSampler, texCoord).rgb, 1.0);
}
