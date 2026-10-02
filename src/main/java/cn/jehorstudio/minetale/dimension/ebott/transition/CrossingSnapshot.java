package cn.jehorstudio.minetale.dimension.ebott.transition;

import cn.jehorstudio.minetale.dimension.ebott.transition.seam.DimensionSeam;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

// 冻结穿面发生 Tick 的连续性数据
record CrossingSnapshot(
        Vec3 sourceEyeProbe,
        Vec3 sourceBasePosition,
        double seamRelativeEyeDepth,
        Vec3 velocity,
        float yaw,
        float pitch,
        double localX,
        double localZ,
        double playerEyeHeight
) {
    CrossingSnapshot {
        requireFinite(sourceEyeProbe, "sourceEyeProbe");
        requireFinite(sourceBasePosition, "sourceBasePosition");
        requireFinite(velocity, "velocity");
        requireFinite(seamRelativeEyeDepth, "seamRelativeEyeDepth");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
        requireFinite(localX, "localX");
        requireFinite(localZ, "localZ");
        requireFinite(playerEyeHeight, "playerEyeHeight");
        if (!(playerEyeHeight > 0.0D)) {
            throw new IllegalArgumentException("playerEyeHeight must be positive");
        }
    }

    static CrossingSnapshot capture(
            Vec3 sourceEyeProbe,
            Vec3 sourceBasePosition,
            double sourceSeamY,
            Vec3 velocity,
            float yaw,
            float pitch,
            double sourceCenterX,
            double sourceCenterZ,
            double playerEyeHeight
    ) {
        Objects.requireNonNull(sourceEyeProbe, "sourceEyeProbe");
        Objects.requireNonNull(sourceBasePosition, "sourceBasePosition");
        return new CrossingSnapshot(
                sourceEyeProbe,
                sourceBasePosition,
                sourceEyeProbe.y - sourceSeamY,
                velocity,
                yaw,
                pitch,
                sourceBasePosition.x - sourceCenterX,
                sourceBasePosition.z - sourceCenterZ,
                playerEyeHeight
        );
    }

    Vec3 targetEyeProbe(DimensionSeam.SeamTransform transform) {
        Objects.requireNonNull(transform, "transform");
        return new Vec3(
                transform.targetCenterX() + this.localX,
                transform.targetPlaneY() + this.seamRelativeEyeDepth,
                transform.targetCenterZ() + this.localZ
        );
    }

    Vec3 targetBasePosition(DimensionSeam.SeamTransform transform) {
        Vec3 targetEye = targetEyeProbe(transform);
        return new Vec3(targetEye.x, targetEye.y - this.playerEyeHeight, targetEye.z);
    }

    private static void requireFinite(Vec3 value, String name) {
        if (value == null || !Double.isFinite(value.x)
                || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
