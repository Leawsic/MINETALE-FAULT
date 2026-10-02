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
out vec4 vertexColor;
out vec3 worldPosition;
out vec4 shadowPosition;
flat out float shadowReceiver;
out vec3 frameLocalPosition;

void main() {
    vec4 world = Model * vec4(Position, 1.0);
    vertexColor = Color;
    frameLocalPosition = Position;
    worldPosition = world.xyz;
    shadowPosition = LightViewProjection * world;
    shadowReceiver = EndPoint.w;
    gl_Position = Projection * View * world;
}
