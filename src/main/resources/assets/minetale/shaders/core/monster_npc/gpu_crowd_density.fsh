#version 330

uniform sampler2D PositionState;
uniform sampler2D StaticField;
uniform sampler2D OccupancyAtlas;

layout(std140) uniform CrowdSimulation {
    vec4 Simulation; // deltaSeconds、reset、agentCount、textureSize
    vec4 Layout;     // extentX、extentZ、walkSpeed、hardCollisionMargin
    vec4 Behavior;   // elapsedSeconds、softPersonalSpace、horizon、acceleration
    vec4 Field;      // textureSize、heightRange、maxClearance、walkableCount
    vec4 Migration;  // xy=相对旧锚点偏移，z=migrate
    vec4 StatePage;  // xy=状态 Atlas 原点
};

out vec4 fragColor;

const int OCCUPANCY_BUCKET_COLUMNS = 4;
const int OCCUPANCY_BUCKET_ROWS = 4;
const int OCCUPANCY_BUCKETS = OCCUPANCY_BUCKET_COLUMNS * OCCUPANCY_BUCKET_ROWS;
const int SEARCH_RADIUS = 3;
const float SPLAT_RADIUS = 2.25;
const float MAX_DENSITY = 12.0;

float decode16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

int decodeAgentId(vec2 encoded) {
    ivec2 bytes = ivec2(floor(encoded * 255.0 + 0.5));
    return bytes.x * 256 + bytes.y - 1;
}

int occupantAt(ivec2 cell, int bucket) {
    ivec2 bucketTexel = cell * ivec2(OCCUPANCY_BUCKET_COLUMNS, OCCUPANCY_BUCKET_ROWS)
            + ivec2(bucket % OCCUPANCY_BUCKET_COLUMNS, bucket / OCCUPANCY_BUCKET_COLUMNS);
    return decodeAgentId(texelFetch(OccupancyAtlas, bucketTexel, 0).rg);
}

vec2 decodePosition(int agentId) {
    int stateSize = int(Simulation.w + 0.5);
    ivec2 texel = ivec2(StatePage.xy + 0.5)
            + ivec2(agentId % stateSize, agentId / stateSize);
    vec4 encoded = texelFetch(PositionState, texel, 0);
    return vec2(
            decode16(encoded.rg) * Layout.x * 2.0 - Layout.x,
            decode16(encoded.ba) * Layout.y * 2.0 - Layout.y
    );
}

void main() {
    ivec2 fieldTexel = ivec2(gl_FragCoord.xy);
    if (texelFetch(StaticField, fieldTexel, 0).r < 0.5) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 fieldPosition = gl_FragCoord.xy - Layout.xy;
    int fieldSize = int(Field.x + 0.5);
    int agentCount = int(Simulation.z + 0.5);
    float density = 0.0;
    for (int offsetZ = -SEARCH_RADIUS; offsetZ <= SEARCH_RADIUS; offsetZ++) {
        for (int offsetX = -SEARCH_RADIUS; offsetX <= SEARCH_RADIUS; offsetX++) {
            ivec2 cell = fieldTexel + ivec2(offsetX, offsetZ);
            if (any(lessThan(cell, ivec2(0)))
                    || any(greaterThanEqual(cell, ivec2(fieldSize)))) {
                continue;
            }
            for (int bucket = 0; bucket < OCCUPANCY_BUCKETS; bucket++) {
                int agentId = occupantAt(cell, bucket);
                if (agentId < 0 || agentId >= agentCount) {
                    continue;
                }
                float normalizedDistance = length(fieldPosition - decodePosition(agentId))
                        / SPLAT_RADIUS;
                float influence = max(1.0 - normalizedDistance, 0.0);
                density += influence * influence;
            }
        }
    }
    fragColor = vec4(min(density / MAX_DENSITY, 1.0), 0.0, 0.0, 1.0);
}
