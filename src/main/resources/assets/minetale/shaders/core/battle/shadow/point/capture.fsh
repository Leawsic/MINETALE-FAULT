#version 330

#if defined(SOUL_POINT_TEXTURED_CASTER)
uniform sampler2D Sampler0;
#elif !defined(SOUL_POINT_FRAME_CASTER)
#error "Soul point-shadow capture requires a caster shader define"
#endif

layout(std140) uniform SoulPointShadowUniform {
    mat4 FaceViewProjection;
    vec4 SoulPositionRadius;
};

#if defined(SOUL_POINT_TEXTURED_CASTER)
in vec2 texCoord;
in vec4 vertexColor;
flat in int alphaMode;
#endif
in vec3 worldPosition;
out vec4 fragColor;

void main() {
#if defined(SOUL_POINT_TEXTURED_CASTER)
    vec4 sampled = texture(Sampler0, texCoord) * vertexColor;
    if ((alphaMode == 1 && sampled.a < 0.5)
            || (alphaMode == 2 && sampled.a <= 0.0)
            || (alphaMode == 0 && sampled.a <= 0.001)) {
        discard;
    }
#endif
    float radialDepth = clamp(
            distance(worldPosition, SoulPositionRadius.xyz) / SoulPositionRadius.w,
            0.0,
            1.0);
    fragColor = vec4(radialDepth, radialDepth, radialDepth, 1.0);
}
