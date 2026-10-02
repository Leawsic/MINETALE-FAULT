#version 330

layout(std140) uniform DrawUniform {
    mat4 View;
    mat4 Projection;
    mat4 Model;
    vec4 Tint;
    vec4 UvRect;
    vec4 Params;
    vec4 StartPoint;
    vec4 EndPoint;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
out vec2 texCoord;
out vec4 vertexColor;

void main() {
    vec4 world = Model * vec4(Position, 1.0);
    texCoord = UV0;
    vertexColor = Color;
    gl_Position = Projection * View * world;
}
