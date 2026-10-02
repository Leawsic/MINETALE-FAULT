#version 330

// 挤出图像保留正反面贴图亮度、固定压暗侧面，只叠加全局阴影与 Soul 点光/点阴影。

uniform sampler2D Sampler0;
uniform sampler2D ShadowSampler;
uniform sampler2D PointShadowAtlas;
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

in vec2 texCoord;
in vec4 vertexColor;
in vec3 worldPosition;
in vec3 worldNormal;
in vec4 shadowPosition;
flat in int alphaMode;
flat in float sideFace;
flat in float shadowReceiver;
out vec4 fragColor;

#moj_import <minetale:battle/soul_point_surface_light.glsl>

// 每个 PCF tap 按 shadow UV 深度梯度校正接收面
vec2 receiverPlaneDepthGradient(vec3 projected) {
    vec3 projectedDx = dFdx(projected);
    vec3 projectedDy = dFdy(projected);
    float determinant = projectedDx.x * projectedDy.y - projectedDx.y * projectedDy.x;
    if (abs(determinant) <= 1.0e-10) return vec2(0.0);
    return vec2(
            projectedDx.z * projectedDy.y - projectedDx.y * projectedDy.z,
            projectedDx.x * projectedDy.z - projectedDx.z * projectedDy.x)
            / determinant;
}

float shadowVisibility() {
    if (ShadowParams.y < 0.5 || shadowReceiver < 0.5) return 1.0;
    vec3 projected = shadowPosition.xyz / shadowPosition.w;
    projected = projected * 0.5 + 0.5;
    vec2 depthGradient = receiverPlaneDepthGradient(projected);
    if (projected.x <= 0.0 || projected.x >= 1.0 || projected.y <= 0.0 || projected.y >= 1.0 || projected.z <= 0.0 || projected.z >= 1.0) return 1.0;
    float lit = 0.0;
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            vec2 offset = vec2(x, y) * ShadowParams.x;
            float closest = texture(ShadowSampler, projected.xy + offset).r;
            float receiverDepth = projected.z + dot(depthGradient, offset);
            lit += receiverDepth - SpecularShadow.z <= closest ? 1.0 : 1.0 - SpecularShadow.w;
        }
    }
    return lit / 9.0;
}

void main() {
    vec4 sampled = texture(Sampler0, texCoord) * vertexColor;
    if (sampled.a <= 0.0) discard;
    float sideBrightness = 1.0 - ShadowParams.z * clamp(sideFace, 0.0, 1.0);
    vec3 baseColor = sampled.rgb * sideBrightness * shadowVisibility();
    fragColor = vec4(clamp(applySoulPointSurfaceLight(
            baseColor, worldPosition, normalize(worldNormal), shadowReceiver), 0.0, 1.0), 1.0);
}
