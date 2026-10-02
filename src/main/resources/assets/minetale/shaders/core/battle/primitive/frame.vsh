#version 330

layout(std140) uniform DrawUniform {
    mat4 View;
    mat4 Projection;
    mat4 Model;
    vec4 Color;
    vec4 UvRect;
    vec4 Params;
    vec4 StartPoint;
    vec4 EndPoint;
};

in vec3 Position;
out vec4 vertexColor;
out vec3 frameLocalPosition;

void main() {
    vec4 world = Model * vec4(Position, 1.0);
    vertexColor = Color;
    frameLocalPosition = Position;
    gl_Position = Projection * View * world;
}
