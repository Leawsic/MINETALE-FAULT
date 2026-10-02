package cn.jehorstudio.minetale.lib.client.particle.gpu;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

// 提取阶段冻结的粒子帧
// 渲染阶段只能消费该快照
public record ParticleFrame(
        Vec3 anchor,
        Vec3 axis,
        Vec3 radialRight,
        Vec3 radialUp,
        Vec3 billboardRight,
        Vec3 billboardUp,
        float motionTimeSeconds,
        float radialScale,
        float axialScale,
        float particleSizeScale,
        float intensity,
        float visibility,
        Bounds bounds
) {
    public ParticleFrame {
        requireFinite(anchor, "anchor");
        requireDirection(axis, "axis");
        requireDirection(radialRight, "radialRight");
        requireDirection(radialUp, "radialUp");
        requireDirection(billboardRight, "billboardRight");
        requireDirection(billboardUp, "billboardUp");
        axis = axis.normalize();
        radialRight = radialRight.normalize();
        radialUp = radialUp.normalize();
        billboardRight = billboardRight.normalize();
        billboardUp = billboardUp.normalize();
        requireNonNegative(motionTimeSeconds, "motionTimeSeconds");
        requireNonNegative(radialScale, "radialScale");
        requireNonNegative(axialScale, "axialScale");
        requirePositive(particleSizeScale, "particleSizeScale");
        requireUnit(intensity, "intensity");
        requireUnit(visibility, "visibility");
        Objects.requireNonNull(bounds, "bounds");
    }

    // 世界空间保守包围范围只用于裁剪
    public record Bounds(float minimumAxial, float maximumAxial, float radialExtent) {
        public Bounds {
            requireFinite(minimumAxial, "bounds.minimumAxial");
            requireFinite(maximumAxial, "bounds.maximumAxial");
            requireNonNegative(radialExtent, "bounds.radialExtent");
            if (minimumAxial > maximumAxial) {
                throw new IllegalArgumentException("bounds.minimumAxial 不得大于 maximumAxial");
            }
        }
    }

    private static void requireDirection(Vec3 value, String name) {
        requireFinite(value, name);
        if (value.lengthSqr() < 1.0E-8) {
            throw new IllegalArgumentException(name + " 不得为零向量");
        }
    }

    private static void requireFinite(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " 必须为有限向量");
        }
    }

    private static void requirePositive(float value, String name) {
        requireFinite(value, name);
        if (value <= 0.0F) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }

    private static void requireNonNegative(float value, String name) {
        requireFinite(value, name);
        if (value < 0.0F) {
            throw new IllegalArgumentException(name + " 不得为负");
        }
    }

    private static void requireUnit(float value, String name) {
        requireFinite(value, name);
        if (value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " 必须在 0 到 1 之间");
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须为有限值");
        }
    }
}
