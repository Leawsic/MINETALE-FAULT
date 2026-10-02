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

layout(std140) uniform SoulPointShadowUniform {
    mat4 FaceViewProjection;
    vec4 SoulPositionRadius;
};

in vec3 Position;
#if defined(SOUL_POINT_TEXTURED_CASTER)
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
out vec2 texCoord;
out vec4 vertexColor;
flat out int alphaMode;
#elif !defined(SOUL_POINT_FRAME_CASTER)
#error "Soul point-shadow capture requires a caster shader define"
#endif
out vec3 worldPosition;

void main() {
    vec4 world = Model * vec4(Position, 1.0);
#if defined(SOUL_POINT_TEXTURED_CASTER)
    texCoord = UV0;
    vertexColor = Color * Tint;
    alphaMode = int(Params.w + 0.5);
#endif
    worldPosition = world.xyz;
    gl_Position = FaceViewProjection * world;
}
