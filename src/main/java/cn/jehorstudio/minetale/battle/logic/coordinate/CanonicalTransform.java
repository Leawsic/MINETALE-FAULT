package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Objects;

// Battle canonical space 中的绝对变换。
public record CanonicalTransform(
        BattleCoordinateSpace space,
        CanonicalVec3 position,
        CanonicalVec3 rotationDeg,
        CanonicalVec3 scale
) {
    public static final CanonicalTransform IDENTITY = new CanonicalTransform(
            BattleCoordinateSpace.CANONICAL,
            CanonicalVec3.ZERO,
            CanonicalVec3.ZERO,
            CanonicalVec3.ONE
    );

    public CanonicalTransform {
        if (Objects.requireNonNull(space, "space") != BattleCoordinateSpace.CANONICAL) {
            throw new IllegalArgumentException("Gameplay transform must use battle.canonical.");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(rotationDeg, "rotationDeg");
        Objects.requireNonNull(scale, "scale");
        if (scale.x() == 0.0D || scale.y() == 0.0D || scale.z() == 0.0D) {
            throw new IllegalArgumentException("Canonical transform scale axes must be non-zero.");
        }
    }

    public CanonicalTransform(CanonicalVec3 position, CanonicalVec3 rotationDeg, CanonicalVec3 scale) {
        this(BattleCoordinateSpace.CANONICAL, position, rotationDeg, scale);
    }

    public CanonicalTransform(
            CanonicalVec3 position,
            double yawDeg,
            double pitchDeg,
            double rollDeg,
            CanonicalVec3 scale
    ) {
        this(position, new CanonicalVec3(pitchDeg, yawDeg, rollDeg), scale);
    }

    public CanonicalTransform withPosition(CanonicalVec3 position) {
        return new CanonicalTransform(this.space, position, this.rotationDeg, this.scale);
    }

    public double yawDeg() {
        return this.rotationDeg.y();
    }

    public double pitchDeg() {
        return this.rotationDeg.x();
    }

    public double rollDeg() {
        return this.rotationDeg.z();
    }
}
