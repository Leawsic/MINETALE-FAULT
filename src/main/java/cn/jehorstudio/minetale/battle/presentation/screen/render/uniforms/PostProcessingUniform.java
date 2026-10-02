package cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms;

import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleScene;
import cn.jehorstudio.minetale.battle.presentation.screen.render.PostProcessing;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.MappableRingBuffer;

// Bloom 单帧 UBO：Bloom、Pyramid Strategy、Resolution 三个 vec4，std140 共 48 字节。
public final class PostProcessingUniform implements AutoCloseable {
    private static final int BLOCK_SIZE = new Std140SizeCalculator()
            .putVec4().putVec4().putVec4()
            .get();

    private final MappableRingBuffer buffer = new MappableRingBuffer(
            () -> "Battle post-processing uniforms", 130, BLOCK_SIZE);

    public void upload(BattleScene.Frame frame, PostProcessing.FramePlan plan) {
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.buffer.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            putVec4(
                    data,
                    0,
                    Math.max(0.1F, VisualConfig.POST_BLOOM_EMISSIVE_CURVE()),
                    Math.max(0.0F, VisualConfig.POST_BLOOM_INTENSITY()),
                    Math.max(0.0F, VisualConfig.POST_BLOOM_BLUR_RADIUS_PIXELS()),
                    plan.bloomLevels()
            );
            putVec4(
                    data,
                    16,
                    plan.bloomFirstLevelSamples(),
                    0.0F,
                    0.0F,
                    0.0F
            );
            putVec4(
                    data,
                    32,
                    frame.viewport().width(),
                    frame.viewport().height(),
                    1.0F / frame.viewport().width(),
                    1.0F / frame.viewport().height()
            );
        }
    }

    public void bind(RenderPass pass) {
        pass.setUniform("PostProcessingUniform", this.buffer.currentBuffer());
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
