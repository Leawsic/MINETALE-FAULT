#version 330

flat in int agentId;
flat in float rankDepth;

out vec4 fragColor;

vec2 encodeAgentId(int id) {
    int encoded = id + 1;
    return vec2(encoded / 256, encoded % 256) / 255.0;
}

void main() {
    fragColor = vec4(encodeAgentId(agentId), 0.0, 1.0);
}
