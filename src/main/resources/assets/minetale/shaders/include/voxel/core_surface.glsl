#ifndef MINETALE_CORE_SURFACE_GLSL
#define MINETALE_CORE_SURFACE_GLSL

#moj_import <minetale:voxel/math.glsl>
#moj_import <minetale:voxel/noise.glsl>
#moj_import <minetale:voxel/core_materials.glsl>

// 坐标与方向均以场景原点为基准。调用方先扣除场景摆放位置，再转换到 Blender 共享材质空间。
vec3 coreToMaterialSpace(vec3 sceneVector) {
    return vec3(sceneVector.x, -sceneVector.z, sceneVector.y);
}
vec3 coreToGameSpace(vec3 materialVector) {
    return vec3(materialVector.x, materialVector.z, -materialVector.y);
}

// normal、tangent、bitangent 是材质空间中的单位正交帧。
// heightDerivative 为沿两条切线、每一材质空间单位的 Height 变化率。
// 邻域 Height 可来自同页已计算的样本；跨页必须复用同一采样网格及边界邻域。
vec3 coreBumpNormal(vec3 normal, vec3 tangent, vec3 bitangent,
                   vec2 heightDerivative, float strength, float distance) {
    vec3 gradient = tangent * heightDerivative.x + bitangent * heightDerivative.y;
    vec3 bumped = normalize(normal - distance * gradient);
    return normalize(mix(normal, bumped, max(strength, 0.0)));
}

vec3 coreBumpFromNeighbors(CoreMaterial material, vec3 normal, vec3 tangent, vec3 bitangent,
                          vec2 positiveHeight, vec2 spacing) {
    vec2 derivative = (positiveHeight - material.height) / spacing;
    return coreBumpNormal(normal, tangent, bitangent, derivative,
                          material.bumpStrength, material.bumpDistance);
}

#endif
