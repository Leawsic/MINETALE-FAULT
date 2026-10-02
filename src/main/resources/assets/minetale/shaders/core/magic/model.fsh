#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minetale:screen_door.glsl>

uniform sampler2D Sampler0;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) discard;
    // 瞳孔每面只有一个贴图像素，UV 遮罩会使整面跳变。两遍绘制共用屏幕点阵，孔洞与深度一致。
    minetale_screenDoor(vertexColor.a);
    color.rgb *= vertexColor.rgb * ColorModulator.rgb;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#ifndef EMISSIVE
    color.rgb *= lightMapColor.rgb;
#endif
    color.a = 1.0;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
