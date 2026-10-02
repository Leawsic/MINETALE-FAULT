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
in vec4 Color;
in vec3 Normal;
out vec2 texCoord;
out vec4 vertexColor;
out vec3 worldPosition;
out vec3 worldNormal;
out vec4 shadowPosition;
flat out int alphaMode;
flat out float sideFace;
flat out float shadowReceiver;

void main() {
    vec4 world = Model * vec4(Position, 1.0);
    texCoord = UV0;
    vertexColor = Color * Tint;
    worldPosition = world.xyz;
    worldNormal = normalize(mat3(transpose(inverse(Model))) * Normal);
    shadowPosition = LightViewProjection * world;
    alphaMode = int(Params.w + 0.5);
    sideFace = 1.0 - abs(Normal.y);
    shadowReceiver = EndPoint.w;
    gl_Position = Projection * View * world;
}
