package cn.jehorstudio.minetale.battle.presentation.timeline;

import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;

import java.util.Objects;

public sealed interface InterpolatedValue permits InterpolatedValue.VectorValue, InterpolatedValue.DoubleValue {
    record VectorValue(CanonicalVec3 value) implements InterpolatedValue {
        public VectorValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record DoubleValue(double value) implements InterpolatedValue {
        public DoubleValue {
            requireFinite(value, "value");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }
}
