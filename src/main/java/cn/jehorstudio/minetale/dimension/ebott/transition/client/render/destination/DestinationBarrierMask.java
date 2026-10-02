package cn.jehorstudio.minetale.dimension.ebott.transition.client.render.destination;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;

// 逐帧计算视线是否穿过贴边结界，结果直接控制目标地形 MASK。
public final class DestinationBarrierMask implements AutoCloseable {
    public static final DestinationBarrierMask INSTANCE = new DestinationBarrierMask();
    public static final float CLIP_EPSILON = 0.03125F;
    private static final int UNIFORM_SIZE = 32;

    private MappableRingBuffer uniform;
    private boolean active;

    private DestinationBarrierMask() {
    }

    public void begin(
            double seamY,
            double blockGridCenterX,
            double blockGridCenterZ,
            double apertureCenterOffsetX,
            double apertureCenterOffsetZ,
            double radius,
            Vec3 cameraPosition
    ) {
        if (!Double.isFinite(seamY) || !Double.isFinite(blockGridCenterX)
                || !Double.isFinite(blockGridCenterZ)
                || !Double.isFinite(apertureCenterOffsetX)
                || !Double.isFinite(apertureCenterOffsetZ)
                || !Double.isFinite(radius)
                || !(radius > 0.0)) {
            throw new IllegalArgumentException("目标结界 MASK 参数无效");
        }
        if (this.active) {
            throw new IllegalStateException("目标结界 MASK 不允许嵌套");
        }
        if (this.uniform == null) {
            this.uniform = new MappableRingBuffer(
                    () -> "Destination barrier mask", 130, UNIFORM_SIZE);
        }
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.uniform.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            data.putFloat(0, (float) (seamY + CLIP_EPSILON - cameraPosition.y));
            data.putFloat(4, (float) (blockGridCenterX - cameraPosition.x));
            data.putFloat(8, (float) (blockGridCenterZ - cameraPosition.z));
            data.putFloat(12, (float) radius);
            data.putFloat(16, (float) apertureCenterOffsetX);
            data.putFloat(20, (float) apertureCenterOffsetZ);
            // MASK 锚定真实接缝面；epsilon 只属于地形裁剪，否则斜视轮廓会漂移。
            data.putFloat(24, (float) (seamY - cameraPosition.y));
            data.putFloat(28, 0.0F);
        }
        this.active = true;
    }

    public void bind(RenderPass pass) {
        if (!this.active || this.uniform == null) {
            throw new IllegalStateException("目标结界 MASK uniform 尚未准备");
        }
        pass.setUniform("EbottBarrierMask", this.uniform.currentBuffer());
    }

    public void end() {
        if (!this.active) {
            return;
        }
        this.active = false;
        this.uniform.rotate();
    }

    @Override
    public void close() {
        this.active = false;
        if (this.uniform != null) {
            this.uniform.close();
            this.uniform = null;
        }
    }
}
