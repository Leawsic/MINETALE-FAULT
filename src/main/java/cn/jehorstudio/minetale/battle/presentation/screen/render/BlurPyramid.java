package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.ClearPolicy;
import cn.jehorstudio.minetale.battle.presentation.screen.render.FullscreenPassRunner.Sampler;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.PostProcessingUniform;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.util.List;
import java.util.Objects;

// 在 RenderTargets 持有的物理目标上构建 Bloom 模糊金字塔。
public final class BlurPyramid {
    public static final int MAX_LEVELS = 6;

    private final FullscreenPassRunner passes;

    public BlurPyramid(FullscreenPassRunner passes) {
        this.passes = Objects.requireNonNull(passes, "passes");
    }

    public TextureTarget buildBloom(
            TextureTarget source,
            int levelCount,
            List<TextureTarget> down,
            List<TextureTarget> up,
            PostProcessingUniform uniform
    ) {
        requireLevels(levelCount, down);
        if (up.size() < Math.max(0, levelCount - 1)) {
            throw new IllegalArgumentException("Bloom 升采样目标不足");
        }
        buildDownsampleChain(
                source, levelCount, down,
                PipelineRegister.BATTLE_POST_BLOOM_DOWNSAMPLE,
                "Battle Bloom downsample", uniform
        );
        TextureTarget coarse = down.get(levelCount - 1);
        for (int level = levelCount - 2; level >= 0; level--) {
            TextureTarget destination = up.get(level);
            this.passes.run(
                    "Battle Bloom upsample level " + level,
                    PipelineRegister.BATTLE_POST_BLOOM_UPSAMPLE,
                    List.of(
                            Sampler.color("CoarseSampler", coarse),
                            Sampler.color("FineSampler", down.get(level))
                    ),
                    destination,
                    ClearPolicy.TRANSPARENT_BLACK,
                    uniform::bind
            );
            coarse = destination;
        }
        return coarse;
    }

    private void buildDownsampleChain(
            TextureTarget source,
            int levelCount,
            List<TextureTarget> down,
            RenderPipeline pipeline,
            String label,
            PostProcessingUniform uniform
    ) {
        TextureTarget previous = source;
        for (int level = 0; level < levelCount; level++) {
            TextureTarget destination = down.get(level);
            this.passes.run(
                    label + " level " + level,
                    pipeline,
                    List.of(Sampler.color("Sampler0", previous)),
                    destination,
                    ClearPolicy.TRANSPARENT_BLACK,
                    uniform::bind
            );
            previous = destination;
        }
    }

    // 每层约覆盖两倍空间尺度；在达到半径需求或下一层退化为 1×1 前停止。
    public static int levelCountForRadius(int width, int height, float radiusPixels) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Blur Pyramid 源尺寸必须为正");
        }
        if (!(radiusPixels > 0.0F)) {
            return 0;
        }
        int requestedLevels = Math.clamp(
                (int) Math.ceil(Math.log(Math.max(2.0F, radiusPixels)) / Math.log(2.0D)),
                1,
                MAX_LEVELS
        );
        int levels = 0;
        int levelWidth = width;
        int levelHeight = height;
        while (levels < requestedLevels) {
            int nextWidth = Math.max(1, (levelWidth + 1) / 2);
            int nextHeight = Math.max(1, (levelHeight + 1) / 2);
            if (nextWidth == 1 && nextHeight == 1) {
                break;
            }
            levelWidth = nextWidth;
            levelHeight = nextHeight;
            levels++;
        }
        return levels;
    }

    // 奇数尺寸逐层向上取半，避免丢失边缘像素。
    public static LevelDimensions levelDimensions(int width, int height, int levelIndex) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Blur Pyramid 源尺寸必须为正");
        }
        if (levelIndex < 0 || levelIndex >= MAX_LEVELS) {
            throw new IllegalArgumentException(
                    "Blur Pyramid 层索引必须位于 [0, " + (MAX_LEVELS - 1) + "]");
        }
        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level <= levelIndex; level++) {
            levelWidth = Math.max(1, (levelWidth + 1) / 2);
            levelHeight = Math.max(1, (levelHeight + 1) / 2);
        }
        return new LevelDimensions(levelWidth, levelHeight);
    }

    public record LevelDimensions(int width, int height) {
    }

    private static void requireLevels(int levelCount, List<TextureTarget> levels) {
        if (levelCount <= 0 || levelCount > MAX_LEVELS) {
            throw new IllegalArgumentException("Blur Pyramid 层数必须位于 [1, " + MAX_LEVELS + "]");
        }
        if (levels.size() < levelCount) {
            throw new IllegalArgumentException("Blur Pyramid 物理目标不足");
        }
    }
}
