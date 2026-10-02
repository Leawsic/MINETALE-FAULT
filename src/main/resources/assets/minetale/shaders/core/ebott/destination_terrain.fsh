#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
layout(std140) uniform EbottBarrierMask {
    vec4 EbottMask;
    vec4 EbottAperture;
};
layout(std140) uniform EbottSourceFog {
    vec4 EbottSourceFogColor;
    float EbottSourceFogEnvironmentalStart;
    float EbottSourceFogEnvironmentalEnd;
    float EbottSourceFogRenderDistanceStart;
    float EbottSourceFogRenderDistanceEnd;
    float EbottSourceFogSkyEnd;
    float EbottSourceFogCloudsEnd;
};

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in float ebottClipDistance;
in vec3 ebottRelativePosition;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    if (ebottClipDistance < 0.0) discard;

    float seamRayScale = abs(ebottRelativePosition.y) > 1.0e-6
            ? EbottAperture.z / ebottRelativePosition.y
            : -1.0;
    if (seamRayScale < 0.0 || seamRayScale > 1.0) discard;
    vec3 seamPosition = ebottRelativePosition * seamRayScale;
    vec2 localPosition = seamPosition.xz - EbottMask.yz;
    // MASK 与结界网格、竖井生成共用方块中心判定
    vec2 blockCenter = floor(localPosition + vec2(0.5));
    if (distance(blockCenter, EbottAperture.xy) > EbottMask.w) discard;

    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) discard;
#endif
    vec4 targetFoggedColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    // 主世界雾按穿过接缝的视线 mask 计算，不使用目标区块在接缝后的深度。
    fragColor = apply_fog(targetFoggedColor,
            fog_spherical_distance(seamPosition), fog_cylindrical_distance(seamPosition),
            EbottSourceFogEnvironmentalStart, EbottSourceFogEnvironmentalEnd,
            EbottSourceFogRenderDistanceStart, EbottSourceFogRenderDistanceEnd,
            EbottSourceFogColor);
}
