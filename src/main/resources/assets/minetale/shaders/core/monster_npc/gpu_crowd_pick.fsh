#version 330

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 pickData;
in vec3 interactionRelativePosition;
in float interactionReach;
out vec4 fragColor;

void main() {
    if (texture(Sampler0, texCoord0).a < 0.1
            || interactionReach <= 0.0
            || length(interactionRelativePosition) > interactionReach) {
        discard;
    }
    fragColor = pickData;
}
