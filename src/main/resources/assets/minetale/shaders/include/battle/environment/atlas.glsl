#ifndef MINETALE_ENVIRONMENT_ATLAS_GLSL
#define MINETALE_ENVIRONMENT_ATLAS_GLSL

// 定义捕获阶段 3×2 Atlas 的方向、face 与保护区；环境 Field 就绪后不再消费。
const int MINETALE_ENVIRONMENT_ATLAS_COLUMNS = 3;
const int MINETALE_ENVIRONMENT_ATLAS_ROWS = 2;
const int MINETALE_ENVIRONMENT_ATLAS_GUARD_TEXELS = 2;

ivec2 minetaleEnvironmentFaceTile(int faceIndex) {
    if (faceIndex == 0) return ivec2(0, 0); // FRONT 面
    if (faceIndex == 1) return ivec2(1, 0); // RIGHT 面
    if (faceIndex == 2) return ivec2(2, 0); // BACK 面
    if (faceIndex == 3) return ivec2(0, 1); // LEFT 面
    if (faceIndex == 4) return ivec2(1, 1); // UP 面
    return ivec2(2, 1);                     // DOWN 面
}

ivec2 minetaleEnvironmentFaceSize(ivec2 atlasSize) {
    return atlasSize / ivec2(
            MINETALE_ENVIRONMENT_ATLAS_COLUMNS,
            MINETALE_ENVIRONMENT_ATLAS_ROWS
    ) - ivec2(MINETALE_ENVIRONMENT_ATLAS_GUARD_TEXELS * 2);
}

ivec2 minetaleEnvironmentFaceTexel(
        int faceIndex,
        ivec2 facePixel,
        ivec2 atlasSize
) {
    ivec2 tileStride = atlasSize / ivec2(
            MINETALE_ENVIRONMENT_ATLAS_COLUMNS,
            MINETALE_ENVIRONMENT_ATLAS_ROWS
    );
    return minetaleEnvironmentFaceTile(faceIndex) * tileStride
            + ivec2(MINETALE_ENVIRONMENT_ATLAS_GUARD_TEXELS)
            + facePixel;
}

vec3 minetaleEnvironmentFaceDirection(int faceIndex, vec2 faceUv) {
    if (faceIndex == 0) return normalize(vec3(faceUv.x, faceUv.y, 1.0));
    if (faceIndex == 1) return normalize(vec3(1.0, faceUv.y, -faceUv.x));
    if (faceIndex == 2) return normalize(vec3(-faceUv.x, faceUv.y, -1.0));
    if (faceIndex == 3) return normalize(vec3(-1.0, faceUv.y, faceUv.x));
    if (faceIndex == 4) return normalize(vec3(faceUv.x, 1.0, -faceUv.y));
    return normalize(vec3(faceUv.x, -1.0, faceUv.y));
}

int minetaleEnvironmentDirectionFace(vec3 direction) {
    vec3 absoluteDirection = abs(direction);
    if (absoluteDirection.z >= absoluteDirection.x
            && absoluteDirection.z >= absoluteDirection.y) {
        return direction.z >= 0.0 ? 0 : 2;
    }
    if (absoluteDirection.x >= absoluteDirection.y) {
        return direction.x >= 0.0 ? 1 : 3;
    }
    return direction.y >= 0.0 ? 4 : 5;
}

vec2 minetaleEnvironmentDirectionFaceUv(int faceIndex, vec3 direction) {
    vec3 absoluteDirection = abs(direction);
    if (faceIndex == 0) return vec2(direction.x, direction.y) / absoluteDirection.z;
    if (faceIndex == 1) return vec2(-direction.z, direction.y) / absoluteDirection.x;
    if (faceIndex == 2) return vec2(-direction.x, direction.y) / absoluteDirection.z;
    if (faceIndex == 3) return vec2(direction.z, direction.y) / absoluteDirection.x;
    if (faceIndex == 4) return vec2(direction.x, -direction.z) / absoluteDirection.y;
    return vec2(direction.x, direction.z) / absoluteDirection.y;
}

vec2 minetaleEnvironmentAtlasUv(vec3 direction, ivec2 atlasSize) {
    int faceIndex = minetaleEnvironmentDirectionFace(direction);
    vec2 faceUv = minetaleEnvironmentDirectionFaceUv(faceIndex, direction);
    vec2 atlasDimensions = vec2(atlasSize);
    vec2 tileStride = atlasDimensions / vec2(
            float(MINETALE_ENVIRONMENT_ATLAS_COLUMNS),
            float(MINETALE_ENVIRONMENT_ATLAS_ROWS)
    );
    vec2 faceSize = tileStride
            - vec2(float(MINETALE_ENVIRONMENT_ATLAS_GUARD_TEXELS * 2));
    vec2 facePixel = (faceUv * 0.5 + 0.5) * faceSize;
    facePixel = clamp(facePixel, vec2(0.5), faceSize - vec2(0.5));
    vec2 atlasPixel = vec2(minetaleEnvironmentFaceTile(faceIndex)) * tileStride
            + vec2(float(MINETALE_ENVIRONMENT_ATLAS_GUARD_TEXELS))
            + facePixel;
    return atlasPixel / atlasDimensions;
}

#endif
