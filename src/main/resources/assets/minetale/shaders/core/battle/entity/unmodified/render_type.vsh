#version 330

// 原样传递冻结的 MultiBufferSource/RenderType 画面顶点。

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
out vec2 texCoord;
out vec4 vertexColor;
flat out int alphaMode;

void main() {
    texCoord = UV0;
    vertexColor = Color * ColorModulator;
    alphaMode = 1;
    gl_Position = ProjMat * ModelViewMat * vec4(Position + ModelOffset, 1.0);
}
