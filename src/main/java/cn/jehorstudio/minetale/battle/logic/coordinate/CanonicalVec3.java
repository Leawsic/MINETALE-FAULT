package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Objects;

// 单位为 BU 的 canonical 向量。
public record CanonicalVec3(double x, double y, double z) {
    public static final CanonicalVec3 ZERO = new CanonicalVec3(0.0D, 0.0D, 0.0D);
    public static final CanonicalVec3 ONE = new CanonicalVec3(1.0D, 1.0D, 1.0D);
    public static final CanonicalVec3 RIGHT = new CanonicalVec3(1.0D, 0.0D, 0.0D);
    // canonical 全局上轴约定为 -Z。
    public static final CanonicalVec3 UP = new CanonicalVec3(0.0D, 0.0D, -1.0D);
    // canonical 正深度轴约定为 +Y。
    public static final CanonicalVec3 DEPTH = new CanonicalVec3(0.0D, 1.0D, 0.0D);

    public CanonicalVec3 {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    public CanonicalVec3 add(CanonicalVec3 other) {
        Objects.requireNonNull(other, "other");
        return new CanonicalVec3(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public CanonicalVec3 add(double x, double y, double z) {
        return new CanonicalVec3(this.x + x, this.y + y, this.z + z);
    }

    public CanonicalVec3 subtract(CanonicalVec3 other) {
        Objects.requireNonNull(other, "other");
        return new CanonicalVec3(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public CanonicalVec3 scale(double factor) {
        requireFinite(factor, "factor");
        return new CanonicalVec3(this.x * factor, this.y * factor, this.z * factor);
    }

    public double dot(CanonicalVec3 other) {
        Objects.requireNonNull(other, "other");
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public CanonicalVec3 cross(CanonicalVec3 other) {
        Objects.requireNonNull(other, "other");
        return new CanonicalVec3(
                this.y * other.z - this.z * other.y,
                this.z * other.x - this.x * other.z,
                this.x * other.y - this.y * other.x
        );
    }

    public double lengthSquared() {
        return dot(this);
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public CanonicalVec3 normalize() {
        double length = length();
        if (length <= 1.0E-12D) {
            return ZERO;
        }
        return scale(1.0D / length);
    }

    public CanonicalVec3 lerp(CanonicalVec3 to, double progress) {
        return lerp(this, to, progress);
    }

    public double component(BattleCoordinateSpace.Axis axis) {
        return switch (Objects.requireNonNull(axis, "axis")) {
            case X -> this.x;
            case Y -> this.y;
            case Z -> this.z;
        };
    }

    public CanonicalVec3 withComponent(BattleCoordinateSpace.Axis axis, double value) {
        requireFinite(value, "value");
        return switch (Objects.requireNonNull(axis, "axis")) {
            case X -> new CanonicalVec3(value, this.y, this.z);
            case Y -> new CanonicalVec3(this.x, value, this.z);
            case Z -> new CanonicalVec3(this.x, this.y, value);
        };
    }

    public static CanonicalVec3 lerp(CanonicalVec3 from, CanonicalVec3 to, double progress) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        requireFinite(progress, "progress");
        return from.add(to.subtract(from).scale(progress));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }
}
