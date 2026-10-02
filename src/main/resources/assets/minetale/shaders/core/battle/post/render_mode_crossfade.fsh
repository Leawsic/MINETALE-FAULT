#version 330

uniform sampler2D UnmodifiedSampler;
uniform sampler2D RenderedSampler;

layout(std140) uniform RenderModeCrossfadeUniform {
    float RenderedBlend;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 unmodified = texture(UnmodifiedSampler, texCoord);
    vec4 rendered = texture(RenderedSampler, texCoord);
    fragColor = mix(unmodified, rendered, clamp(RenderedBlend, 0.0, 1.0));
}
