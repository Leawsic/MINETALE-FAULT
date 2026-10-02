#version 330

uniform sampler2D PositionState;
uniform sampler2D VelocityState;
uniform sampler2D BehaviorState;
uniform sampler2D StaticField;
uniform sampler2D DestinationData;
uniform sampler2D AppearanceData;
uniform sampler2D OccupancyAtlas;

layout(std140) uniform CrowdSimulation {
    vec4 Simulation; // deltaSeconds、reset、agentCount、textureSize
    vec4 Layout;     // extentX、extentZ、speed、hardCollisionMargin
    vec4 Behavior;   // elapsedSeconds、softPersonalSpace、horizon、acceleration
    vec4 Field;      // textureSize、heightRange、maxClearance、walkableCount
    vec4 Migration;  // xy=相对旧锚点偏移，z=migrate
    vec4 StatePage;  // xy=共享状态 Atlas 原点
};

out vec4 fragColor;

const float MAX_STEP_HEIGHT = 1.01;
const float MAX_DEPENETRATION_PER_STEP = 0.035;
const float MAX_GEOMETRY_RECOVERY_PER_STEP = 0.04;
const int OCCUPANCY_BUCKET_COLUMNS = 4;
const int OCCUPANCY_BUCKET_ROWS = 4;
const int OCCUPANCY_BUCKETS = OCCUPANCY_BUCKET_COLUMNS * OCCUPANCY_BUCKET_ROWS;
const int PROJECTION_CELL_RADIUS = 4;
const int RECOVERY_CELL_RADIUS = 4;
const int SPAWN_POINT_COUNT = 128;
const int HEADING_LEVELS = 2048;
const float TAU = 6.2831853;
const float MAX_FOOTPRINT_HALF_EXTENT = 4.0;

float decode16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

int decodeAgentId(vec2 encoded) {
    ivec2 bytes = ivec2(floor(encoded * 255.0 + 0.5));
    return bytes.x * 256 + bytes.y - 1;
}

int decodeBytes(vec2 encoded) {
    ivec2 bytes = ivec2(floor(encoded * 255.0 + 0.5));
    return bytes.x * 256 + bytes.y;
}

ivec2 stateTexel(ivec2 localTexel) {
    return ivec2(StatePage.xy + 0.5) + localTexel;
}

int occupantAt(ivec2 cell, int bucket) {
    ivec2 bucketTexel = cell * ivec2(OCCUPANCY_BUCKET_COLUMNS, OCCUPANCY_BUCKET_ROWS)
            + ivec2(bucket % OCCUPANCY_BUCKET_COLUMNS, bucket / OCCUPANCY_BUCKET_COLUMNS);
    return decodeAgentId(texelFetch(OccupancyAtlas, bucketTexel, 0).rg);
}

vec2 encode16(float value) {
    float integerValue = floor(clamp(value, 0.0, 1.0) * 65535.0 + 0.5);
    return vec2(floor(integerValue / 256.0), mod(integerValue, 256.0)) / 255.0;
}

float hash11(float value) {
    return fract(sin(value * 91.3458 + 17.23) * 47453.5453);
}

ivec2 fieldTexel(vec2 position) {
    vec2 normalized = position / (Layout.xy * 2.0) + 0.5;
    int fieldSize = int(Field.x + 0.5);
    return clamp(ivec2(floor(normalized * Field.x)), ivec2(0), ivec2(fieldSize - 1));
}

vec2 decodePosition(ivec2 texel) {
    vec4 encoded = texelFetch(PositionState, stateTexel(texel), 0);
    return vec2(
            decode16(encoded.rg) * Layout.x * 2.0 - Layout.x,
            decode16(encoded.ba) * Layout.y * 2.0 - Layout.y);
}

vec2 decodeVelocity(ivec2 texel) {
    float velocityRange = Layout.z * 1.25;
    vec4 encoded = texelFetch(VelocityState, stateTexel(texel), 0);
    return vec2(
            (decode16(encoded.rg) * 2.0 - 1.0) * velocityRange,
            (decode16(encoded.ba) * 2.0 - 1.0) * velocityRange);
}

vec2 agentHalfExtents(int id) {
    int appearance = id % textureSize(AppearanceData, 0).x;
    return texelFetch(AppearanceData, ivec2(appearance, 0), 0).rg
            * MAX_FOOTPRINT_HALF_EXTENT;
}

float footprintSupport(vec2 halfExtents, float heading, vec2 direction) {
    vec2 forward = vec2(cos(heading), sin(heading));
    vec2 right = vec2(-forward.y, forward.x);
    return abs(dot(direction, right)) * halfExtents.x
            + abs(dot(direction, forward)) * halfExtents.y;
}

float agentHeading(int id) {
    int stateSize = int(Simulation.w + 0.5);
    ivec2 texel = ivec2(id % stateSize, id / stateSize);
    vec4 encoded = texelFetch(BehaviorState, stateTexel(texel), 0);
    int packedState = decodeBytes(encoded.rg);
    int packedTimerHeading = decodeBytes(encoded.ba);
    int lowBits = (packedState >> 11) & 31;
    int highBits = (packedTimerHeading >> 10) & 63;
    int heading = lowBits | (highBits << 5);
    return float(heading) / float(HEADING_LEVELS) * TAU;
}

float clearanceAt(ivec2 texel) {
    return texelFetch(StaticField, texel, 0).a * Field.z;
}

bool insideField(vec2 position) {
    return position.x >= -Layout.x && position.x < Layout.x
            && position.y >= -Layout.y && position.y < Layout.y;
}

bool walkableAt(vec2 position) {
    return insideField(position)
            && texelFetch(StaticField, fieldTexel(position), 0).r > 0.5;
}

bool footprintWalkable(vec2 position, float heading, vec2 halfExtents) {
    vec2 forward = vec2(cos(heading), sin(heading));
    vec2 right = vec2(-forward.y, forward.x);
    vec2 forwardExtent = forward * halfExtents.y;
    vec2 rightExtent = right * halfExtents.x;
    vec2 halfForward = forwardExtent * 0.5;
    return walkableAt(position)
            && walkableAt(position + forwardExtent)
            && walkableAt(position - forwardExtent)
            && walkableAt(position + rightExtent)
            && walkableAt(position - rightExtent)
            && walkableAt(position + forwardExtent + rightExtent)
            && walkableAt(position + forwardExtent - rightExtent)
            && walkableAt(position - forwardExtent + rightExtent)
            && walkableAt(position - forwardExtent - rightExtent)
            && walkableAt(position + halfForward + rightExtent)
            && walkableAt(position + halfForward - rightExtent)
            && walkableAt(position - halfForward + rightExtent)
            && walkableAt(position - halfForward - rightExtent);
}

float surfaceHeight(vec2 position) {
    vec4 fieldSample = texelFetch(StaticField, fieldTexel(position), 0);
    return (decode16(fieldSample.gb) * 2.0 - 1.0) * Field.y;
}

bool validCandidate(
        vec2 current,
        vec2 candidate,
        float heading,
        vec2 halfExtents
) {
    return footprintWalkable(candidate, heading, halfExtents)
            && abs(surfaceHeight(candidate) - surfaceHeight(current)) <= MAX_STEP_HEIGHT + 0.01;
}

vec2 constrainMove(
        vec2 current,
        vec2 candidate,
        float heading,
        vec2 halfExtents
) {
    if (validCandidate(current, candidate, heading, halfExtents)) {
        return candidate;
    }

    vec2 xOnly = vec2(candidate.x, current.y);
    vec2 zOnly = vec2(current.x, candidate.y);
    bool xValid = validCandidate(current, xOnly, heading, halfExtents);
    bool zValid = validCandidate(current, zOnly, heading, halfExtents);
    if (xValid && zValid) {
        return abs(candidate.x - current.x) >= abs(candidate.y - current.y)
                ? xOnly : zOnly;
    }
    if (xValid) {
        return xOnly;
    }
    if (zValid) {
        return zOnly;
    }
    return current;
}

vec2 recoverFootprint(
        vec2 current,
        float heading,
        vec2 halfExtents,
        int id
) {
    ivec2 center = fieldTexel(current);
    int fieldSize = int(Field.x + 0.5);
    ivec2 maximum = ivec2(fieldSize - 1);
    float requiredClearance = max(halfExtents.x, halfExtents.y) + 0.08;
    float bestDistanceSquared = 1.0E9;
    vec2 best = current;
    for (int offsetZ = -RECOVERY_CELL_RADIUS;
            offsetZ <= RECOVERY_CELL_RADIUS;
            offsetZ++) {
        for (int offsetX = -RECOVERY_CELL_RADIUS;
                offsetX <= RECOVERY_CELL_RADIUS;
                offsetX++) {
            ivec2 cell = center + ivec2(offsetX, offsetZ);
            if (any(lessThan(cell, ivec2(0))) || any(greaterThan(cell, maximum))
                    || clearanceAt(cell) < requiredClearance) {
                continue;
            }
            float angle = hash11(float(id * 17 + cell.x * 31 + cell.y * 47)) * TAU;
            vec2 candidate = vec2(cell) + vec2(0.5) - Layout.xy
                    + vec2(cos(angle), sin(angle)) * 0.05;
            vec2 delta = candidate - current;
            float distanceSquared = dot(delta, delta);
            if (distanceSquared < bestDistanceSquared
                    && footprintWalkable(candidate, heading, halfExtents)) {
                best = candidate;
                bestDistanceSquared = distanceSquared;
            }
        }
    }
    return best;
}

vec2 destinationSpawnPosition(int id) {
    // BA 锁存本代状态的屏幕外出生点
    int spawnSlot = (id * 73) % SPAWN_POINT_COUNT;
    ivec2 selected = ivec2(floor(
            texelFetch(DestinationData, ivec2(spawnSlot, 0), 0).ba
                    * 255.0 + 0.5));
    vec2 halfExtents = agentHalfExtents(id);
    float heading = agentHeading(id);
    vec2 cellCenter = vec2(selected) + vec2(0.5) - Layout.xy;
    float maximumJitter = clamp(
            clearanceAt(selected) - 0.5 - max(halfExtents.x, halfExtents.y),
            0.04,
            0.28);
    float jitterAngle = hash11(float(id) + 31.0) * 6.2831853;
    float jitterRadius = sqrt(hash11(float(id) + 79.0)) * maximumJitter;
    vec2 jitter = vec2(cos(jitterAngle), sin(jitterAngle)) * jitterRadius;
    return footprintWalkable(cellCenter + jitter, heading, halfExtents)
            ? cellCenter + jitter
            : cellCenter;
}

vec2 coincidentNormal(int selfId, int neighborId) {
    int lowId = min(selfId, neighborId);
    int highId = max(selfId, neighborId);
    float angle = hash11(float(lowId * 1031 + highId * 17) + 503.0) * 6.2831853;
    vec2 normal = vec2(cos(angle), sin(angle));
    return selfId < neighborId ? normal : -normal;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy) - ivec2(StatePage.xy + 0.5);
    int stateSize = int(Simulation.w + 0.5);
    int id = texel.y * stateSize + texel.x;
    int agentCount = int(Simulation.z + 0.5);
    float extentX = Layout.x;
    float extentZ = Layout.y;
    vec2 position;

    if (id >= agentCount) {
        fragColor = vec4(0.0);
        return;
    } else if (Migration.z > 0.5) {
        vec4 previousPosition = texelFetch(PositionState, stateTexel(texel), 0);
        bool wasInactive = all(equal(previousPosition, vec4(0.0)));
        position = wasInactive
                ? destinationSpawnPosition(id)
                : decodePosition(texel) + Migration.xy;
        vec2 halfExtents = agentHalfExtents(id);
        float heading = agentHeading(id);
        if (!wasInactive && !footprintWalkable(position, heading, halfExtents)) {
            position = destinationSpawnPosition(id);
        }
    } else if (Simulation.y > 0.5
            || all(equal(texelFetch(PositionState, stateTexel(texel), 0), vec4(0.0)))) {
        position = destinationSpawnPosition(id);
    } else {
        position = decodePosition(texel);
        vec2 halfExtents = agentHalfExtents(id);
        float heading = agentHeading(id);
        if (!footprintWalkable(position, heading, halfExtents)) {
            // 位置失效后保留最近有效出口并逐步撤离，不能瞬移修复。
            vec2 recoveryTarget = recoverFootprint(position, heading, halfExtents, id);
            vec2 recoveryDelta = recoveryTarget - position;
            float recoveryDistance = length(recoveryDelta);
            if (recoveryDistance > MAX_GEOMETRY_RECOVERY_PER_STEP) {
                recoveryDelta *= MAX_GEOMETRY_RECOVERY_PER_STEP / recoveryDistance;
            }
            position += recoveryDelta;
        } else {
            vec2 velocity = decodeVelocity(texel);
            vec2 proposed = constrainMove(
                    position,
                    position + velocity * Simulation.x,
                    heading,
                    halfExtents);

            // 软避让负责预判；这里只修正最深穿透，避免叠加邻居导致方向逐步翻转。
            vec2 deepestNormal = vec2(0.0);
            float deepestPenetration = 0.0;
            int fieldSize = int(Field.x + 0.5);
            ivec2 fieldMaximum = ivec2(fieldSize - 1);
            ivec2 proposedCell = fieldTexel(proposed);
            for (int offsetZ = -PROJECTION_CELL_RADIUS;
                    offsetZ <= PROJECTION_CELL_RADIUS;
                    offsetZ++) {
                for (int offsetX = -PROJECTION_CELL_RADIUS;
                        offsetX <= PROJECTION_CELL_RADIUS;
                        offsetX++) {
                    ivec2 neighborCell = proposedCell + ivec2(offsetX, offsetZ);
                    if (any(lessThan(neighborCell, ivec2(0)))
                            || any(greaterThan(neighborCell, fieldMaximum))) {
                        continue;
                    }
                    for (int bucket = 0; bucket < OCCUPANCY_BUCKETS; bucket++) {
                        int neighborId = occupantAt(neighborCell, bucket);
                        if (neighborId < 0 || neighborId >= agentCount) {
                            continue;
                        }
                        if (neighborId == id) {
                            continue;
                        }
                        ivec2 neighborTexel = ivec2(
                                neighborId % stateSize,
                                neighborId / stateSize);
                        vec2 neighborPosition = decodePosition(neighborTexel);
                        vec2 neighborHalfExtents = agentHalfExtents(neighborId);
                        float selfBound = length(halfExtents);
                        float neighborBound = length(neighborHalfExtents);
                        float maximumDistance = selfBound
                                + neighborBound
                                + Layout.w + 0.2;
                        if (length(neighborPosition - position) > maximumDistance) {
                            continue;
                        }
                        vec2 neighborProposed = neighborPosition
                                + decodeVelocity(neighborTexel) * Simulation.x;
                        vec2 delta = proposed - neighborProposed;
                        float distance = length(delta);
                        vec2 normal = distance > 0.001
                                ? delta / distance
                                : coincidentNormal(id, neighborId);
                        float neighborHeading = agentHeading(neighborId);
                        float minimumDistance = footprintSupport(halfExtents, heading, normal)
                                + footprintSupport(neighborHalfExtents, neighborHeading, normal)
                                + Layout.w;
                        float penetration = minimumDistance - distance;
                        if (penetration <= deepestPenetration) {
                            continue;
                        }
                        deepestPenetration = penetration;
                        deepestNormal = normal;
                    }
                }
            }
            vec2 separationCorrection = deepestNormal
                    * min(deepestPenetration * 0.5, MAX_DEPENETRATION_PER_STEP);
            position = constrainMove(
                    proposed,
                    proposed + separationCorrection,
                    heading,
                    halfExtents);
        }
    }

    // 未激活槽固定为全零 RGBA，激活坐标的 fixed-point 编码不得产生该值。
    float normalizedX = position.x / (extentX * 2.0) + 0.5;
    float normalizedZ = position.y / (extentZ * 2.0) + 0.5;
    fragColor = vec4(encode16(normalizedX), encode16(normalizedZ));
}
