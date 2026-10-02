package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureSnapshot;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentField;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TimerQuery;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

// 将冻结 Environment Field 解析为四分之一分辨率背景，再与 Activation Frame 合成 raw scene 底色。
// 背景参数独立于材质环境光。
public final class EnvironmentBackground implements AutoCloseable {
    private static final ResourceLocation BLUE_NOISE = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID, "textures/noise/blue_noise_64.png");
    private static final int MAX_PENDING_GPU_PROFILES = 12;

    private final Uniforms uniforms = new Uniforms();
    private final ArrayDeque<PendingGpuProfile> pendingGpuProfiles = new ArrayDeque<>();
    private long evaluateCpuSubmissionNanos;
    private long compositeCpuSubmissionNanos;
    private long evaluateGpuNanos = -1L;
    private long compositeGpuNanos = -1L;

    FramePlan plan(EnvironmentCaptureSnapshot environment, float opacity) {
        Objects.requireNonNull(environment, "environment");
        requireUnit(opacity, "opacity");
        pollGpuProfiles();

        Settings settings = Settings.capture(opacity);
        boolean enabled = environment.ready();
        if (!enabled) {
            this.evaluateCpuSubmissionNanos = 0L;
            this.compositeCpuSubmissionNanos = 0L;
            return FramePlan.disabled();
        }

        EnvironmentField field = environment.field().orElseThrow();
        EnvironmentCaptureSnapshot.ActivationFrame activationFrame =
                environment.activationFrame().orElseThrow();
        AbstractTexture blueNoise = Minecraft.getInstance().getTextureManager().getTexture(BLUE_NOISE);
        blueNoise.setFilter(true, false);
        blueNoise.setClamp(false);
        return new FramePlan(field, activationFrame, blueNoise.getTextureView(), settings);
    }

    void upload(BattleScene.Frame frame, FramePlan plan) {
        requireEnabled(plan);
        this.uniforms.upload(frame, plan);
    }

    void evaluate(TextureTarget target, FramePlan plan) {
        requireEnabled(plan);
        Objects.requireNonNull(target, "target");
        long started = System.nanoTime();
        boolean gpuProfile = beginGpuProfile();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle Environment Background Evaluate (1/4)",
                target.getColorTextureView(), OptionalInt.of(0xFF000000),
                null, OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, target.width, target.height);
            pass.setPipeline(PipelineRegister.BATTLE_ENVIRONMENT_BACKGROUND_EVALUATE);
            pass.bindSampler("EnvironmentFieldSampler", plan.field().textureView());
            this.uniforms.bind(pass);
            pass.draw(0, 3);
        } finally {
            endGpuProfile(ProfileKind.EVALUATE, gpuProfile);
            this.evaluateCpuSubmissionNanos = System.nanoTime() - started;
        }
    }

    // 必须在 raw scene 清屏后、任何 Battle 几何之前合成。
    void composite(RenderPass pass, TextureTarget evaluatedBackground, FramePlan plan) {
        requireEnabled(plan);
        Objects.requireNonNull(pass, "pass");
        Objects.requireNonNull(evaluatedBackground, "evaluatedBackground");
        long started = System.nanoTime();
        boolean gpuProfile = beginGpuProfile();
        try {
            pass.setPipeline(PipelineRegister.BATTLE_ENVIRONMENT_BACKGROUND_COMPOSITE);
            pass.bindSampler(
                    "EnvironmentBackgroundSampler",
                    evaluatedBackground.getColorTextureView()
            );
            pass.bindSampler("ActivationFrameSampler", plan.activationFrame().textureView());
            pass.bindSampler("BlueNoiseSampler", plan.blueNoise());
            this.uniforms.bind(pass);
            pass.draw(0, 3);
        } finally {
            endGpuProfile(ProfileKind.COMPOSITE, gpuProfile);
            this.compositeCpuSubmissionNanos = System.nanoTime() - started;
        }
    }

    void rotate(FramePlan plan) {
        if (plan.enabled()) {
            this.uniforms.rotate();
        }
    }

    ForegroundTexture foregroundTexture(TextureTarget evaluatedBackground, FramePlan plan) {
        if (!plan.enabled()) {
            return ForegroundTexture.unavailable();
        }
        Objects.requireNonNull(evaluatedBackground, "evaluatedBackground");
        return new ForegroundTexture(
                evaluatedBackground.getColorTextureView(),
                plan.blueNoise(),
                plan.settings().ditherStrengthLsb()
        );
    }

    public DebugInfo debugInfo() {
        return new DebugInfo(
                this.evaluateCpuSubmissionNanos,
                this.compositeCpuSubmissionNanos,
                this.evaluateGpuNanos,
                this.compositeGpuNanos,
                this.pendingGpuProfiles.size()
        );
    }

    @Override
    public void close() {
        for (PendingGpuProfile pending : this.pendingGpuProfiles) {
            pending.profile().cancel();
        }
        this.pendingGpuProfiles.clear();
        this.uniforms.close();
    }

    private boolean beginGpuProfile() {
        TimerQuery timer = TimerQuery.getInstance();
        if (timer.isRecording() || this.pendingGpuProfiles.size() >= MAX_PENDING_GPU_PROFILES) {
            return false;
        }
        timer.beginProfile();
        return true;
    }

    private void endGpuProfile(ProfileKind kind, boolean started) {
        if (!started) {
            return;
        }
        this.pendingGpuProfiles.addLast(new PendingGpuProfile(
                kind,
                TimerQuery.getInstance().endProfile()
        ));
    }

    private void pollGpuProfiles() {
        Iterator<PendingGpuProfile> iterator = this.pendingGpuProfiles.iterator();
        while (iterator.hasNext()) {
            PendingGpuProfile pending = iterator.next();
            if (!pending.profile().isDone()) {
                continue;
            }
            long nanos = pending.profile().get();
            if (pending.kind() == ProfileKind.EVALUATE) {
                this.evaluateGpuNanos = nanos;
            } else {
                this.compositeGpuNanos = nanos;
            }
            iterator.remove();
        }
    }

    private static void requireEnabled(FramePlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.enabled()) {
            throw new IllegalArgumentException("未启用的环境背景计划不得提交 GPU Pass");
        }
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be between 0 and 1.");
        }
    }

    private record Settings(
            float opacity,
            float intensity,
            float widthScale,
            float baseLevel,
            float colorStrength,
            float targetLuma,
            float adaptationStrength,
            float ditherStrengthLsb
    ) {
        static Settings capture(float opacity) {
            return new Settings(
                    VisualConfig.ENVIRONMENT_BACKGROUND_ENABLED() ? opacity : 0.0F,
                    VisualConfig.ENVIRONMENT_BACKGROUND_INTENSITY(),
                    VisualConfig.ENVIRONMENT_BACKGROUND_WIDTH_SCALE(),
                    VisualConfig.ENVIRONMENT_BACKGROUND_BASE_LEVEL(),
                    VisualConfig.ENVIRONMENT_BACKGROUND_COLOR_STRENGTH(),
                    VisualConfig.ENVIRONMENT_BACKGROUND_TARGET_LUMA(),
                    VisualConfig.ENVIRONMENT_BACKGROUND_ADAPTATION_STRENGTH(),
                    VisualConfig.ENVIRONMENT_BACKGROUND_DITHER_STRENGTH_LSB()
            );
        }
    }

    record FramePlan(
            EnvironmentField field,
            EnvironmentCaptureSnapshot.ActivationFrame activationFrame,
            GpuTextureView blueNoise,
            Settings settings
    ) {
        FramePlan {
            if (field != null) {
                Objects.requireNonNull(field, "field");
                Objects.requireNonNull(activationFrame, "activationFrame");
                Objects.requireNonNull(blueNoise, "blueNoise");
                Objects.requireNonNull(settings, "settings");
            } else if (activationFrame != null || blueNoise != null || settings != null) {
                throw new IllegalArgumentException("关闭的环境背景计划不得保留 GPU 输入");
            }
        }

        boolean enabled() {
            return this.field != null;
        }

        static FramePlan disabled() {
            return new FramePlan(null, null, null, null);
        }
    }

    // 前景覆盖借用已解析背景。
    record ForegroundTexture(
            GpuTextureView texture,
            GpuTextureView blueNoise,
            float ditherStrengthLsb
    ) {
        ForegroundTexture {
            if (texture != null) {
                Objects.requireNonNull(blueNoise, "blueNoise");
            } else if (blueNoise != null || ditherStrengthLsb != 0.0F) {
                throw new IllegalArgumentException("不可用的环境前景不得保留 GPU 输入");
            }
        }

        boolean available() {
            return this.texture != null;
        }

        static ForegroundTexture unavailable() {
            return new ForegroundTexture(null, null, 0.0F);
        }
    }

    // GPU 时间 -1 表示查询尚未返回，或计时器正由全帧 profiler 占用。
    public record DebugInfo(
            long evaluateCpuSubmissionNanos,
            long compositeCpuSubmissionNanos,
            long evaluateGpuNanos,
            long compositeGpuNanos,
            int pendingGpuProfiles
    ) {
    }

    private enum ProfileKind {
        EVALUATE,
        COMPOSITE
    }

    private record PendingGpuProfile(ProfileKind kind, TimerQuery.FrameProfile profile) {
    }

    // Evaluate 与 Composite 共用此布局。
    private static final class Uniforms implements AutoCloseable {
        private static final int BLOCK_SIZE = new Std140SizeCalculator()
                .putVec4().putVec4().putVec4()
                .putVec4().putVec4().putVec4()
                .putVec4().putVec4().putVec4()
                .get();

        private final MappableRingBuffer buffer = new MappableRingBuffer(
                () -> "Battle sparse-SG environment background uniforms", 130, BLOCK_SIZE);

        private void upload(BattleScene.Frame frame, FramePlan plan) {
            Vector3f direction = frame.camera().viewDirection();
            Vector3f right = frame.camera().right();
            Vector3f up = frame.camera().up();
            EnvironmentField field = plan.field();
            Settings settings = plan.settings();
            try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.buffer.currentBuffer(), false, true)) {
                ByteBuffer data = mapped.data();
                putVec4(data, 0,
                        direction.x(), direction.y(), direction.z(), frame.viewport().aspectRatio());
                putVec4(data, 16, right.x(), right.y(), right.z(), 0.0F);
                putVec4(data, 32, up.x(), up.y(), up.z(), 0.0F);
                putBasis(data, 48, field.right());
                putBasis(data, 64, field.up());
                putBasis(data, 80, field.forward());
                putVec4(data, 96,
                        settings.intensity(),
                        settings.widthScale(),
                        settings.baseLevel(),
                        settings.colorStrength());
                putVec4(data, 112,
                        settings.targetLuma(),
                        settings.adaptationStrength(),
                        settings.opacity(),
                        0.0F);
                putVec4(data, 128, settings.ditherStrengthLsb(), 0.0F, 0.0F, 0.0F);
            }
        }

        private void bind(RenderPass pass) {
            pass.setUniform("EnvironmentBackgroundUniform", this.buffer.currentBuffer());
        }

        private void rotate() {
            this.buffer.rotate();
        }

        @Override
        public void close() {
            this.buffer.close();
        }

        private static void putBasis(ByteBuffer data, int offset, Vec3 vector) {
            putVec4(data, offset, (float) vector.x, (float) vector.y, (float) vector.z, 0.0F);
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
}
