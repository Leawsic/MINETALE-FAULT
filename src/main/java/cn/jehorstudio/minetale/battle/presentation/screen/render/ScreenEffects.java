package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.ClearPolicy;
import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.Sampler;
import cn.jehorstudio.minetale.battle.presentation.states.ScreenEffectSnapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;

import java.util.List;

// 独占 transition 历史与逐帧 UBO；Pass 顺序固定为 transition → shake → flash → foreground overlay。
final class ScreenEffects implements AutoCloseable {
    private static final int TRANSITION_SIZE = new Std140SizeCalculator().putFloat().get();
    private static final int SHAKE_SIZE = new Std140SizeCalculator().putVec2().get();
    private static final int FLASH_SIZE = new Std140SizeCalculator().putVec4().get();
    private static final int FOREGROUND_SIZE =
            new Std140SizeCalculator().putVec4().putVec4().get();
    private static final int UNIFORM_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;

    private final FullscreenPassRunner passes = new FullscreenPassRunner();
    private final MappableRingBuffer transitionUniform =
            uniformBuffer("Battle transition uniform", TRANSITION_SIZE);
    private final MappableRingBuffer shakeUniform =
            uniformBuffer("Battle shake uniform", SHAKE_SIZE);
    private final MappableRingBuffer flashUniform =
            uniformBuffer("Battle flash uniform", FLASH_SIZE);
    private final MappableRingBuffer foregroundUniform =
            uniformBuffer("Battle foreground overlay uniform", FOREGROUND_SIZE);

    private TextureTarget historyTarget;
    private boolean historyReady;
    private long activeTransitionId = -1L;
    private boolean activeTransitionHasFreeze;

    void process(
            RenderTargets.TargetSet targets,
            ScreenEffectSnapshot effects,
            EnvironmentBackground.ForegroundTexture environmentTexture,
            int width,
            int height
    ) {
        synchronizeHistoryTarget(targets.history());
        upload(effects, environmentTexture, width, height);
        this.passes.beginFrame();

        applyTransition(targets, effects);
        runSingleInput(
                targets.raw(),
                targets.ping(),
                PipelineRegister.BATTLE_SCREEN_SHAKE,
                "Battle screen shake",
                this::bindShake
        );
        runSingleInput(
                targets.ping(),
                targets.pong(),
                PipelineRegister.BATTLE_SCREEN_FLASH,
                "Battle screen flash",
                this::bindFlash
        );
        applyForegroundOverlay(targets, effects, environmentTexture);
    }

    void reset() {
        this.historyTarget = null;
        this.historyReady = false;
        this.activeTransitionId = -1L;
        this.activeTransitionHasFreeze = false;
    }

    private void synchronizeHistoryTarget(TextureTarget target) {
        if (this.historyTarget == target) {
            return;
        }
        this.historyTarget = target;
        this.historyReady = false;
        this.activeTransitionId = -1L;
        this.activeTransitionHasFreeze = false;
    }

    private void applyTransition(RenderTargets.TargetSet targets, ScreenEffectSnapshot effects) {
        var transition = effects.transition();
        if (transition.isPresent()) {
            ScreenEffectSnapshot.Transition value = transition.get();
            if (value.id() != this.activeTransitionId) {
                this.activeTransitionId = value.id();
                this.activeTransitionHasFreeze = this.historyReady;
                if (this.activeTransitionHasFreeze) {
                    copy(targets.history(), targets.freeze(), "Battle transition capture");
                }
            }
        } else {
            this.activeTransitionId = -1L;
            this.activeTransitionHasFreeze = false;
        }

        if (transition.isPresent() && this.activeTransitionHasFreeze) {
            this.passes.run(
                    "Battle screen transition",
                    PipelineRegister.BATTLE_SCREEN_TRANSITION,
                    List.of(
                            Sampler.color("CurrentSampler", targets.pong()),
                            Sampler.color("FreezeSampler", targets.freeze())
                    ),
                    targets.raw(),
                    ClearPolicy.TRANSPARENT_BLACK,
                    this::bindTransition
            );
        } else {
            copy(targets.pong(), targets.raw(), "Battle transition passthrough");
        }

        copy(targets.raw(), targets.history(), "Battle transition history");
        this.historyReady = true;
    }

    private void runSingleInput(
            TextureTarget source,
            TextureTarget destination,
            RenderPipeline pipeline,
            String label,
            java.util.function.Consumer<RenderPass> bindUniform
    ) {
        this.passes.run(
                label,
                pipeline,
                List.of(Sampler.color("Sampler0", source)),
                destination,
                ClearPolicy.TRANSPARENT_BLACK,
                bindUniform
        );
    }

    private void copy(TextureTarget source, TextureTarget destination, String label) {
        this.passes.run(
                label,
                PipelineRegister.BATTLE_POST_COPY,
                List.of(Sampler.color("Sampler0", source)),
                destination,
                ClearPolicy.TRANSPARENT_BLACK,
                null
        );
    }

    private void applyForegroundOverlay(
            RenderTargets.TargetSet targets,
            ScreenEffectSnapshot effects,
            EnvironmentBackground.ForegroundTexture environmentTexture
    ) {
        var scene = targets.pong().getColorTextureView();
        var environment = environmentTexture.available()
                ? environmentTexture.texture()
                : scene;
        var blueNoise = environmentTexture.available()
                ? environmentTexture.blueNoise()
                : scene;
        this.passes.run(
                "Battle foreground overlay",
                PipelineRegister.BATTLE_SCREEN_FOREGROUND_OVERLAY,
                List.of(
                        new Sampler("SceneSampler", scene),
                        new Sampler("EnvironmentBackgroundSampler", environment),
                        new Sampler("BlueNoiseSampler", blueNoise)
                ),
                targets.output(),
                ClearPolicy.TRANSPARENT_BLACK,
                this::bindForeground
        );
    }

    private void upload(
            ScreenEffectSnapshot effects,
            EnvironmentBackground.ForegroundTexture environmentTexture,
            int width,
            int height
    ) {
        float transitionProgress = effects.transition()
                .map(ScreenEffectSnapshot.Transition::progress)
                .orElse(1.0F);
        int shortSide = Math.min(width, height);
        float shakeU = (float) (effects.shakeOffsetX() * shortSide / width);
        float shakeV = (float) (effects.shakeOffsetY() * shortSide / height);
        ScreenEffectSnapshot.Flash flash = effects.flash();
        ScreenEffectSnapshot.ForegroundOverlay foreground = effects.foregroundOverlay();

        this.transitionUniform.rotate();
        this.shakeUniform.rotate();
        this.flashUniform.rotate();
        this.foregroundUniform.rotate();
        try (GpuBuffer.MappedView transitionView = map(this.transitionUniform)) {
            Std140Builder.intoBuffer(transitionView.data()).putFloat(transitionProgress);
        }
        try (GpuBuffer.MappedView shakeView = map(this.shakeUniform)) {
            Std140Builder.intoBuffer(shakeView.data()).putVec2(shakeU, shakeV);
        }
        try (GpuBuffer.MappedView flashView = map(this.flashUniform)) {
            Std140Builder.intoBuffer(flashView.data()).putVec4(
                    flash.red(), flash.green(), flash.blue(), flash.alpha()
            );
        }
        try (GpuBuffer.MappedView foregroundView = map(this.foregroundUniform)) {
            Std140Builder.intoBuffer(foregroundView.data())
                    .putVec4(
                            foreground.red(),
                            foreground.green(),
                            foreground.blue(),
                            foreground.alpha()
                    )
                    .putVec4(
                            foreground.usesEnvironment() ? 1.0F : 0.0F,
                            environmentTexture.available() ? 1.0F : 0.0F,
                            environmentTexture.ditherStrengthLsb(),
                            0.0F
                    );
        }
    }

    private void bindTransition(RenderPass pass) {
        pass.setUniform("TransitionUniform", this.transitionUniform.currentBuffer());
    }

    private void bindShake(RenderPass pass) {
        pass.setUniform("ShakeUniform", this.shakeUniform.currentBuffer());
    }

    private void bindFlash(RenderPass pass) {
        pass.setUniform("FlashUniform", this.flashUniform.currentBuffer());
    }

    private void bindForeground(RenderPass pass) {
        pass.setUniform("ForegroundOverlayUniform", this.foregroundUniform.currentBuffer());
    }

    @Override
    public void close() {
        this.transitionUniform.close();
        this.shakeUniform.close();
        this.flashUniform.close();
        this.foregroundUniform.close();
    }

    private static MappableRingBuffer uniformBuffer(String label, int size) {
        return new MappableRingBuffer(() -> label, UNIFORM_USAGE, size);
    }

    private static GpuBuffer.MappedView map(MappableRingBuffer buffer) {
        return RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(buffer.currentBuffer(), false, true);
    }
}
