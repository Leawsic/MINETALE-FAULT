#version 330

uniform sampler2D Sampler0;

layout(std140) uniform FlashUniform {
    vec4 FlashColor;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = mix(texture(Sampler0, texCoord), vec4(FlashColor.rgb, 1.0), FlashColor.a);
}
