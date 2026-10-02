#version 330

uniform sampler2D ShadowSampler;
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

in vec2 gridCoordinate;
in vec4 lineColor;
in vec4 fillColor;
in vec3 worldPosition;
in vec4 shadowPosition;
out vec4 fragColor;

float shadowVisibility() {
    if (ShadowParams.y < 0.5) return 1.0;
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

float soulLightWeight() {
    if (SoulLightColorEnabled.w < 0.5) return 0.0;
    float normalizedDistance = clamp(
            distance(worldPosition, SoulLightPositionRadius.xyz) / SoulLightPositionRadius.w,
            0.0,
            1.0);
    float remaining = 1.0 - normalizedDistance;
    return remaining * remaining;
}

vec3 applySoulLight(vec3 baseColor, float shadowVisibility, float lightWeight) {
    // Soul 没有独立阴影图；其有限范围直接恢复亮度，避免全局阴影压黑发光区域。
    float effectiveVisibility = mix(shadowVisibility, 1.0, lightWeight);
    vec3 tinted = baseColor * effectiveVisibility
            * mix(vec3(1.0), SoulLightColorEnabled.rgb, lightWeight);
    float emissionStrength = max(SoulLightColorEnabled.w - 1.0, 0.0);
    return tinted + SoulLightColorEnabled.rgb * lightWeight * emissionStrength;
}

void main() {
    vec2 distanceToLine = abs(fract(gridCoordinate + 0.5) - 0.5);
    vec2 pixelFootprint = max(fwidth(gridCoordinate), vec2(0.000001));
    vec2 halfLineWidth = pixelFootprint * Params.z * 0.5;
    vec2 coverage = 1.0 - smoothstep(halfLineWidth, halfLineWidth + pixelFootprint, distanceToLine);
    float lineCoverage = max(coverage.x, coverage.y);
    vec4 surface = mix(fillColor, lineColor, lineCoverage);
    float visibility = shadowVisibility();
    float lightWeight = soulLightWeight();
    vec3 litSurface = applySoulLight(surface.rgb, visibility, lightWeight);
    // 网格先合入场景且不写深度，后续体积自然覆盖；此处保留材质原始 Alpha。
    fragColor = vec4(litSurface, surface.a);
}
