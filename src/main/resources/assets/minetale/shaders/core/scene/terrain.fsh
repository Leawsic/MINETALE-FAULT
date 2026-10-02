#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
uniform sampler2D SceneEmission;
in vec3 cameraRelativePosition;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 surfaceNormal;
in vec4 surfaceTangent;
out vec4 fragColor;

void main() {
    vec3 normal=normalize(surfaceNormal);
    // 方向明暗沿用方块六面的强度，并与最终体素法线连续混合。
    float shade=dot(normal*normal,vec3(.6,normal.y<0.0?.5:1.0,.8));
    float emission=texture(SceneEmission,texCoord0).r;
    vec4 color=texture(Sampler0,texCoord0)*ColorModulator;
    color.rgb*=mix(vertexColor.rgb*shade,vec3(1),emission);
    color.a*=vertexColor.a;
    // 贪婪合并的大三角形上，插值标量雾距离会整面拉平；
    // 位置经透视校正插值后逐片元重算，雾恢复为随距离连续变化。
    fragColor=apply_fog(color,
        fog_spherical_distance(cameraRelativePosition),
        fog_cylindrical_distance(cameraRelativePosition),
        FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd,FogColor);
}
