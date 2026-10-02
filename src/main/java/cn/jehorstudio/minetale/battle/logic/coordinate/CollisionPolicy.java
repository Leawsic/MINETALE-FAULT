package cn.jehorstudio.minetale.battle.logic.coordinate;

import java.util.Objects;

// 只改变碰撞体的解释方式。
public record CollisionPolicy(Type type, double maxDepthDistanceBu) {
    public static final CollisionPolicy VOLUME_3D = new CollisionPolicy(Type.VOLUME_3D, 0.0D);
    public static final CollisionPolicy PROJECTED_2D = new CollisionPolicy(Type.PROJECTED_2D, 0.0D);

    public CollisionPolicy {
        Objects.requireNonNull(type, "type");
        if (!Double.isFinite(maxDepthDistanceBu) || maxDepthDistanceBu < 0.0D) {
            throw new IllegalArgumentException("maxDepthDistanceBu must be finite and >= 0.");
        }
        if (type != Type.HYBRID_DEPTH_BAND && maxDepthDistanceBu != 0.0D) {
            throw new IllegalArgumentException("Only hybrid_depth_band accepts maxDepthDistanceBu.");
        }
    }

    public static CollisionPolicy hybrid(double maxDepthDistanceBu) {
        return new CollisionPolicy(Type.HYBRID_DEPTH_BAND, maxDepthDistanceBu);
    }

    public enum Type {
        VOLUME_3D,
        PROJECTED_2D,
        HYBRID_DEPTH_BAND
    }
}
