#version 330

uniform sampler2D PositionState;
uniform sampler2D VelocityState;
uniform sampler2D StaticField;
uniform sampler2D FlowField;
uniform sampler2D DensityField;
uniform sampler2D BehaviorState;
uniform sampler2D AppearanceData;
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

const int HEADING_LEVELS = 2048;
const int OCCUPANCY_BUCKET_COLUMNS = 4;
const int OCCUPANCY_BUCKET_ROWS = 4;
const int OCCUPANCY_BUCKETS = OCCUPANCY_BUCKET_COLUMNS * OCCUPANCY_BUCKET_ROWS;
const int NEIGHBOR_CELL_RADIUS = 4;
const float MAX_DENSITY = 12.0;
const float TAU = 6.2831853;
const float MAX_FOOTPRINT_HALF_EXTENT = 4.0;

float decode16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
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
    return decodeBytes(texelFetch(OccupancyAtlas, bucketTexel, 0).rg) - 1;
}

vec2 encode16(float value) {
    float integerValue = floor(clamp(value, 0.0, 1.0) * 65535.0 + 0.5);
    return vec2(floor(integerValue / 256.0), mod(integerValue, 256.0)) / 255.0;
}

vec2 decodePosition(ivec2 texel) {
    vec4 encoded = texelFetch(PositionState, stateTexel(texel), 0);
    return vec2(
            decode16(encoded.rg) * Layout.x * 2.0 - Layout.x,
            decode16(encoded.ba) * Layout.y * 2.0 - Layout.y
    );
}

vec2 decodeVelocity(ivec2 texel) {
    float velocityRange = Layout.z * 1.25;
    vec4 encoded = texelFetch(VelocityState, stateTexel(texel), 0);
    return vec2(
            (decode16(encoded.rg) * 2.0 - 1.0) * velocityRange,
            (decode16(encoded.ba) * 2.0 - 1.0) * velocityRange
    );
}

ivec2 fieldTexel(vec2 position) {
    vec2 normalized = position / (Layout.xy * 2.0) + 0.5;
    int fieldSize = int(Field.x + 0.5);
    return clamp(ivec2(floor(normalized * Field.x)), ivec2(0), ivec2(fieldSize - 1));
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

vec2 flowAt(ivec2 texel) {
    return texelFetch(FlowField, texel, 0).rg * 2.0 - 1.0;
}

float streamKindAt(ivec2 texel) {
    return texelFetch(FlowField, texel, 0).b;
}

vec2 continuousFlow(vec2 position) {
    int fieldSize = int(Field.x + 0.5);
    ivec2 fieldMaximum = ivec2(fieldSize - 1);
    vec2 gridPosition = position + Layout.xy - vec2(0.5);
    ivec2 base = ivec2(floor(gridPosition));
    vec2 blend = fract(gridPosition);
    ivec2 cells[4] = ivec2[4](
            clamp(base, ivec2(0), fieldMaximum),
            clamp(base + ivec2(1, 0), ivec2(0), fieldMaximum),
            clamp(base + ivec2(0, 1), ivec2(0), fieldMaximum),
            clamp(base + ivec2(1, 1), ivec2(0), fieldMaximum));
    float weights[4] = float[4](
            (1.0 - blend.x) * (1.0 - blend.y),
            blend.x * (1.0 - blend.y),
            (1.0 - blend.x) * blend.y,
            blend.x * blend.y);
    vec2 flow = vec2(0.0);
    float totalWeight = 0.0;
    vec2 reference = flowAt(fieldTexel(position));
    float referenceLength = length(reference);
    if (referenceLength > 0.02) {
        reference /= referenceLength;
    }
    for (int sampleIndex = 0; sampleIndex < 4; sampleIndex++) {
        float walkable = texelFetch(StaticField, cells[sampleIndex], 0).r > 0.5 ? 1.0 : 0.0;
        vec2 sampleFlow = flowAt(cells[sampleIndex]);
        float sampleLength = length(sampleFlow);
        // 相邻反向流不参与插值，避免主环双向路段在边界相消；直行与直角转弯仍连续混合。
        float compatible = referenceLength <= 0.02 || sampleLength <= 0.02
                || dot(sampleFlow / sampleLength, reference) >= -0.01
                ? 1.0
                : 0.0;
        float weight = weights[sampleIndex] * walkable * compatible;
        flow += sampleFlow * weight;
        totalWeight += weight;
    }
    if (totalWeight > 0.0001) {
        flow /= totalWeight;
    }
    return length(flow) > 0.02 ? normalize(flow) : vec2(0.0);
}

float densityAt(vec2 position) {
    int fieldSize = int(Field.x + 0.5);
    ivec2 fieldMaximum = ivec2(fieldSize - 1);
    vec2 gridPosition = position + Layout.xy - vec2(0.5);
    ivec2 base = ivec2(floor(gridPosition));
    vec2 blend = fract(gridPosition);
    float density00 = texelFetch(DensityField, clamp(base, ivec2(0), fieldMaximum), 0).r;
    float density10 = texelFetch(DensityField, clamp(base + ivec2(1, 0), ivec2(0), fieldMaximum), 0).r;
    float density01 = texelFetch(DensityField, clamp(base + ivec2(0, 1), ivec2(0), fieldMaximum), 0).r;
    float density11 = texelFetch(DensityField, clamp(base + ivec2(1, 1), ivec2(0), fieldMaximum), 0).r;
    return mix(mix(density00, density10, blend.x), mix(density01, density11, blend.x), blend.y)
            * MAX_DENSITY;
}

float clearanceAt(ivec2 texel) {
    return texelFetch(StaticField, texel, 0).a * Field.z;
}

vec4 encodeVelocity(vec2 velocity) {
    float velocityRange = Layout.z * 1.25;
    vec2 normalized = velocity / (velocityRange * 2.0) + 0.5;
    return vec4(encode16(normalized.x), encode16(normalized.y));
}

float hash11(float value) {
    return fract(sin(value * 91.3458 + 17.23) * 47453.5453);
}

vec2 agentHalfExtents(int agentId) {
    int appearance = agentId % textureSize(AppearanceData, 0).x;
    return texelFetch(AppearanceData, ivec2(appearance, 0), 0).rg
            * MAX_FOOTPRINT_HALF_EXTENT;
}

float footprintSupport(vec2 halfExtents, float heading, vec2 direction) {
    vec2 forward = vec2(cos(heading), sin(heading));
    vec2 right = vec2(-forward.y, forward.x);
    return abs(dot(direction, right)) * halfExtents.x
            + abs(dot(direction, forward)) * halfExtents.y;
}

float agentHeading(int agentId) {
    int stateSize = int(Simulation.w + 0.5);
    ivec2 texel = ivec2(agentId % stateSize, agentId / stateSize);
    vec4 encoded = texelFetch(BehaviorState, stateTexel(texel), 0);
    int packedState = decodeBytes(encoded.rg);
    int packedTimerHeading = decodeBytes(encoded.ba);
    int lowBits = (packedState >> 11) & 31;
    int highBits = (packedTimerHeading >> 10) & 63;
    return float(lowBits | (highBits << 5)) / float(HEADING_LEVELS) * TAU;
}

float agentPreferredSpeed(int agentId) {
    int appearance = agentId % textureSize(AppearanceData, 0).x;
    return decode16(texelFetch(AppearanceData, ivec2(appearance, 0), 0).ba)
            * Layout.z * 1.25;
}

vec2 relativeDelta(vec2 selfPosition, vec2 neighborPosition) {
    return selfPosition - neighborPosition;
}

void main() {
    ivec2 selfTexel = ivec2(gl_FragCoord.xy) - ivec2(StatePage.xy + 0.5);
    int stateSize = int(Simulation.w + 0.5);
    int selfId = selfTexel.y * stateSize + selfTexel.x;
    int agentCount = int(Simulation.z + 0.5);
    if (selfId >= agentCount) {
        fragColor = encodeVelocity(vec2(0.0));
        return;
    }
    if (all(equal(texelFetch(PositionState, stateTexel(selfTexel), 0), vec4(0.0)))) {
        fragColor = encodeVelocity(vec2(0.0));
        return;
    }
    if (Migration.z > 0.5) {
        fragColor = texelFetch(VelocityState, stateTexel(selfTexel), 0);
        return;
    }

    if (Simulation.y > 0.5) {
        fragColor = encodeVelocity(vec2(0.0));
        return;
    }

    vec2 selfPosition = decodePosition(selfTexel);
    vec2 selfVelocity = decodeVelocity(selfTexel);
    ivec2 selfFieldTexel = fieldTexel(selfPosition);
    vec4 encodedBehavior = texelFetch(BehaviorState, stateTexel(selfTexel), 0);
    int packedBehavior = decodeBytes(encodedBehavior.rg);
    int packedHeading = decodeBytes(encodedBehavior.ba);
    int headingLowBits = (packedBehavior >> 11) & 31;
    int headingHighBits = (packedHeading >> 10) & 63;
    int heading = headingLowBits | (headingHighBits << 5);
    float persistentHeading = float(heading) / float(HEADING_LEVELS) * TAU;
    vec2 navigation = continuousFlow(selfPosition);
    float navigationLength = length(navigation);
    float selfSpeed = length(selfVelocity);
    float fallbackAngle = hash11(float(selfId) + 271.0) * 6.2831853;
    vec2 navigationHeading = navigationLength > 0.02
            ? navigation / navigationLength
            : selfSpeed > 0.02
            ? selfVelocity / selfSpeed
            : vec2(cos(fallbackAngle), sin(fallbackAngle));
    float navigationWeight = smoothstep(0.02, 0.18, navigationLength);
    float localDensity = densityAt(selfPosition);
    float crowdPressure = smoothstep(1.5, 5.5, localDensity);
    // 高密区保留最低推进速度，防止局部冲突固化为永久拥堵。
    float densitySpeedFactor = mix(1.0, 0.72, crowdPressure);
    float desiredSpeed = agentPreferredSpeed(selfId)
            * navigationWeight
            * densitySpeedFactor;
    vec2 right = vec2(navigationHeading.y, -navigationHeading.x);
    float stableOffset = hash11(float(selfId) + 307.0) * 2.0 - 1.0;
    float noiseRate = mix(
            0.10,
            0.18,
            hash11(float(selfId) + 331.0));
    float pathNoise = sin(
            dot(selfPosition, vec2(0.071, 0.053))
            + Behavior.x * noiseRate
            + hash11(float(selfId) + 347.0) * TAU);
    float clearanceWeight = smoothstep(
            1.5,
            4.0,
            clearanceAt(selfFieldTexel));
    float perturbation = (stableOffset * 0.035 + pathNoise * 0.08)
            * clearanceWeight
            * mix(0.45, 1.0, streamKindAt(selfFieldTexel));
    vec2 preferredHeading = normalize(
            navigationHeading + right * perturbation);
    vec2 preferredVelocity = preferredHeading * desiredSpeed;

    vec2 avoidance = vec2(0.0);
    float horizon = Behavior.z;

    int fieldSize = int(Field.x + 0.5);
    ivec2 fieldMaximum = ivec2(fieldSize - 1);
    float clearancePositiveX = clearanceAt(clamp(selfFieldTexel + ivec2(1, 0), ivec2(0), fieldMaximum));
    float clearanceNegativeX = clearanceAt(clamp(selfFieldTexel - ivec2(1, 0), ivec2(0), fieldMaximum));
    float clearancePositiveZ = clearanceAt(clamp(selfFieldTexel + ivec2(0, 1), ivec2(0), fieldMaximum));
    float clearanceNegativeZ = clearanceAt(clamp(selfFieldTexel - ivec2(0, 1), ivec2(0), fieldMaximum));
    vec2 clearanceGradient = vec2(
            clearancePositiveX - clearanceNegativeX,
            clearancePositiveZ - clearanceNegativeZ);
    float gradientLength = length(clearanceGradient);
    vec2 selfHalfExtents = agentHalfExtents(selfId);
    float wallThreshold = selfHalfExtents.x + 0.9;
    float wallWeight = clamp(
            (wallThreshold - clearanceAt(selfFieldTexel)) / max(wallThreshold, 0.1),
            0.0,
            1.0);
    if (gradientLength > 0.001) {
        avoidance += clearanceGradient / gradientLength * wallWeight * Behavior.w;
    }
    vec2 densityGradient = vec2(
            densityAt(selfPosition + vec2(1.0, 0.0)) - densityAt(selfPosition - vec2(1.0, 0.0)),
            densityAt(selfPosition + vec2(0.0, 1.0)) - densityAt(selfPosition - vec2(0.0, 1.0))) * 0.5;
    float densityGradientLength = length(densityGradient);
    if (densityGradientLength > 0.04) {
        vec2 densityAvoidance = -densityGradient * Behavior.w * 0.28;
        float maximumDensityAvoidance = Behavior.w * crowdPressure * 0.75;
        float densityAvoidanceLength = length(densityAvoidance);
        if (densityAvoidanceLength > maximumDensityAvoidance && densityAvoidanceLength > 0.0001) {
            densityAvoidance *= maximumDensityAvoidance / densityAvoidanceLength;
        }
        avoidance += densityAvoidance;
    }
    vec2 predictedPosition = selfPosition + preferredVelocity * 0.8;
    float predictedHeading = length(preferredVelocity) > 0.02
            ? atan(preferredVelocity.y, preferredVelocity.x)
            : persistentHeading;
    if (!footprintWalkable(predictedPosition, predictedHeading, selfHalfExtents)) {
        preferredVelocity *= 0.15;
        if (gradientLength > 0.001) {
            avoidance += clearanceGradient / gradientLength * Behavior.w;
        }
    }

    // 9×9×16 哈希邻域覆盖 2.5 秒 TTC，同时保持固定迭代上限。
    for (int offsetZ = -NEIGHBOR_CELL_RADIUS;
            offsetZ <= NEIGHBOR_CELL_RADIUS;
            offsetZ++) {
        for (int offsetX = -NEIGHBOR_CELL_RADIUS;
                offsetX <= NEIGHBOR_CELL_RADIUS;
                offsetX++) {
            ivec2 neighborCell = selfFieldTexel + ivec2(offsetX, offsetZ);
            if (any(lessThan(neighborCell, ivec2(0)))
                    || any(greaterThan(neighborCell, fieldMaximum))) {
                continue;
            }
            for (int bucket = 0; bucket < OCCUPANCY_BUCKETS; bucket++) {
                int neighborId = occupantAt(neighborCell, bucket);
                if (neighborId < 0 || neighborId >= agentCount) {
                    continue;
                }
                if (neighborId == selfId) {
                    continue;
                }
                ivec2 neighborTexel = ivec2(
                        neighborId % stateSize,
                        neighborId / stateSize);
                vec2 neighborPosition = decodePosition(neighborTexel);
                vec2 neighborVelocity = decodeVelocity(neighborTexel);
                vec2 relativePosition = relativeDelta(selfPosition, neighborPosition);
                float distanceNow = length(relativePosition);
                vec2 neighborHalfExtents = agentHalfExtents(neighborId);
                float neighborPersistentHeading = agentHeading(neighborId);
                // 外接圆只做远距粗筛，最终约束使用随朝向变化的矩形投影。
                float selfBound = length(selfHalfExtents);
                float neighborBound = length(neighborHalfExtents);
                if (distanceNow > Layout.z * horizon
                        + selfBound + neighborBound + Behavior.y) {
                    continue;
                }

                vec2 relativeVelocity = selfVelocity - neighborVelocity;
                float relativeSpeedSquared = dot(relativeVelocity, relativeVelocity);
                float timeToClosest = relativeSpeedSquared > 0.0001
                        ? clamp(
                                -dot(relativePosition, relativeVelocity)
                                        / relativeSpeedSquared,
                                0.0,
                                horizon)
                        : 0.0;
                vec2 closestOffset = relativePosition
                        + relativeVelocity * timeToClosest;
                float closestDistance = length(closestOffset);
                vec2 normal = closestDistance > 0.001
                        ? closestOffset / closestDistance
                        : right;

                vec2 currentNormal = distanceNow > 0.001
                        ? relativePosition / distanceNow
                        : normal;
                float currentClearance = footprintSupport(
                                selfHalfExtents, persistentHeading, currentNormal)
                        + footprintSupport(
                                neighborHalfExtents, neighborPersistentHeading, currentNormal)
                        + Behavior.y;
                float predictedClearance = footprintSupport(
                                selfHalfExtents, persistentHeading, normal)
                        + footprintSupport(
                                neighborHalfExtents, neighborPersistentHeading, normal)
                        + Behavior.y;

                float overlap = max(currentClearance - distanceNow, 0.0)
                        / max(currentClearance, 0.001);
                if (overlap > 0.0) {
                    float yieldWeight = hash11(float(selfId) + 9.0)
                                    < hash11(float(neighborId) + 9.0)
                            ? 0.55
                            : 1.0;
                    avoidance += normal * overlap * Behavior.w * yieldWeight;
                }

                if (timeToClosest > 0.001 && closestDistance < predictedClearance) {
                    float spatialWeight = (predictedClearance - closestDistance)
                            / predictedClearance;
                    float timeWeight = exp(-timeToClosest / 1.5)
                            / (timeToClosest * timeToClosest + 0.15);
                    float powerLawWeight = min(spatialWeight * timeWeight, 2.5);
                    vec2 neighborHeading = length(neighborVelocity) > 0.05
                            ? normalize(neighborVelocity)
                            : -navigationHeading;
                    float headOn = smoothstep(
                            0.25,
                            0.85,
                            -dot(navigationHeading, neighborHeading));
                    avoidance += normal * powerLawWeight;
                    // 确定性靠右规则打破镜像死锁，使相向者选择世界空间的相反侧。
                    avoidance += right * headOn * powerLawWeight * 0.9;
                }
            }
        }
    }

    float avoidanceLength = length(avoidance);
    float maximumAvoidance = Layout.z * mix(0.70, 0.90, crowdPressure);
    if (avoidanceLength > maximumAvoidance && avoidanceLength > 0.0001) {
        avoidance *= maximumAvoidance / avoidanceLength;
    }
    vec2 targetVelocity = preferredVelocity + avoidance;
    if (navigationLength > 0.02) {
        float backwardSpeed = dot(targetVelocity, navigationHeading);
        if (backwardSpeed < 0.0) {
            targetVelocity -= navigationHeading * backwardSpeed;
        }
    }
    float targetSpeed = length(targetVelocity);
    float maximumSpeed = Layout.z * 1.25;
    if (targetSpeed > maximumSpeed) {
        targetVelocity *= maximumSpeed / targetSpeed;
    }

    // 持久速度充当一阶低通，密集区以更长时间常数抑制左右高频修正。
    float smoothingSeconds = mix(0.22, 0.65, crowdPressure);
    float smoothingAlpha = 1.0 - exp(-Simulation.x / smoothingSeconds);
    vec2 filteredVelocity = mix(selfVelocity, targetVelocity, smoothingAlpha);
    float filteredSpeed = length(filteredVelocity);
    if (filteredSpeed > 0.04) {
        float currentAngle = selfSpeed > 0.08
                ? atan(selfVelocity.y, selfVelocity.x)
                : persistentHeading;
        float filteredAngle = atan(filteredVelocity.y, filteredVelocity.x);
        float angleDelta = atan(
                sin(filteredAngle - currentAngle),
                cos(filteredAngle - currentAngle));
        float maximumTurn = mix(2.8, 1.4, crowdPressure) * Simulation.x;
        float limitedAngle = currentAngle + clamp(angleDelta, -maximumTurn, maximumTurn);
        filteredVelocity = vec2(cos(limitedAngle), sin(limitedAngle)) * filteredSpeed;
    }

    vec2 velocityDelta = filteredVelocity - selfVelocity;
    float maximumChange = Behavior.w * Simulation.x;
    float changeLength = length(velocityDelta);
    if (changeLength > maximumChange && changeLength > 0.0001) {
        velocityDelta *= maximumChange / changeLength;
    }
    fragColor = encodeVelocity(selfVelocity + velocityDelta);
}
