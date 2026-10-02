package cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms;

import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.Lighting;
import cn.jehorstudio.minetale.battle.presentation.screen.render.volume.VolumetricShadows;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.Objects;

// 一次上传 PointPlan，并在绘制时同时绑定六面 shadow atlas 与 Soul 点光 UBO。
// 矩阵、半径、atlas 布局及 disabled 退化均由该类持有。
public final class SoulPointLightUniform implements AutoCloseable {
    private static final int BLOCK_SIZE = new Std140SizeCalculator()
            .putMat4f().putMat4f().putMat4f()
            .putMat4f().putMat4f().putMat4f()
            .putVec4().putVec4().putVec4().putVec4()
            .get();

    private final MappableRingBuffer buffer = new MappableRingBuffer(
            () -> "Battle Soul surface point-light uniforms", 130, BLOCK_SIZE);

    public void upload(
            VolumetricShadows.PointPlan plan,
            Lighting.Settings lighting
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(lighting, "lighting");
        boolean enabled = plan.enabled()
                && VisualConfig.SOUL_SURFACE_LIGHT_INTENSITY() > 0.0F;
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.buffer.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            for (int face = 0; face < VolumetricShadows.POINT_FACE_COUNT; face++) {
                Matrix4f matrix = enabled
                        ? plan.faceViewProjections()[face]
                        : new Matrix4f().zero();
                matrix.get(face * 64, data);
            }

            Vector3f position = enabled ? plan.lightPosition() : new Vector3f();
            putVec4(data, 384,
                    position.x(), position.y(), position.z(),
                    enabled ? plan.captureRadius() : 0.0F);
            int color = enabled ? plan.lightColor() : 0;
            putVec4(data, 400,
                    ((color >>> 16) & 0xFF) / 255.0F,
                    ((color >>> 8) & 0xFF) / 255.0F,
                    (color & 0xFF) / 255.0F,
                    lighting.soulTintLightRadius());
            putVec4(data, 416,
                    Math.max(0.0F, VisualConfig.SOUL_SURFACE_LIGHT_INTENSITY()),
                    Math.max(0.0F, VisualConfig.SOUL_SURFACE_LIGHT_SHADOW_BIAS()),
                    enabled ? 1.0F : 0.0F,
                    0.0F);
            VolumetricShadows.PointTargetLayout layout = plan.layout();
            putVec4(data, 432,
                    layout == null ? 1 : layout.faceSize(),
                    layout == null ? 1 : layout.border(),
                    layout == null ? 3 : layout.tileSize(),
                    enabled ? 1.0F : 0.0F);
        }
    }

    public void bind(RenderPass pass, GpuTextureView pointShadowAtlas) {
        pass.bindSampler("PointShadowAtlas", pointShadowAtlas);
        pass.setUniform("SoulPointLightUniform", this.buffer.currentBuffer());
    }

    public void rotate() {
        this.buffer.rotate();
    }

    @Override
    public void close() {
        this.buffer.close();
    }

    private static void putVec4(
            ByteBuffer data,
            int offset,
            float x,
            float y,
            float z,
            float w
    ) {
        data.putFloat(offset, x);
        data.putFloat(offset + 4, y);
        data.putFloat(offset + 8, z);
        data.putFloat(offset + 12, w);
    }
}
