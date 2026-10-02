package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.logic.coordinate.BattleCoordinateStateCache;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.ClearPolicy;
import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.Sampler;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.MappableRingBuffer;

// 在两张已完成 FXAA 的 Unmodified/Rendered 画面之间交叉淡化。
final class RenderModeCrossfade implements AutoCloseable {
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putFloat().get();
    private static final int UNIFORM_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;

    private final FullscreenPassRunner passes = new FullscreenPassRunner();
    private final MappableRingBuffer uniform = new MappableRingBuffer(
            () -> "Battle render-mode crossfade uniform",
            UNIFORM_USAGE,
            UNIFORM_SIZE
    );

    static FramePlan plan(
            BattleCoordinateStateCache.SceneMode sceneMode,
            double renderedBlend,
            VisualConfig.BattleRenderMode preferredThreeDimensionalMode
    ) {
        Objects.requireNonNull(sceneMode, "sceneMode");
        Objects.requireNonNull(preferredThreeDimensionalMode, "preferredThreeDimensionalMode");
        if (!Double.isFinite(renderedBlend) || renderedBlend < 0.0D || renderedBlend > 1.0D) {
            throw new IllegalArgumentException("renderedBlend must be finite and within [0, 1].");
        }
        if (sceneMode == BattleCoordinateStateCache.SceneMode.TWO_D) {
            if (renderedBlend != 0.0D) {
                throw new IllegalArgumentException("TWO_D scene requires renderedBlend=0.");
            }
            return FramePlan.UNMODIFIED;
        }
        if (preferredThreeDimensionalMode != VisualConfig.BattleRenderMode.RENDERED
                || renderedBlend <= 0.0D) {
            return FramePlan.UNMODIFIED;
        }
        if (renderedBlend >= 1.0D) {
            return FramePlan.RENDERED;
        }
        return new FramePlan(Branch.BOTH, (float) renderedBlend);
    }

    void process(
            TextureTarget unmodified,
            TextureTarget rendered,
            TextureTarget destination,
            FramePlan plan
    ) {
        if (!plan.crossfade()) {
            throw new IllegalArgumentException("单分支 FramePlan 不应执行 RenderModeCrossfade");
        }
        this.passes.beginFrame();
        try {
            try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.uniform.currentBuffer(), false, true)) {
                Std140Builder.intoBuffer(view.data()).putFloat(plan.renderedBlend());
            }
            this.passes.run(
                    "Battle render-mode crossfade",
                    PipelineRegister.BATTLE_RENDER_MODE_CROSSFADE,
                    List.of(
                            Sampler.color("UnmodifiedSampler", unmodified),
                            Sampler.color("RenderedSampler", rendered)
                    ),
                    destination,
                    ClearPolicy.TRANSPARENT_BLACK,
                    this::bind
            );
        } finally {
            this.uniform.rotate();
        }
    }

    private void bind(RenderPass pass) {
        pass.setUniform("RenderModeCrossfadeUniform", this.uniform.currentBuffer());
    }

    @Override
    public void close() {
        this.uniform.close();
    }

    enum Branch {
        UNMODIFIED,
        RENDERED,
        BOTH
    }

    record FramePlan(Branch branch, float renderedBlend) {
        private static final FramePlan UNMODIFIED = new FramePlan(Branch.UNMODIFIED, 0.0F);
        private static final FramePlan RENDERED = new FramePlan(Branch.RENDERED, 1.0F);

        FramePlan {
            Objects.requireNonNull(branch, "branch");
            if (!Float.isFinite(renderedBlend) || renderedBlend < 0.0F || renderedBlend > 1.0F) {
                throw new IllegalArgumentException("renderedBlend must be finite and within [0, 1].");
            }
            if (branch == Branch.UNMODIFIED && renderedBlend != 0.0F) {
                throw new IllegalArgumentException("Unmodified plan requires renderedBlend=0.");
            }
            if (branch == Branch.RENDERED && renderedBlend != 1.0F) {
                throw new IllegalArgumentException("Rendered plan requires renderedBlend=1.");
            }
            if (branch == Branch.BOTH && (renderedBlend <= 0.0F || renderedBlend >= 1.0F)) {
                throw new IllegalArgumentException("Crossfade plan requires 0 < renderedBlend < 1.");
            }
        }

        boolean needsUnmodified() {
            return this.branch != Branch.RENDERED;
        }

        boolean needsRendered() {
            return this.branch != Branch.UNMODIFIED;
        }

        boolean crossfade() {
            return this.branch == Branch.BOTH;
        }
    }
}
