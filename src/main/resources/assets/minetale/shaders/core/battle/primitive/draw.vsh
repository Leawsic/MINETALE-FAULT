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
in vec2 UV0;
out vec2 texCoord;
out vec4 vertexColor;
flat out int alphaMode;

void main() {
    vec4 world = Model * vec4(Position, 1.0);
    texCoord = mix(UvRect.xy, UvRect.zw, UV0);
    vertexColor = Color;
    alphaMode = int(Params.w + 0.5);
    gl_Position = Projection * View * world;
}
