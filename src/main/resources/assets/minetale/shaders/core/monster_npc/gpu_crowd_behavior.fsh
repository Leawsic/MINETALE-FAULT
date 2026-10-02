#version 330

uniform sampler2D PositionState;
uniform sampler2D VelocityState;
uniform sampler2D BehaviorState;
uniform sampler2D StaticField;
uniform sampler2D AppearanceData;

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
const int ACTIVE_STATE_MARKER = 1;
const float TAU = 6.2831853;
const float MAX_HEADING_TURN_RATE = 2.0;
const float MAX_FOOTPRINT_HALF_EXTENT = 4.0;

float decode16(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0;
}

int decodeBytes(vec2 encoded) {
    ivec2 bytes = ivec2(floor(encoded * 255.0 + 0.5));
    return bytes.x * 256 + bytes.y;
}

vec2 encodeBytes(int value) {
    return vec2(value / 256, value % 256) / 255.0;
}

ivec2 stateTexel(ivec2 localTexel) {
    return ivec2(StatePage.xy + 0.5) + localTexel;
}

vec2 decodeVelocity(ivec2 texel) {
    float velocityRange = Layout.z * 1.25;
    vec4 encoded = texelFetch(VelocityState, stateTexel(texel), 0);
    return vec2(
            (decode16(encoded.rg) * 2.0 - 1.0) * velocityRange,
            (decode16(encoded.ba) * 2.0 - 1.0) * velocityRange);
}

int decodeHeading(int packedState, int packedHeading) {
    int lowBits = (packedState >> 11) & 31;
    int highBits = (packedHeading >> 10) & 63;
    return lowBits | (highBits << 5);
}

float headingAngle(int heading) {
    return float(heading) / float(HEADING_LEVELS) * TAU;
}

int encodeHeading(float angle) {
    float normalized = fract(angle / TAU);
    return int(floor(normalized * float(HEADING_LEVELS) + 0.5))
            & (HEADING_LEVELS - 1);
}

vec2 decodePosition(ivec2 texel) {
    vec4 encoded = texelFetch(PositionState, stateTexel(texel), 0);
    return vec2(
            decode16(encoded.rg) * Layout.x * 2.0 - Layout.x,
            decode16(encoded.ba) * Layout.y * 2.0 - Layout.y);
}

ivec2 fieldTexel(vec2 position) {
    vec2 normalized = position / (Layout.xy * 2.0) + 0.5;
    int fieldSize = int(Field.x + 0.5);
    return clamp(
            ivec2(floor(normalized * Field.x)),
            ivec2(0),
            ivec2(fieldSize - 1));
}

float hash11(float value) {
    return fract(sin(value * 91.3458 + 17.23) * 47453.5453);
}

vec2 agentHalfExtents(int agentId) {
    int appearance = agentId % textureSize(AppearanceData, 0).x;
    return texelFetch(AppearanceData, ivec2(appearance, 0), 0).rg
            * MAX_FOOTPRINT_HALF_EXTENT;
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

void main() {
    ivec2 selfTexel = ivec2(gl_FragCoord.xy) - ivec2(StatePage.xy + 0.5);
    int stateSize = int(Simulation.w + 0.5);
    int selfId = selfTexel.y * stateSize + selfTexel.x;
    int agentCount = int(Simulation.z + 0.5);
    if (selfId >= agentCount) {
        fragColor = vec4(0.0);
        return;
    }

    vec4 previousPosition = texelFetch(
            PositionState,
            stateTexel(selfTexel),
            0);
    // 全零 RGBA 是唯一未激活标记；扩容只能初始化新增槽。
    bool initialize = Simulation.y > 0.5
            || all(equal(previousPosition, vec4(0.0)));
    if (Migration.z > 0.5) {
        vec4 previousBehavior = texelFetch(
                BehaviorState,
                stateTexel(selfTexel),
                0);
        if (any(notEqual(previousBehavior, vec4(0.0)))) {
            fragColor = previousBehavior;
            return;
        }
        initialize = true;
    }

    int heading;
    if (initialize) {
        heading = int(floor(
                hash11(float(selfId) + 271.0)
                        * float(HEADING_LEVELS)));
    } else {
        vec4 encoded = texelFetch(
                BehaviorState,
                stateTexel(selfTexel),
                0);
        int packedState = decodeBytes(encoded.rg);
        int packedHeading = decodeBytes(encoded.ba);
        heading = decodeHeading(packedState, packedHeading);

        // 朝向独立持久化，低速时保持旧值以避免 idle/移动角跳变。
        vec2 velocity = decodeVelocity(selfTexel);
        if (length(velocity) > 0.04) {
            float currentAngle = headingAngle(heading);
            float targetAngle = atan(velocity.y, velocity.x);
            float angleDelta = atan(
                    sin(targetAngle - currentAngle),
                    cos(targetAngle - currentAngle));
            float candidateAngle = currentAngle + clamp(
                    angleDelta,
                    -MAX_HEADING_TURN_RATE * Simulation.x,
                    MAX_HEADING_TURN_RATE * Simulation.x);
            // 新朝向只有在当前位置仍容纳完整模型时才提交。
            if (footprintWalkable(
                    decodePosition(selfTexel),
                    candidateAngle,
                    agentHalfExtents(selfId))) {
                heading = encodeHeading(candidateAngle);
            }
        }
    }

    // bit 0 独立标记激活态，避免 heading=0 与全零未初始化纹素混淆。
    int packedState = ACTIVE_STATE_MARKER | ((heading & 31) << 11);
    int packedHeading = ((heading >> 5) & 63) << 10;
    fragColor = vec4(encodeBytes(packedState), encodeBytes(packedHeading));
}
