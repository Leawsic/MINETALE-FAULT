package cn.jehorstudio.minetale.battle.logic.actor;

import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;

// 所有字段均位于 Actor 局部坐标；旋转只作用于当前子 OBB。
public record CollisionBox(
        CanonicalVec3 center,
        CanonicalVec3 halfExtents,
        double yawDeg,
        double pitchDeg,
        double rollDeg
) {
    public static final CollisionBox POINT = new CollisionBox(
            CanonicalVec3.ZERO,
            CanonicalVec3.ZERO,
            0.0D,
            0.0D,
            0.0D
    );

    public CollisionBox {
        if (center == null) {
            throw new NullPointerException("center");
        }
        if (halfExtents == null) {
            throw new NullPointerException("halfExtents");
        }
        if (halfExtents.x() < 0.0D || halfExtents.y() < 0.0D || halfExtents.z() < 0.0D) {
            throw new IllegalArgumentException("halfExtents must be >= 0.");
        }
        requireFinite(yawDeg, "yawDeg");
        requireFinite(pitchDeg, "pitchDeg");
        requireFinite(rollDeg, "rollDeg");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }
}
