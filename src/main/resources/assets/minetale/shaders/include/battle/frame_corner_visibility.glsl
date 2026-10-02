float frameCornerOpacity(vec3 cornerSign) {
    vec3 halfSize = UvRect.xyz;
    float halfThickness = UvRect.w * 0.5;
    vec3 cornerDistance = abs(frameLocalPosition - halfSize * cornerSign);
    vec3 edgeLength = max(halfSize * 2.0 - vec3(UvRect.w), vec3(0.000001));
    vec3 edgeProgress = clamp(
            (cornerDistance - vec3(halfThickness)) / edgeLength,
            0.0,
            1.0);
    // 遮挡棱边默认隐藏，仅在接近另一端角点时按二次曲线恢复可见度。
    const float transparentCore = 0.60;
    const float fadeEnd = 0.97;
    edgeProgress = clamp(
            (edgeProgress - vec3(transparentCore)) / (fadeEnd - transparentCore),
            0.0,
            1.0);
    edgeProgress *= edgeProgress;
    float tolerance = halfThickness + 0.00001;

    float opacity = 1.0;
    if (cornerDistance.y <= tolerance && cornerDistance.z <= tolerance) {
        opacity = min(opacity, edgeProgress.x);
    }
    if (cornerDistance.x <= tolerance && cornerDistance.z <= tolerance) {
        opacity = min(opacity, edgeProgress.y);
    }
    if (cornerDistance.x <= tolerance && cornerDistance.y <= tolerance) {
        opacity = min(opacity, edgeProgress.z);
    }
    return opacity;
}

float blendedFrameCornerOpacity() {
    if (StartPoint.w <= 0.0) return 1.0;

    vec3 cameraDirection = normalize(StartPoint.xyz);
    vec3 positiveWeight = smoothstep(vec3(-0.25), vec3(0.25), cameraDirection);
    float opacity = 0.0;
    for (int ix = 0; ix < 2; ++ix) {
        for (int iy = 0; iy < 2; ++iy) {
            for (int iz = 0; iz < 2; ++iz) {
                vec3 cornerSign = vec3(
                        ix == 0 ? -1.0 : 1.0,
                        iy == 0 ? -1.0 : 1.0,
                        iz == 0 ? -1.0 : 1.0);
                vec3 axisWeight = mix(vec3(1.0) - positiveWeight, positiveWeight,
                        vec3(float(ix), float(iy), float(iz)));
                opacity += axisWeight.x * axisWeight.y * axisWeight.z
                        * frameCornerOpacity(cornerSign);
            }
        }
    }
    return mix(1.0, opacity, StartPoint.w);
}
