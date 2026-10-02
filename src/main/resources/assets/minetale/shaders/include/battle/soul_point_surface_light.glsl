int soulPointDominantFace(vec3 value) {
    vec3 magnitude = abs(value);
    if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z) {
        return value.x >= 0.0 ? 0 : 1;
    }
    if (magnitude.y >= magnitude.z) return value.y >= 0.0 ? 2 : 3;
    return value.z >= 0.0 ? 4 : 5;
}

mat4 soulPointFaceMatrix(int face) {
    if (face == 0) return PointFaceViewProjection[0];
    if (face == 1) return PointFaceViewProjection[1];
    if (face == 2) return PointFaceViewProjection[2];
    if (face == 3) return PointFaceViewProjection[3];
    if (face == 4) return PointFaceViewProjection[4];
    return PointFaceViewProjection[5];
}

float soulPointRawDepth(int face, vec2 uv) {
    int faceSize = int(SoulPointAtlasLayout.x + 0.5);
    int border = int(SoulPointAtlasLayout.y + 0.5);
    int tileSize = int(SoulPointAtlasLayout.z + 0.5);
    vec2 safeUv = clamp(
            uv,
            vec2(0.5 / float(faceSize)),
            vec2(1.0 - 0.5 / float(faceSize)));
    ivec2 localTexel = ivec2(floor(safeUv * float(faceSize)));
    ivec2 faceTile = ivec2(face % 3, face / 3);
    return texelFetch(
            PointShadowAtlas,
            faceTile * tileSize + ivec2(border) + localTexel,
            0).r;
}

float soulPointSurfaceVisibility(vec3 surfaceWorldPosition, float shadowReceiver) {
    if (SoulPointSurfaceControl.z < 0.5 || shadowReceiver < 0.5) return 1.0;
    vec3 relative = surfaceWorldPosition - SoulPointPositionCaptureRadius.xyz;
    float radialDepth = length(relative)
            / max(SoulPointPositionCaptureRadius.w, 1.0e-6);
    int face = soulPointDominantFace(relative);
    vec4 clip = soulPointFaceMatrix(face) * vec4(surfaceWorldPosition, 1.0);
    if (clip.w <= 1.0e-8) return 1.0;
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    if (uv.x <= 0.0 || uv.x >= 1.0
            || uv.y <= 0.0 || uv.y >= 1.0) return 1.0;
    float storedDepth = soulPointRawDepth(face, uv);
    return radialDepth - SoulPointSurfaceControl.y <= storedDepth ? 1.0 : 0.0;
}

vec3 applySoulPointSurfaceLight(
        vec3 baseColor,
        vec3 surfaceWorldPosition,
        vec3 surfaceWorldNormal,
        float shadowReceiver
) {
    if (SoulPointSurfaceControl.z < 0.5) return baseColor;
    vec3 toSoul = SoulPointPositionCaptureRadius.xyz - surfaceWorldPosition;
    float distanceToSoul = length(toSoul);
    float surfaceRadius = SoulPointColorSurfaceRadius.w;
    if (distanceToSoul >= surfaceRadius) return baseColor;
    vec3 lightDirection = toSoul / max(distanceToSoul, 1.0e-6);
    float diffuse = max(dot(surfaceWorldNormal, lightDirection), 0.0);
    float visibility = soulPointSurfaceVisibility(surfaceWorldPosition, shadowReceiver);
    const float coreRadius = 0.12;
    float coreSquared = coreRadius * coreRadius;
    float attenuation = coreSquared
            / (distanceToSoul * distanceToSoul + coreSquared);
    float support = 1.0 - smoothstep(
            surfaceRadius * 0.82,
            surfaceRadius,
            distanceToSoul);
    vec3 contribution = SoulPointColorSurfaceRadius.rgb
            * SoulPointSurfaceControl.x
            * attenuation
            * support
            * diffuse
            * visibility;
    return baseColor + contribution;
}
