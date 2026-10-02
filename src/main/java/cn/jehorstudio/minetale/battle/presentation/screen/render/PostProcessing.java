package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig.PostQuality;
import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.ClearPolicy;
import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.Sampler;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.PostProcessingUniform;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.List;
import java.util.Map;

// 标准后处理顺序固定为 Bloom → FXAA。
public final class PostProcessing implements AutoCloseable {
    private final PostProcessingUniform uniform = new PostProcessingUniform();
    private final FullscreenPassRunner passes = new FullscreenPassRunner();
    private final BlurPyramid blurPyramid = new BlurPyramid(this.passes);
    private DebugInfo debugInfo = DebugInfo.EMPTY;
    private int frameBloomLevels;
    private int framePassCount;
    private long frameEstimatedTextureReads;
    private int frameBloomFirstLevelSamples;

    public static FramePlan plan(boolean rendered, int width, int height) {
        return plan(
                rendered,
                width,
                height,
                VisualConfig.POST_BLOOM_ENABLED(),
                VisualConfig.POST_BLOOM_QUALITY(),
                VisualConfig.POST_BLOOM_INTENSITY(),
                VisualConfig.POST_BLOOM_BLUR_RADIUS_PIXELS()
        );
    }

    static FramePlan plan(
            boolean rendered,
            int width,
            int height,
            boolean bloomEnabled,
            PostQuality bloomQuality,
            float bloomIntensity,
            float bloomRadiusPixels
    ) {
        boolean bloom = rendered
                && bloomEnabled
                && bloomQuality != PostQuality.OFF
                && bloomIntensity > 0.0F
                && bloomRadiusPixels > 0.0F;
        int bloomLevels = bloom ? BlurPyramid.levelCountForRadius(
                width, height, bloomRadiusPixels) : 0;
        bloom &= bloomLevels > 0;
        RenderTargets.PostLayout targets = bloomLevels == 0
                ? RenderTargets.PostLayout.OFF
                : new RenderTargets.PostLayout(
                        bloom,
                        bloomLevels,
                        bloom ? bloomLevels - 1 : 0
                );
        return new FramePlan(
                bloom,
                bloomLevels,
                bloom ? bloomQuality.firstLevelFilterSamples() : 0,
                targets
        );
    }

    // 必须在 Emissive pass 前上传，使 Seed 与合成阶段读取同一帧参数。
    public void beginFrame(BattleScene.Frame frame, FramePlan plan) {
        this.passes.beginFrame();
        this.uniform.upload(frame, plan);
        this.frameBloomLevels = 0;
        this.framePassCount = 0;
        this.frameEstimatedTextureReads = 0L;
        this.frameBloomFirstLevelSamples = 0;
    }

    public void bindUniform(RenderPass pass) {
        this.uniform.bind(pass);
    }

    public void process(
            RenderTargets.TargetSet targets,
            FramePlan plan,
            TextureTarget source,
            TextureTarget destination,
            String branchLabel
    ) {
        TextureTarget current = source;
        int passCount = 1;
        if (plan.bloom()) {
            TextureTarget bloom = this.blurPyramid.buildBloom(
                    requireBloomSource(targets),
                    plan.bloomLevels(),
                    targets.pyramidDown(),
                    targets.pyramidUp(),
                    this.uniform
            );
            bloomComposite(current, bloom, targets.ping(), branchLabel);
            current = targets.ping();
            passCount += plan.bloomLevels() + Math.max(0, plan.bloomLevels() - 1) + 1;
        }

        this.passes.run(
                "Battle " + branchLabel + " FXAA post",
                PipelineRegister.BATTLE_POST_FXAA,
                List.of(Sampler.color("Sampler0", current)),
                destination,
                ClearPolicy.TRANSPARENT_BLACK,
                null
        );
        this.frameBloomLevels = Math.max(this.frameBloomLevels, plan.bloomLevels());
        this.framePassCount += passCount;
        this.frameEstimatedTextureReads += estimatedBloomReads(plan);
        this.frameBloomFirstLevelSamples =
                Math.max(this.frameBloomFirstLevelSamples, plan.bloomFirstLevelSamples());
        this.debugInfo = new DebugInfo(
                this.frameBloomLevels,
                this.framePassCount,
                this.frameEstimatedTextureReads,
                this.frameBloomFirstLevelSamples,
                this.passes.submissionNanos()
        );
    }

    public void endFrame() {
        this.uniform.rotate();
    }

    public DebugInfo debugInfo() {
        return this.debugInfo;
    }

    private void bloomComposite(
            TextureTarget scene,
            TextureTarget bloom,
            TextureTarget destination,
            String branchLabel
    ) {
        this.passes.run(
                "Battle " + branchLabel + " Bloom composite",
                PipelineRegister.BATTLE_POST_BLOOM_COMPOSITE,
                List.of(
                        Sampler.color("SceneSampler", scene),
                        Sampler.color("BloomSampler", bloom)
                ),
                destination,
                ClearPolicy.TRANSPARENT_BLACK,
                this.uniform::bind
        );
    }

    private static TextureTarget requireBloomSource(RenderTargets.TargetSet targets) {
        if (targets.bloomSource() == null) {
            throw new IllegalStateException("Bloom 已启用但 Emissive Seed 目标未分配");
        }
        return targets.bloomSource();
    }

    private static long estimatedBloomReads(FramePlan plan) {
        if (!plan.bloom()) return 0L;
        long down = plan.bloomFirstLevelSamples() + 4L * Math.max(0, plan.bloomLevels() - 1);
        long up = 5L * Math.max(0, plan.bloomLevels() - 1);
        return down + up + 2L;
    }

    @Override
    public void close() {
        this.uniform.close();
    }

    public record FramePlan(
            boolean bloom,
            int bloomLevels,
            int bloomFirstLevelSamples,
            RenderTargets.PostLayout targets
    ) {
    }

    // 只统计固定 Pass 与采样规模；未测量的 GPU 时间不得由 CPU 提交时间代替。
    public record DebugInfo(
            int bloomLevels,
            int fullscreenPasses,
            long estimatedBloomTextureReads,
            int bloomFirstLevelSamples,
            Map<String, Long> cpuSubmissionNanosByPass
    ) {
        private static final DebugInfo EMPTY = new DebugInfo(0, 0, 0, 0, Map.of());

        public DebugInfo {
            cpuSubmissionNanosByPass = Map.copyOf(cpuSubmissionNanosByPass);
        }
    }
}
