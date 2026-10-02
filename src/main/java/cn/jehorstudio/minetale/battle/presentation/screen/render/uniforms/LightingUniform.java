package cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms;

import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Vector3f;
import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleScene;
import cn.jehorstudio.minetale.battle.presentation.screen.render.Lighting;

import java.nio.ByteBuffer;

// Rendered entity、BattleFrame 与 shadow pass 共享的单帧光照 UBO。
public final class LightingUniform implements AutoCloseable {
    private static final int BLOCK_SIZE = new Std140SizeCalculator()
            .putMat4f().putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
            .get();

    private final MappableRingBuffer buffer = new MappableRingBuffer(
            () -> "Battle lighting uniforms", 130, BLOCK_SIZE);

    public void upload(
            BattleScene.Frame frame,
            Lighting.Settings settings,
            Lighting.LightSpace lightSpace
    ) {
        Vector3f direction = new Vector3f(
                (float) settings.globalLightDirection().x(),
                (float) settings.globalLightDirection().y(),
                (float) settings.globalLightDirection().z()
        );
        Vector3f camera = frame.camera().position();
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.buffer.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            lightSpace.viewProjection().get(0, data);
            // direction.w 固定为 0；vec4 宽度必须匹配共享 Shader 的 std140 布局。
            putVec4(data, 64, direction.x(), direction.y(), direction.z(), 0.0F);
            putVec4(data, 80, camera.x(), camera.y(), camera.z(), settings.diffuseIntensity());
            putVec4(data, 96, settings.specularIntensity(), settings.shininess(),
                    settings.shadowBias(), settings.shadowStrength());
            putVec4(data, 112, 1.0F / settings.shadowMapSize(),
                    settings.shadowsEnabled() ? 1.0F : 0.0F,
                    settings.extrudedImageSideBrightnessReduction(), 0.0F);
            frame.soulTintLight().ifPresentOrElse(light -> {
                int color = light.color();
                putVec4(data, 128,
                        (float) light.modelBoundsCenter().x(),
                        (float) light.modelBoundsCenter().y(),
                        (float) light.modelBoundsCenter().z(),
                        settings.soulTintLightRadius());
                putVec4(data, 144,
                        ((color >>> 16) & 0xFF) / 255.0F,
                        ((color >>> 8) & 0xFF) / 255.0F,
                        (color & 0xFF) / 255.0F,
                        1.0F + settings.soulTintLightEmissionStrength());
            }, () -> {
                putVec4(data, 128, 0.0F, 0.0F, 0.0F, settings.soulTintLightRadius());
                putVec4(data, 144, 0.0F, 0.0F, 0.0F, 0.0F);
            });
        }
    }

    public void bind(RenderPass pass) {
        pass.setUniform("LightingUniform", this.buffer.currentBuffer());
    }

    public void rotate() {
        this.buffer.rotate();
    }

    @Override
    public void close() {
        this.buffer.close();
    }

    private static void putVec4(ByteBuffer data, int offset, float x, float y, float z, float w) {
        data.putFloat(offset, x);
        data.putFloat(offset + 4, y);
        data.putFloat(offset + 8, z);
        data.putFloat(offset + 12, w);
    }
}
