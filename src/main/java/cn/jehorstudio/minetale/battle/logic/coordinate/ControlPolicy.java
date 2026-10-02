package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Objects;

// 定义原始输入的 canonical 方向基、锁轴与移动速率。
public record ControlPolicy(
        Type type,
        Basis basis,
        BattleCoordinateSpace.Axis lockedAxis,
        double lockedValue,
        double speedBuPerSecond,
        BattleCoordinateSpace.Axis dodgeAxis,
        double dodgeDistanceBu
) {
    public static final ControlPolicy PLANE_LOCKED_XZ = planeLocked(
            BattleCoordinateSpace.Axis.Y,
            0.0D,
            1.25D
    );

    public ControlPolicy {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(basis, "basis");
        if (!Double.isFinite(lockedValue)) {
            throw new IllegalArgumentException("lockedValue must be finite.");
        }
        if (!Double.isFinite(speedBuPerSecond) || speedBuPerSecond < 0.0D) {
            throw new IllegalArgumentException("speedBuPerSecond must be finite and >= 0.");
        }
        if (!Double.isFinite(dodgeDistanceBu) || dodgeDistanceBu < 0.0D) {
            throw new IllegalArgumentException("dodgeDistanceBu must be finite and >= 0.");
        }
        if ((type == Type.PLANE_LOCKED || type == Type.PLANE_WITH_DEPTH_DODGE) && lockedAxis == null) {
            throw new IllegalArgumentException("Plane control requires lockedAxis.");
        }
    }

    public static ControlPolicy planeLocked(BattleCoordinateSpace.Axis axis, double value, double speed) {
        return new ControlPolicy(Type.PLANE_LOCKED, Basis.CANONICAL, axis, value, speed, null, 0.0D);
    }

    public static ControlPolicy free3d(double speed) {
        return new ControlPolicy(Type.FREE_3D, Basis.CAMERA, null, 0.0D, speed, null, 0.0D);
    }

    public CanonicalVec3 movementDirection(double horizontal, double vertical, double depth, BattleViewMode viewMode) {
        Objects.requireNonNull(viewMode, "viewMode");
        CanonicalVec3 direction = this.basis == Basis.CAMERA
                ? cameraHorizontalDirection(horizontal, vertical, depth, viewMode)
                : new CanonicalVec3(horizontal, depth, -vertical);
        if (this.type == Type.PLANE_LOCKED || this.type == Type.PLANE_WITH_DEPTH_DODGE) {
            direction = direction.withComponent(this.lockedAxis, 0.0D);
        }
        return direction.normalize();
    }

    // 相机基移动只采用水平朝向；rise/fall 固定沿 canonical -Z。
    private static CanonicalVec3 cameraHorizontalDirection(
            double horizontal,
            double vertical,
            double rise,
            BattleViewMode viewMode
    ) {
        CanonicalVec3 view = viewMode.viewDirection();
        CanonicalVec3 forward = new CanonicalVec3(view.x(), view.y(), 0.0D).normalize();
        if (forward.lengthSquared() <= 1.0E-12D) {
            forward = CanonicalVec3.DEPTH.scale(-1.0D);
        }
        CanonicalVec3 right = forward.cross(CanonicalVec3.UP).normalize();
        return right.scale(horizontal)
                .add(forward.scale(vertical))
                .add(CanonicalVec3.UP.scale(rise));
    }

    public CanonicalVec3 constrainPosition(CanonicalVec3 position) {
        Objects.requireNonNull(position, "position");
        if (this.type == Type.PLANE_LOCKED || this.type == Type.PLANE_WITH_DEPTH_DODGE) {
            return position.withComponent(this.lockedAxis, this.lockedValue);
        }
        return position;
    }

    public enum Type {
        PLANE_LOCKED,
        FREE_3D,
        PLANE_WITH_DEPTH_DODGE,
        LOCKED
    }

    public enum Basis {
        CANONICAL,
        CAMERA
    }
}
