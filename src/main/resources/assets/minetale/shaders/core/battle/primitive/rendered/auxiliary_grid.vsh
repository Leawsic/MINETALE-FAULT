#version 330

layout(std140) uniform DrawUniform {
    mat4 View;
    mat4 Projection;
    mat4 Model;
    vec4 Color;
    vec4 FillColor;
    vec4 Params;
    vec4 StartPoint;
    vec4 EndPoint;
};
layout(std140) uniform LightingUniform {
    mat4 LightViewProjection;
    vec4 LightDirectionAmbient;
    vec4 CameraPositionDiffuse;
    vec4 SpecularShadow;
    vec4 ShadowParams;
    vec4 SoulLightPositionRadius;
    vec4 SoulLightColorEnabled;
};

in vec3 Position;
in vec2 UV0;
out vec2 gridCoordinate;
out vec4 lineColor;
out vec4 fillColor;
out vec3 worldPosition;
out vec4 shadowPosition;

void main() {
    vec4 world = Model * vec4(Position, 1.0);
    vec2 span = vec2(length(Model[0].xyz), length(Model[1].xyz));
    gridCoordinate = UV0 * span * Params.w;
    lineColor = Color;
    fillColor = FillColor;
    worldPosition = world.xyz;
    shadowPosition = LightViewProjection * world;
    gl_Position = Projection * View * world;
}
