#version 330

#moj_import <minecraft:fog.glsl>

uniform sampler2D SceneDepthSampler;
uniform sampler2D BeamDepthSampler;
uniform sampler2D BeforeHandDepthSampler;
uniform sampler2D AfterHandDepthSampler;
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
in vec3 localPosition;
in vec3 surfaceNormal;
flat in int surfaceKind;
out vec4 fragColor;

float surfaceVisibility(ivec2 cell, vec2 local, float z, vec3 right, vec3 up) {
    ivec2 size = textureSize(BeamDepthSampler,0);
    cell = clamp(cell,ivec2(0),size-1);
    vec4 encoded = texelFetch(BeamDepthSampler,cell,0);
    float stop = dot(encoded.rgb,vec3(16711680.0,65280.0,255.0))/512.0;
    int normal = int(round(encoded.a*255.0))-1;
    if (normal >= 0) {
        vec2 center = ((vec2(cell)+0.5)/vec2(size)*2.0-1.0)*Muzzle.z;
        vec3 delta = right*(local.x-center.x)+up*(local.y-center.y);
        if (abs(AxisLength[normal]) > 0.00001) stop -= delta[normal]/AxisLength[normal];
    }
    // 收口从各自的墙面接触处同步回缩；只缩短最大射程会让近墙处的束尾滞留。
    // 先围绕固定发射基准收束，再换算到表现炮口；动画后退不移动射程或墙面接触点。
    stop = max(0.0,stop) * Muzzle.w + Occlusion.x;
    return 1.0-smoothstep(stop-0.015625,stop,z);
}

float beamVisibility(vec3 radial, float z, vec3 right, vec3 up) {
    vec2 local = vec2(dot(radial,right),dot(radial,up));
    vec2 pixel = (local/(2.0*Muzzle.z)+0.5)*vec2(textureSize(BeamDepthSampler,0))-0.5;
    ivec2 cell = ivec2(floor(pixel));
    vec2 f = fract(pixel);
    // 先比较，再混合可见性；直接混合距离会在窄柱边缘制造不存在的斜面和漏光。
    return mix(mix(surfaceVisibility(cell,local,z,right,up),surfaceVisibility(cell+ivec2(1,0),local,z,right,up),f.x),
               mix(surfaceVisibility(cell+ivec2(0,1),local,z,right,up),surfaceVisibility(cell+ivec2(1,1),local,z,right,up),f.x),f.y);
}

void main() {
#ifdef DISTORTION
    // 视线到有限线段的距离统一描述束身与端点
    vec2 uv = gl_FragCoord.xy/ceil(Viewport.xy/4.0);
    // GameRenderer 把走路摇晃的平移也乘进投影
    // 用近裁剪面和中间深度的两点还原射线，以防远裁剪面的齐次除法精度损失。
    vec4 nearPoint = InverseViewProjection*vec4(uv*2.0-1.0,-1.0,1.0);
    vec4 middlePoint = InverseViewProjection*vec4(uv*2.0-1.0,0.0,1.0);
    vec3 rayOrigin = nearPoint.xyz/nearPoint.w;
    vec3 ray = normalize(middlePoint.xyz/middlePoint.w-rayOrigin);
    vec3 axis = AxisLength.xyz;
    vec3 origin = OriginRadius.xyz;
    float along = dot(ray,axis);
    float denominator = max(1.0-along*along,0.000001);
    vec3 relativeOrigin = origin-rayOrigin;
    float z = clamp((along*dot(ray,relativeOrigin)-dot(axis,relativeOrigin))/denominator,0.0,AxisLength.w);
    vec3 center = origin+axis*z;
    float travel = max(0.0,dot(center-rayOrigin,ray));
    vec3 position = rayOrigin+ray*travel;
    vec3 radial = position-center;
    float distance = length(radial);
    float radius = OriginRadius.w;
    float outer = radius+Distortion.x;
    float envelope = 1.0-smoothstep(radius,outer,distance);
    // 轴心与相机附近也归零，避免方向归一化或投影除法的奇点。
    envelope *= smoothstep(0.0,radius,distance)*smoothstep(0.0,outer,travel);
    // 近处包络的屏幕矩形常覆盖整屏；零贡献像素无需继续读取传播图和 4×4 场景深度。
    if (envelope == 0.0) discard;
    vec3 right = normalize(cross(axis,abs(axis.y)<0.9 ? vec3(0,1,0) : vec3(1,0,0)));
    vec3 up = cross(axis,right);
    vec3 local = position-origin;
    vec3 beamRadial = local-axis*dot(local,axis);
    envelope *= beamVisibility(beamRadial*radius/outer,max(0.0,z-0.015625),right,up);
    vec4 clip = ViewProjection*vec4(position,1.0);
    float beamDepth = clip.z/max(clip.w,0.00001)*0.5+0.5;
    // 四分之一目标必须平均整个覆盖区域的可见性；单点深度会把 TAA 的亚像素抖动放大成 4 像素跳变。
    ivec2 sceneSize = textureSize(SceneDepthSampler,0);
    vec2 footprint = Viewport.xy/ceil(Viewport.xy/4.0);
    vec2 base = floor(gl_FragCoord.xy)*footprint;
    float visible = 0.0;
    for (int y = 0; y < 4; ++y) for (int x = 0; x < 4; ++x) {
        ivec2 p = clamp(ivec2(base+(vec2(x,y)+0.5)*footprint/4.0),ivec2(0),sceneSize-1);
        bool behind = texelFetch(SceneDepthSampler,p,0).r >= beamDepth;
        if (Occlusion.y > 0.0) behind = behind && texelFetch(AfterHandDepthSampler,p,0).r
                >= texelFetch(BeforeHandDepthSampler,p,0).r;
        visible += behind ? 1.0 : 0.0;
    }
    envelope *= visible/16.0;
    // 相位锚定固定传播原点与静态口径。
    vec3 wavePosition = (local-axis*Occlusion.x)*(2.2/Muzzle.z);
    float wave = sin(dot(wavePosition,vec3(0.7,1.1,0.9))-Occlusion.z);
    float strength = Distortion.y*(1.0+Distortion.w*wave)*pow(envelope,Distortion.z);
    vec3 displaced = position+radial/max(distance,0.00001)*strength;
    vec4 shifted = ViewProjection*vec4(displaced,1.0);
    vec2 offset = (shifted.xy/max(shifted.w,0.00001)-clip.xy/max(clip.w,0.00001))*0.5;
    float fog = total_fog_value(fog_spherical_distance(position),fog_cylindrical_distance(position),
            FogEnvironmentalStart,FogEnvironmentalEnd,FogRenderDistanceStart,FogRenderDistanceEnd);
    offset *= 1.0-fog*FogColor.a;
    // RGBA8 分别累加 x+/y+/x-/y-
    vec2 value = offset*Viewport.xy/(Viewport.y/24.0);
    fragColor = vec4(max(value,vec2(0.0)),max(-value,vec2(0.0)));
#else
#ifndef MASK_ONLY
    ivec2 size = textureSize(SceneDepthSampler, 0);
    // 场景深度须与光束使用相同的窗口坐标
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
    // 场景保留透明地形前的深度；核心之间由固定功能深度测试处理
    if (gl_FragCoord.z > texelFetch(SceneDepthSampler, pixel, 0).r) discard;
    if (Occlusion.y > 0.0 && texelFetch(AfterHandDepthSampler, pixel, 0).r
            < texelFetch(BeforeHandDepthSampler, pixel, 0).r) discard;
#endif

    vec3 axis = AxisLength.xyz;
    vec3 right = normalize(cross(axis, abs(axis.y) < 0.9 ? vec3(0,1,0) : vec3(1,0,0)));
    vec3 up = cross(axis, right);
    float z = dot(localPosition, axis);
    vec3 radial = localPosition - z * axis;
    // 尾盖在射程边界上，应从其内侧判断传播可见性
    bool endCap = surfaceKind == 1 && dot(surfaceNormal, axis) > 0.5;
    if (beamVisibility(radial, z - (endCap ? 0.015625 : 0.0), right, up) < 0.5) discard;
#ifdef MASK_ONLY
    // 光影包已提交核心颜色
    fragColor = vec4(1.0);
#else
    vec3 cameraRelative = localPosition + OriginRadius.xyz;
    vec3 view = -normalize(cameraRelative);
    vec3 viewRadial = view - dot(view, axis) * axis;
    float facing = abs(dot(normalize(surfaceNormal), viewRadial / max(length(viewRadial), 0.00001)));
    float r = sqrt(max(0.0, 1.0 - facing * facing));
    if (surfaceKind == 1) r = length(radial) / OriginRadius.w;
    if (surfaceKind == 2) r = 0.0;
    float textureZ = z * sqrt(0.25 * 2.2 / Muzzle.z);
    float waveZ = textureZ * 0.4;
    float waveTime = Viewport.z * 0.2;
    float wave = sin(waveZ * 7.0 - waveTime * 38.0 + sin(waveZ * 1.8 - waveTime * 12.0) * 0.5);
    float profile = r - 0.018 * wave;
    float shoulder = smoothstep(0.20, 0.85, profile);
    float rim = smoothstep(0.82, 1.0, profile);
    float join = Muzzle.x > 0.0 ? smoothstep(Muzzle.x * 0.8, Muzzle.x * 1.5, z - Muzzle.y) : 1.0;
    vec3 color = mix(vec3(1.0), vec3(0.96, 0.976, 1.0), shoulder * join);
    color = mix(color, vec3(0.88, 0.93, 1.0), rim * join);
    float fog = total_fog_value(fog_spherical_distance(cameraRelative), fog_cylindrical_distance(cameraRelative),
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    // RGB 保存自发光颜色，A 保存雾透射率。覆盖由深度标识
    fragColor = vec4(color, 1.0 - fog * FogColor.a);
#endif
#endif
}
