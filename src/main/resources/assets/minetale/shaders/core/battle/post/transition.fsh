#version 330

uniform sampler2D FreezeSampler;
uniform sampler2D CurrentSampler;

layout(std140) uniform TransitionUniform {
    float TransitionT;
};

in vec2 texCoord;
out vec4 fragColor;

void main(){
    vec4 freezeColor = texture(FreezeSampler, texCoord);
    vec4 currentColor = texture(CurrentSampler, texCoord);

    float t = clamp(TransitionT,0.0,1.0);

    vec4 mixedColor = mix(freezeColor, currentColor, t);

    fragColor = mixedColor;
}
