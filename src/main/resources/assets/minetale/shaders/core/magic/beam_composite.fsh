#version 330
#moj_import <minecraft:fog.glsl>
uniform sampler2D CoreSampler;
uniform sampler2D CoreDepthSampler;
uniform sampler2D GlowSampler;
uniform sampler2D DistortionSampler;
uniform sampler2D SceneSampler;
layout(std140) uniform MagicBeam {
    mat4 ViewProjection;
    mat4 InverseViewProjection;
    vec4 OriginRadius;
    vec4 AxisLength;
    vec4 Viewport;
    vec4 Muzzle;
    vec4 Occlusion;
    vec4 Distortion;
};
in vec2 texCoord;
out vec4 fragColor;

vec2 velocity(vec2 uv) {
    vec4 lens = texture(DistortionSampler,uv);
    vec2 pixels = (vec2(dot(lens.rg,vec2(65280.0,255.0)),
                       dot(lens.ba,vec2(65280.0,255.0)))-32768.0)/32767.0*(Viewport.y/24.0);
    float limit = Viewport.y/60.0;
    pixels /= sqrt(1.0+dot(pixels,pixels)/(limit*limit));
    vec2 edge = min(uv,1.0-uv)*Viewport.xy;
    return pixels/Viewport.xy*smoothstep(0.0,limit*3.0,min(edge.x,edge.y));
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 core = texelFetch(CoreSampler, pixel, 0);
    float coverage = texelFetch(CoreDepthSampler, pixel, 0).r < 1.0 ? 1.0 : 0.0;
    vec3 glow = vec3(0.0);
#ifndef SCENE_SURFACE
    vec2 halfTexel = 0.5 / vec2(textureSize(GlowSampler, 0));
    glow = texture(GlowSampler, clamp(texCoord, halfTexel, 1.0-halfTexel)).rgb * 0.12;
#endif
    if (coverage > 0.0) {
#ifdef SCENE_SURFACE
        fragColor = vec4(0.0);
#else
        fragColor = vec4(mix(FogColor.rgb, core.rgb, core.a), 1.0);
#endif
        return;
    }
    vec2 texel = 1.0 / vec2(textureSize(CoreSampler, 0));
    vec2 offset = vec2(0.0);
    if (Distortion.y > 0.0) {
        // 沿平滑场分步移动采样坐标。单步小于场的梯度界，避免强场翻折。
        // 四分之一目标使用相邻 13 tap 核；高度越大，累计幅值越大，步数也同比增加。
        float steps = max(8.0,ceil(Viewport.y/90.0));
        vec2 source = texCoord;
        for (int i = 0; i < int(steps); ++i) source += velocity(source)/steps;
        offset = source-texCoord;
    }
    if (dot(offset,offset) == 0.0) {
        fragColor = vec4(glow,0.0);
    } else {
        vec2 h = texel*0.5;
        fragColor = vec4(texture(SceneSampler,clamp(texCoord+offset,h,1.0-h)).rgb+glow,1.0);
    }
}
