#version 330

uniform sampler2D ShadowSampler;
uniform sampler2D PointShadowAtlas;
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
layout(std140) uniform SoulPointLightUniform {
    mat4 PointFaceViewProjection[6];
    vec4 SoulPointPositionCaptureRadius;
    vec4 SoulPointColorSurfaceRadius;
    vec4 SoulPointSurfaceControl;
    vec4 SoulPointAtlasLayout;
};

in vec4 vertexColor;
in vec3 worldPosition;
in vec4 shadowPosition;
flat in float shadowReceiver;
in vec3 frameLocalPosition;
out vec4 fragColor;

#moj_import <minetale:battle/frame_corner_visibility.glsl>
#moj_import <minetale:battle/dither/coverage.glsl>
#moj_import <minetale:battle/soul_point_surface_light.glsl>
#moj_import <minetale:battle/environment/field.glsl>
#moj_import <minetale:battle/environment/lighting.glsl>

float shadowVisibility() {
    if (ShadowParams.y < 0.5 || shadowReceiver < 0.5) return 1.0;
    vec3 projected = shadowPosition.xyz / shadowPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.x <= 0.0 || projected.x >= 1.0 || projected.y <= 0.0 || projected.y >= 1.0 || projected.z <= 0.0 || projected.z >= 1.0) return 1.0;
    float lit = 0.0;
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float closest = texture(ShadowSampler, projected.xy + vec2(x, y) * ShadowParams.x).r;
            lit += projected.z - SpecularShadow.z <= closest ? 1.0 : 1.0 - SpecularShadow.w;
        }
    }
    return lit / 9.0;
}

vec3 frameWorldNormal() {
    vec3 geometric = cross(dFdx(worldPosition), dFdy(worldPosition));
    float magnitude = length(geometric);
    if (magnitude <= 1.0e-8) return vec3(0.0, 1.0, 0.0);
    vec3 normal = geometric / magnitude;
    return gl_FrontFacing ? normal : -normal;
}

void main() {
    float opacity = vertexColor.a * blendedFrameCornerOpacity();
    minetaleApplyOrderedCoverage4x4(opacity, gl_FragCoord.xy);
    vec3 normal = frameWorldNormal();
    vec3 tintedColor = minetaleApplyEnvironmentDyeOnly(
            vertexColor.rgb * shadowVisibility(),
            worldPosition,
            normal);
    vec3 litColor = applySoulPointSurfaceLight(
            tintedColor,
            worldPosition,
            normal,
            shadowReceiver);
    fragColor = vec4(clamp(litColor, 0.0, 1.0), 1.0);
}
