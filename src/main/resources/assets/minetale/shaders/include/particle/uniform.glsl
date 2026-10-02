// 固定 std140 ABI，必须与 GpuParticleUniform 的写入顺序一致。
// [0]=相机相对 anchor/motionTimeSeconds
// [1]=axis/radialScale
// [2]=radialRight/axialScale
// [3]=radialUp/intensity
// [4]=billboardRight/visibility
// [5]=billboardUp/particleSizeScale
// [6].x=alphaCutout，其余分量保留
layout(std140) uniform ParticleUniform {
    vec4 ParticleFrameData[7];
};
