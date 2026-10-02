#version 330

uniform sampler2D PositionState;

layout(std140) uniform CrowdSimulation {
    vec4 Simulation; // deltaSeconds、reset、agentCount、textureSize
    vec4 Layout;     // extentX、extentZ、walkSpeed、hardCollisionMargin
    vec4 Behavior;   // elapsedSeconds、softPersonalSpace、horizon、acceleration
    vec4 Field;      // textureSize、heightRange、maxClearance、walkableCount
    vec4 Migration;  // xy=相对旧锚点偏移，z=migrate
    vec4 StatePage;  // xy=共享状态 Atlas 原点
};

in vec3 Position;

flat out int agentId;
flat out float rankDepth;

const int OCCUPANCY_BUCKET_COLUMNS = 4;
const int OCCUPANCY_BUCKET_ROWS = 4;

float decode16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

vec2 decodePosition(ivec2 texel) {
    vec4 encoded = texelFetch(PositionState, texel, 0);
    return vec2(
            decode16(encoded.rg) * Layout.x * 2.0 - Layout.x,
            decode16(encoded.ba) * Layout.y * 2.0 - Layout.y);
}

ivec2 atlasStateTexel(ivec2 localTexel) {
    return ivec2(StatePage.xy + 0.5) + localTexel;
}

void main() {
    int stateSize = int(Simulation.w + 0.5);
    agentId = gl_InstanceID;
    ivec2 stateTexel = atlasStateTexel(
            ivec2(agentId % stateSize, agentId / stateSize));
    if (all(equal(texelFetch(PositionState, stateTexel, 0), vec4(0.0)))) {
        rankDepth = 1.0;
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    vec2 agentPosition = decodePosition(stateTexel);
    int fieldSize = int(Field.x + 0.5);
    ivec2 cell = clamp(
            ivec2(floor(agentPosition + Layout.xy)),
            ivec2(0),
            ivec2(fieldSize - 1));

    // 每个逻辑格展开为 4×4 哈希槽，一次实例化 Draw 建立全部层的邻域索引。
    uint bucketHash = uint(agentId)
            + (uint(cell.x) * 73856093u ^ uint(cell.y) * 19349663u);
    bucketHash ^= bucketHash >> 16u;
    bucketHash *= 2246822519u;
    bucketHash ^= bucketHash >> 13u;
    int bucket = int(bucketHash & 15u);
    ivec2 bucketTexel = cell * ivec2(OCCUPANCY_BUCKET_COLUMNS, OCCUPANCY_BUCKET_ROWS)
            + ivec2(bucket % OCCUPANCY_BUCKET_COLUMNS, bucket / OCCUPANCY_BUCKET_COLUMNS);
    vec2 atlasSize = Field.xx * vec2(OCCUPANCY_BUCKET_COLUMNS, OCCUPANCY_BUCKET_ROWS);
    vec2 clipPosition = (vec2(bucketTexel) + Position.xy) / atlasSize * 2.0 - 1.0;

    // 步长 73 与 2 的幂容量互质，生成无碰撞且不偏向低 ID 的稳定顺序。
    int stateCapacity = stateSize * stateSize;
    int rank = (agentId * 73) % stateCapacity;
    rankDepth = (float(rank) + 0.5) / float(stateCapacity);
    gl_Position = vec4(clipPosition, rankDepth * 2.0 - 1.0, 1.0);
}
