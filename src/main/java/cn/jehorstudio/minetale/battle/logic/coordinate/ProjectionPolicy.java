package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Objects;

// 纯逻辑投影基，用于 canonical 坐标与当前视平面坐标之间的换算。
public record ProjectionPolicy(
        CanonicalVec3 right,
        CanonicalVec3 up,
        CanonicalVec3 forward
) {
    public ProjectionPolicy {
        right = requireUnit(right, "right");
        up = requireUnit(up, "up");
        forward = requireUnit(forward, "forward");
        if (Math.abs(right.dot(up)) > 1.0E-6D
                || Math.abs(right.dot(forward)) > 1.0E-6D
                || Math.abs(up.dot(forward)) > 1.0E-6D) {
            throw new IllegalArgumentException("Projection basis axes must be orthogonal.");
        }
    }

    public ProjectedPoint project(CanonicalVec3 point) {
        Objects.requireNonNull(point, "point");
        return new ProjectedPoint(point.dot(this.right), point.dot(this.up), point.dot(this.forward));
    }

    public CanonicalVec3 pointOnViewPlane(double horizontal, double vertical, double depth) {
        return this.right.scale(horizontal)
                .add(this.up.scale(vertical))
                .add(this.forward.scale(depth));
    }

    public record ProjectedPoint(double x, double y, double depth) {
    }

    private static CanonicalVec3 requireUnit(CanonicalVec3 value, String name) {
        Objects.requireNonNull(value, name);
        CanonicalVec3 normalized = value.normalize();
        if (normalized.lengthSquared() == 0.0D) {
            throw new IllegalArgumentException(name + " must not be zero.");
        }
        return normalized;
    }
}
