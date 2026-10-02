#version 330

uniform sampler2D Sampler0;

layout(std140) uniform ShakeUniform {
    vec2 ShakeOffset;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 sourceCoord = texCoord - ShakeOffset;
    if (any(lessThan(sourceCoord, vec2(0.0))) || any(greaterThan(sourceCoord, vec2(1.0)))) {
        fragColor = vec4(0.0);
        return;
    }
    fragColor = texture(Sampler0, sourceCoord);
}
