package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Locale;
import java.util.Objects;

// 2D、2.5D 与 3D 只改变观察方式。
public record BattleViewMode(
        Type type,
        CanonicalVec3 viewDirection,
        CanonicalVec3 cameraUp,
        BattleCoordinateSpace.Axis lockedAxis,
        double lockedValue,
        double orthoHeight,
        double verticalCenterOffset,
        Camera camera,
        Framing framing
) {
    // 标准 2D 纵向覆盖 6.25L：原点距底部 2.75L、距顶部 3.5L。
    public static final double STANDARD_2D_HEIGHT_L = 6.25D;
    public static final double STANDARD_2D_ORIGIN_FROM_BOTTOM_L = 2.75D;
    public static final double STANDARD_2D_TOP_FROM_ORIGIN_L = 3.5D;
    public static final double STANDARD_2D_VERTICAL_CENTER_L =
            (STANDARD_2D_TOP_FROM_ORIGIN_L - STANDARD_2D_ORIGIN_FROM_BOTTOM_L) * 0.5D;
    public static final double DEFAULT_ORTHO_HEIGHT_BU = STANDARD_2D_HEIGHT_L;
    public static final BattleViewMode ORTHO_2D = orthographic2d();
    public static final BattleViewMode PERSPECTIVE_3D = perspective3d(
            new Camera(new CanonicalVec3(0.0D, 4.0D, 0.0D), CanonicalVec3.ZERO, 45.0D)
    );

    public BattleViewMode {
        Objects.requireNonNull(type, "type");
        viewDirection = requireDirection(viewDirection, "viewDirection");
        cameraUp = requireDirection(cameraUp, "cameraUp");
        if (Math.abs(viewDirection.dot(cameraUp)) > 1.0E-6D) {
            throw new IllegalArgumentException("viewDirection and cameraUp must be orthogonal.");
        }
        if (!Double.isFinite(lockedValue)) {
            throw new IllegalArgumentException("lockedValue must be finite.");
        }
        if (!Double.isFinite(orthoHeight) || orthoHeight <= 0.0D) {
            throw new IllegalArgumentException("orthoHeight must be finite and > 0.");
        }
        if (!Double.isFinite(verticalCenterOffset)) {
            throw new IllegalArgumentException("verticalCenterOffset must be finite.");
        }
        Objects.requireNonNull(framing, "framing");
        boolean orthographic = type == Type.ORTHO_2D || type == Type.ORTHO_2_5D;
        if (orthographic && lockedAxis == null) {
            throw new IllegalArgumentException("Orthographic view modes require lockedAxis.");
        }
        if ((type == Type.PERSPECTIVE_3D || type == Type.SCRIPTED_CAMERA) && camera == null) {
            throw new IllegalArgumentException(type.id() + " requires camera.");
        }
        if (!orthographic && framing.type() != Framing.Type.FIXED_ORTHO_HEIGHT) {
            throw new IllegalArgumentException("Perspective view modes do not accept orthographic framing.");
        }
        if (type == Type.ORTHO_2D
                && (Double.compare(orthoHeight, STANDARD_2D_HEIGHT_L) != 0
                || framing.type() != Framing.Type.FIXED_ORTHO_HEIGHT)) {
            throw new IllegalArgumentException("Standard ortho_2d uses the fixed 6.25L viewport contract.");
        }
    }

    public static BattleViewMode orthographic2d() {
        return new BattleViewMode(
                Type.ORTHO_2D,
                CanonicalVec3.DEPTH.scale(-1.0D),
                CanonicalVec3.UP,
                BattleCoordinateSpace.Axis.Y,
                0.0D,
                STANDARD_2D_HEIGHT_L,
                STANDARD_2D_VERTICAL_CENTER_L,
                null,
                Framing.fixed()
        );
    }

    public static BattleViewMode orthographic2_5d(double orthoHeight, Framing framing) {
        return new BattleViewMode(
                Type.ORTHO_2_5D,
                CanonicalVec3.DEPTH.scale(-1.0D),
                CanonicalVec3.UP,
                BattleCoordinateSpace.Axis.Y,
                0.0D,
                orthoHeight,
                0.0D,
                null,
                framing
        );
    }

    public static BattleViewMode perspective3d(Camera camera) {
        CanonicalVec3 viewDirection = camera.target().subtract(camera.position()).normalize();
        CanonicalVec3 up = orthogonalCameraUp(viewDirection);
        return new BattleViewMode(Type.PERSPECTIVE_3D, viewDirection, up, null, 0.0D,
                DEFAULT_ORTHO_HEIGHT_BU, 0.0D, camera, Framing.fixed());
    }

    public static BattleViewMode scriptedCamera(Camera camera) {
        return scriptedCamera(camera, 0.0D);
    }

    public static BattleViewMode scriptedCamera(Camera camera, double verticalCenterOffset) {
        CanonicalVec3 viewDirection = camera.target().subtract(camera.position()).normalize();
        CanonicalVec3 up = orthogonalCameraUp(viewDirection);
        return new BattleViewMode(Type.SCRIPTED_CAMERA, viewDirection, up, null, 0.0D,
                DEFAULT_ORTHO_HEIGHT_BU, verticalCenterOffset, camera, Framing.fixed());
    }

    private static CanonicalVec3 orthogonalCameraUp(CanonicalVec3 viewDirection) {
        CanonicalVec3 candidate = Math.abs(viewDirection.dot(CanonicalVec3.UP)) > 0.999D
                ? CanonicalVec3.DEPTH
                : CanonicalVec3.UP;
        CanonicalVec3 right = viewDirection.cross(candidate).normalize();
        return right.cross(viewDirection).normalize();
    }

    // 相机始终观察 canonical 原点；零 yaw/pitch 时位于 +Y 深度轴。
    public static Camera orbitCamera(double yawDeg, double pitchDeg, double distance, double fovDegrees) {
        if (!Double.isFinite(distance) || distance <= 0.0D) {
            throw new IllegalArgumentException("Camera orbit distance must be finite and > 0.");
        }
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double horizontal = Math.cos(pitch) * distance;
        return new Camera(
                new CanonicalVec3(
                        Math.sin(yaw) * horizontal,
                        Math.cos(yaw) * horizontal,
                        -Math.sin(pitch) * distance
                ),
                CanonicalVec3.ZERO,
                fovDegrees
        );
    }

    public boolean orthographic() {
        return this.type == Type.ORTHO_2D || this.type == Type.ORTHO_2_5D;
    }

    public boolean allowsFreeDepthMovement() {
        return this.type == Type.PERSPECTIVE_3D || this.type == Type.SCRIPTED_CAMERA;
    }

    public ProjectionPolicy projection() {
        CanonicalVec3 right = this.viewDirection.cross(this.cameraUp).normalize();
        CanonicalVec3 correctedUp = right.cross(this.viewDirection).normalize();
        return new ProjectionPolicy(right, correctedUp, this.viewDirection.scale(-1.0D));
    }

    public double effectiveOrthoHeight(int viewportWidth, int viewportHeight) {
        if (this.type == Type.ORTHO_2D) {
            return STANDARD_2D_HEIGHT_L;
        }
        if (!orthographic() || this.framing.type() == Framing.Type.FIXED_ORTHO_HEIGHT) {
            return this.orthoHeight;
        }
        double aspect = Math.max(1, viewportWidth) / (double) Math.max(1, viewportHeight);
        ProjectionPolicy projection = projection();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double x : new double[] {this.framing.min().x(), this.framing.max().x()}) {
            for (double y : new double[] {this.framing.min().y(), this.framing.max().y()}) {
                for (double z : new double[] {this.framing.min().z(), this.framing.max().z()}) {
                    ProjectionPolicy.ProjectedPoint point = projection.project(new CanonicalVec3(x, y, z));
                    minX = Math.min(minX, point.x());
                    minY = Math.min(minY, point.y());
                    maxX = Math.max(maxX, point.x());
                    maxY = Math.max(maxY, point.y());
                }
            }
        }
        double padding = 1.0D + this.framing.paddingPercent() / 100.0D;
        double required = Math.max(maxY - minY, (maxX - minX) / aspect) * padding;
        return Math.max(this.orthoHeight, required);
    }

    public enum Type {
        ORTHO_2D("ortho_2d"),
        ORTHO_2_5D("ortho_2_5d"),
        PERSPECTIVE_3D("perspective_3d"),
        SCRIPTED_CAMERA("scripted_camera");

        private final String id;

        Type(String id) {
            this.id = id;
        }

        public String id() {
            return this.id;
        }

        public static Type parse(String id, String path) {
            for (Type value : values()) {
                if (value.id.equals(id.toLowerCase(Locale.ROOT))) {
                    return value;
                }
            }
            throw new IllegalArgumentException(path + " has unsupported view mode: " + id + ".");
        }
    }

    public record Camera(CanonicalVec3 position, CanonicalVec3 target, double fovDegrees) {
        public Camera {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(target, "target");
            if (position.subtract(target).lengthSquared() <= 1.0E-12D) {
                throw new IllegalArgumentException("Camera position must differ from target.");
            }
            if (!Double.isFinite(fovDegrees) || fovDegrees <= 0.0D || fovDegrees >= 179.0D) {
                throw new IllegalArgumentException("fovDegrees must be greater than 0 and less than 179.");
            }
        }
    }

    public record Framing(Type type, CanonicalVec3 min, CanonicalVec3 max, double paddingPercent) {
        public Framing {
            Objects.requireNonNull(type, "type");
            if (!Double.isFinite(paddingPercent) || paddingPercent < 0.0D || paddingPercent > 100.0D) {
                throw new IllegalArgumentException("paddingPercent must be between 0 and 100.");
            }
            if (type == Type.FIT_CANONICAL_BOUNDS) {
                Objects.requireNonNull(min, "min");
                Objects.requireNonNull(max, "max");
                if (min.x() >= max.x() || min.y() >= max.y() || min.z() >= max.z()) {
                    throw new IllegalArgumentException("Framing canonical bounds must satisfy min < max on every axis.");
                }
            } else if (min != null || max != null || paddingPercent != 0.0D) {
                throw new IllegalArgumentException("Fixed framing must not contain bounds or padding.");
            }
        }

        public static Framing fixed() {
            return new Framing(Type.FIXED_ORTHO_HEIGHT, null, null, 0.0D);
        }

        public static Framing fitCanonicalBounds(CanonicalVec3 min, CanonicalVec3 max, double paddingPercent) {
            return new Framing(Type.FIT_CANONICAL_BOUNDS, min, max, paddingPercent);
        }

        public enum Type {
            FIXED_ORTHO_HEIGHT,
            FIT_CANONICAL_BOUNDS
        }
    }

    private static CanonicalVec3 requireDirection(CanonicalVec3 value, String name) {
        Objects.requireNonNull(value, name);
        CanonicalVec3 normalized = value.normalize();
        if (normalized.lengthSquared() == 0.0D) {
            throw new IllegalArgumentException(name + " must not be zero.");
        }
        return normalized;
    }
}
