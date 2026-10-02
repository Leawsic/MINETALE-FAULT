package cn.jehorstudio.minetale.dimension.ebott.transition.seam;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

// 根据连续 probe、滞回侧与线段交点判断穿面
public final class SeamCrossingDetector {
    public static final double DEFAULT_CROSS_EPSILON = 0.03;
    public static final double DEFAULT_EDGE_SAFETY_MARGIN = 0.15;

    private SeamCrossingDetector() {
    }

    public static State initialize(Vec3 probe, double sourcePlaneY, double epsilon) {
        requireFinite(probe, "probe");
        requireFinite(sourcePlaneY, "sourcePlaneY");
        SeamSide side = SeamSide.classify(probe.y - sourcePlaneY, epsilon);
        return new State(null, probe, definiteSide(side), null);
    }

    public static Observation observe(
            State state,
            Vec3 currentProbe,
            double sourcePlaneY,
            double sourceCenterX,
            double sourceCenterZ,
            DimensionSeam.CircularSeamAperture aperture,
            double playerBoundingRadius,
            double edgeSafetyMargin,
            double epsilon
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(aperture, "aperture");
        requireFinite(currentProbe, "currentProbe");
        requireFinite(sourcePlaneY, "sourcePlaneY");
        requireFinite(sourceCenterX, "sourceCenterX");
        requireFinite(sourceCenterZ, "sourceCenterZ");
        requireNonNegativeFinite(playerBoundingRadius, "playerBoundingRadius");
        requireNonNegativeFinite(edgeSafetyMargin, "edgeSafetyMargin");

        Vec3 previousProbe = state.currentProbe();
        double d0 = previousProbe.y - sourcePlaneY;
        double d1 = currentProbe.y - sourcePlaneY;
        SeamSide observedSide = SeamSide.classify(d1, epsilon);
        Vec3 pendingIntersection = state.pendingIntersection();

        if (observedSide == SeamSide.SOURCE || d1 > d0) {
            pendingIntersection = null;
        }
        if (d1 < d0 && d0 > 0.0 && d1 <= 0.0) {
            double denominator = d0 - d1;
            double t = d0 / denominator;
            Vec3 hit = previousProbe.lerp(currentProbe, t);
            double localX = hit.x - sourceCenterX;
            double localZ = hit.z - sourceCenterZ;
            double requiredClearance = playerBoundingRadius + edgeSafetyMargin;
            pendingIntersection = aperture.signedBoundaryDistance(localX, localZ)
                    >= requiredClearance ? hit : null;
        }

        boolean invalidInitialTarget = observedSide == SeamSide.TARGET
                && state.lastDefiniteSide() != SeamSide.SOURCE;
        boolean crossed = observedSide == SeamSide.TARGET
                && state.lastDefiniteSide() == SeamSide.SOURCE
                && pendingIntersection != null;
        SeamSide nextDefiniteSide = observedSide == SeamSide.BAND
                ? state.lastDefiniteSide()
                : observedSide;
        State nextState = new State(
                previousProbe,
                currentProbe,
                nextDefiniteSide,
                crossed ? null : pendingIntersection
        );
        return new Observation(
                nextState,
                observedSide,
                crossed ? pendingIntersection : null,
                invalidInitialTarget
        );
    }

    private static SeamSide definiteSide(SeamSide side) {
        return side == SeamSide.BAND ? null : side;
    }

    private static void requireFinite(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireNonNegativeFinite(double value, String name) {
        requireFinite(value, name);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    public record State(
            Vec3 previousProbe,
            Vec3 currentProbe,
            SeamSide lastDefiniteSide,
            Vec3 pendingIntersection
    ) {
        public State {
            requireFinite(currentProbe, "currentProbe");
            if (previousProbe != null) {
                requireFinite(previousProbe, "previousProbe");
            }
            if (pendingIntersection != null) {
                requireFinite(pendingIntersection, "pendingIntersection");
            }
            if (lastDefiniteSide == SeamSide.BAND) {
                throw new IllegalArgumentException("last definite seam side cannot be BAND");
            }
        }
    }

    public record Observation(
            State state,
            SeamSide observedSide,
            Vec3 crossingHit,
            boolean invalidInitialTarget
    ) {
        public Observation {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(observedSide, "observedSide");
            if (crossingHit != null) {
                requireFinite(crossingHit, "crossingHit");
            }
        }

        public boolean crossed() {
            return crossingHit != null;
        }
    }

    // 记录 probe 相对源端平面的稳定侧，以防止贴面抖动重复触发。
    public enum SeamSide {
        SOURCE,
        BAND,
        TARGET;

        public static SeamSide classify(double signedPlaneDistance, double epsilon) {
            if (!Double.isFinite(signedPlaneDistance) || !Double.isFinite(epsilon) || epsilon <= 0.0) {
                throw new IllegalArgumentException("invalid seam side input");
            }
            if (signedPlaneDistance > epsilon) {
                return SOURCE;
            }
            if (signedPlaneDistance < -epsilon) {
                return TARGET;
            }
            return BAND;
        }
    }
}
