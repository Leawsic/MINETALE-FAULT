#version 330

// Rendered BattleEntity 的 ADS、shadow 与传统复合高光贴图材质。

uniform sampler2D Sampler0;
uniform sampler2D SpecularSampler;
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
flat in float shadowReceiver;
out vec4 fragColor;

#moj_import <minetale:battle/soul_point_surface_light.glsl>
#moj_import <minetale:battle/environment/field.glsl>
#moj_import <minetale:battle/environment/lighting.glsl>

float shadowVisibility(vec3 normal) {
    if (ShadowParams.y < 0.5 || shadowReceiver < 0.5) return 1.0;
    vec3 projected = shadowPosition.xyz / shadowPosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.x <= 0.0 || projected.x >= 1.0 || projected.y <= 0.0 || projected.y >= 1.0 || projected.z <= 0.0 || projected.z >= 1.0) return 1.0;
    float slopeBias = SpecularShadow.z * max(1.0, 1.0 - dot(normal, -LightDirectionAmbient.xyz));
    float lit = 0.0;
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float closest = texture(ShadowSampler, projected.xy + vec2(x, y) * ShadowParams.x).r;
            lit += projected.z - slopeBias <= closest ? 1.0 : 1.0 - SpecularShadow.w;
        }
    }
    return lit / 9.0;
}

vec3 applyMaterial(vec3 baseColor, vec3 material) {
    vec3 normal = normalize(worldNormal);
    vec3 toLight = -normalize(LightDirectionAmbient.xyz);
    float diffuse = max(dot(normal, toLight), 0.0);
    float visibility = shadowVisibility(normal);
    vec3 toCamera = normalize(CameraPositionDiffuse.xyz - worldPosition);
    vec3 reflected = reflect(normalize(LightDirectionAmbient.xyz), normal);

    float smoothness = clamp(material.r, 0.0, 1.0);
    float shininess = mix(1.0, max(SpecularShadow.y, 1.0), smoothness);
    float specular = diffuse > 0.0 ? pow(max(dot(toCamera, reflected), 0.0), shininess) : 0.0;
    vec3 litColor = baseColor * (
            vec3(visibility * CameraPositionDiffuse.w * diffuse)
    )
            + vec3(visibility * SpecularShadow.x * clamp(material.g, 0.0, 1.0) * specular);
    litColor = minetaleApplyEnvironmentTint(litColor, worldPosition, normal);

    // 自发光绕过 ambient/diffuse/shadow，保留少量法线明暗识别外形。
    vec3 emissiveColor = baseColor * (0.9 + 0.1 * diffuse);
    return mix(litColor, emissiveColor, clamp(material.b, 0.0, 1.0));
}

void main() {
    vec4 sampled = texture(Sampler0, texCoord) * vertexColor;
    if (alphaMode == 0) {
        if (sampled.a <= 0.001) discard;
    } else if ((alphaMode == 1 && sampled.a < 0.5) || (alphaMode == 2 && sampled.a <= 0.0)) {
        discard;
    }
    float outputAlpha = alphaMode == 0 ? sampled.a : 1.0;
    vec3 material = texture(SpecularSampler, texCoord).rgb;
    vec3 litColor = applySoulPointSurfaceLight(
            applyMaterial(sampled.rgb, material),
            worldPosition,
            normalize(worldNormal),
            shadowReceiver);
    fragColor = vec4(clamp(litColor, 0.0, 1.0), outputAlpha);
}
