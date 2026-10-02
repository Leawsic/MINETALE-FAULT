package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.presentation.screen.render.volume.VolumetricShadows.HierarchyLayout;
import cn.jehorstudio.minetale.battle.presentation.screen.render.volume.VolumetricShadows.PointTargetLayout;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.FilterMode;

import java.util.ArrayList;
import java.util.List;

// 统一持有场景、后处理、Crossfade、阴影与 Min-Max 层次的物理目标。
public final class RenderTargets implements AutoCloseable {
    private TextureTarget raw;
    private TextureTarget crossfadeRaw;
    private TextureTarget crossfadeUnmodified;
    private TextureTarget crossfadeRendered;
    private TextureTarget environmentBackground;
    private TextureTarget bloomSource;
    private final TextureTarget[] pyramidDown = new TextureTarget[BlurPyramid.MAX_LEVELS];
    private final TextureTarget[] pyramidUp = new TextureTarget[BlurPyramid.MAX_LEVELS - 1];
    private TextureTarget ping;
    private TextureTarget pong;
    private TextureTarget output;
    private TextureTarget history;
    private TextureTarget freeze;
    private TextureTarget shadow;
    private final TextureTarget[] shadowMinMaxLevels = new TextureTarget[HierarchyLayout.MAX_LEVELS];
    private TextureTarget soulPointShadowAtlas;
    private final TextureTarget[] soulPointShadowMinMaxLevels =
            new TextureTarget[PointTargetLayout.MAX_LEVELS];
    private int width = -1;
    private int height = -1;
    private int shadowMapSize = -1;
    private HierarchyLayout shadowHierarchyLayout;
    private PointTargetLayout soulPointShadowLayout;
    private PostLayout postLayout = PostLayout.OFF;

    public TargetSet ensure(
            int width,
            int height,
            int requestedShadowMapSize,
            HierarchyLayout requestedShadowHierarchy,
            PointTargetLayout requestedSoulPointShadowLayout,
            PostLayout requestedPostLayout,
            boolean environmentBackgroundRequired,
            boolean renderModeCrossfadeRequired
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be positive, got " + width + "x" + height);
        }
        if (this.raw == null || this.width != width || this.height != height) {
            destroySceneTargets();
            this.raw = new TextureTarget("MineTale Battle raw scene", width, height, true);
            this.ping = new TextureTarget("MineTale Battle post ping", width, height, false);
            this.pong = new TextureTarget("MineTale Battle post pong", width, height, false);
            this.output = new TextureTarget("MineTale Battle final output", width, height, false);
            this.history = new TextureTarget("MineTale Battle transition history", width, height, false);
            this.freeze = new TextureTarget("MineTale Battle transition freeze", width, height, false);
            this.raw.setFilterMode(FilterMode.LINEAR);
            this.ping.setFilterMode(FilterMode.LINEAR);
            this.pong.setFilterMode(FilterMode.LINEAR);
            this.output.setFilterMode(FilterMode.LINEAR);
            this.history.setFilterMode(FilterMode.LINEAR);
            this.freeze.setFilterMode(FilterMode.LINEAR);
            this.width = width;
            this.height = height;
        }
        ensureRenderModeCrossfadeTargets(renderModeCrossfadeRequired);
        ensureEnvironmentBackgroundTarget(environmentBackgroundRequired);
        ensurePostTargets(requestedPostLayout);
        if (this.shadow == null || this.shadowMapSize != requestedShadowMapSize) {
            destroy(this.shadow);
            destroyMinMaxTargets();
            this.shadow = new TextureTarget(
                    "MineTale Battle global-light shadow map",
                    requestedShadowMapSize,
                    requestedShadowMapSize,
                    true
            );
            this.shadow.setFilterMode(FilterMode.NEAREST);
            this.shadowMapSize = requestedShadowMapSize;
        }
        ensureMinMaxTargets(requestedShadowHierarchy);
        ensureSoulPointShadowTargets(requestedSoulPointShadowLayout);
        List<TextureTarget> levels = new ArrayList<>();
        if (this.shadowHierarchyLayout != null) {
            for (int level = 0; level < this.shadowHierarchyLayout.levelCount(); level++) {
                levels.add(this.shadowMinMaxLevels[level]);
            }
        }
        List<TextureTarget> soulLevels = new ArrayList<>();
        if (this.soulPointShadowLayout != null) {
            for (int level = 0; level < this.soulPointShadowLayout.levelCount(); level++) {
                soulLevels.add(this.soulPointShadowMinMaxLevels[level]);
            }
        }
        List<TextureTarget> pyramidDownTargets = new ArrayList<>();
        for (int level = 0; level < this.postLayout.pyramidLevels(); level++) {
            pyramidDownTargets.add(this.pyramidDown[level]);
        }
        List<TextureTarget> pyramidUpTargets = new ArrayList<>();
        for (int level = 0; level < this.postLayout.upsampleLevels(); level++) {
            pyramidUpTargets.add(this.pyramidUp[level]);
        }
        return new TargetSet(
                this.raw, this.crossfadeRaw, this.crossfadeUnmodified, this.crossfadeRendered,
                this.environmentBackground, this.bloomSource,
                List.copyOf(pyramidDownTargets), List.copyOf(pyramidUpTargets),
                this.ping, this.pong, this.output, this.history, this.freeze,
                this.shadow, List.copyOf(levels), this.shadowHierarchyLayout,
                this.soulPointShadowAtlas, List.copyOf(soulLevels), this.soulPointShadowLayout);
    }

    private void ensureRenderModeCrossfadeTargets(boolean required) {
        if (!required) {
            destroy(this.crossfadeRaw);
            destroy(this.crossfadeUnmodified);
            destroy(this.crossfadeRendered);
            this.crossfadeRaw = null;
            this.crossfadeUnmodified = null;
            this.crossfadeRendered = null;
            return;
        }
        if (this.crossfadeRaw != null
                && this.crossfadeRaw.width == this.width
                && this.crossfadeRaw.height == this.height) {
            return;
        }
        destroy(this.crossfadeRaw);
        destroy(this.crossfadeUnmodified);
        destroy(this.crossfadeRendered);
        this.crossfadeRaw = new TextureTarget(
                "MineTale Battle render-mode secondary raw scene", this.width, this.height, true);
        this.crossfadeUnmodified = target(
                "MineTale Battle completed Unmodified frame", this.width, this.height);
        this.crossfadeRendered = target(
                "MineTale Battle completed Rendered frame", this.width, this.height);
        this.crossfadeRaw.setFilterMode(FilterMode.LINEAR);
    }

    private void ensureEnvironmentBackgroundTarget(boolean required) {
        if (!required) {
            destroy(this.environmentBackground);
            this.environmentBackground = null;
            return;
        }
        int backgroundWidth = Math.max(1, (this.width + 3) / 4);
        int backgroundHeight = Math.max(1, (this.height + 3) / 4);
        if (this.environmentBackground != null
                && this.environmentBackground.width == backgroundWidth
                && this.environmentBackground.height == backgroundHeight) {
            return;
        }
        destroy(this.environmentBackground);
        this.environmentBackground = target(
                "MineTale Battle sparse-SG environment background",
                backgroundWidth,
                backgroundHeight
        );
    }

    private void ensurePostTargets(PostLayout requested) {
        requested = requested == null ? PostLayout.OFF : requested;
        if (requested.bloomSource() && this.bloomSource == null) {
            this.bloomSource = target(
                    "MineTale Battle emissive Bloom source", this.width, this.height);
        } else if (!requested.bloomSource() && this.bloomSource != null) {
            destroy(this.bloomSource);
            this.bloomSource = null;
        }
        if (requested.equals(this.postLayout) && postTargetsReady(requested)) {
            return;
        }
        destroyPyramidTargets();
        for (int level = 0; level < requested.pyramidLevels(); level++) {
            BlurPyramid.LevelDimensions dimensions =
                    BlurPyramid.levelDimensions(this.width, this.height, level);
            this.pyramidDown[level] = target(
                    "MineTale Battle shared blur pyramid down level " + level,
                    dimensions.width(),
                    dimensions.height()
            );
            if (level < requested.upsampleLevels()) {
                this.pyramidUp[level] = target(
                        "MineTale Battle Bloom pyramid up level " + level,
                        dimensions.width(),
                        dimensions.height()
                );
            }
        }
        this.postLayout = requested;
    }

    private boolean postTargetsReady(PostLayout requested) {
        if (requested.pyramidLevels() > 0 && this.pyramidDown[requested.pyramidLevels() - 1] == null) {
            return false;
        }
        return requested.upsampleLevels() == 0
                || this.pyramidUp[requested.upsampleLevels() - 1] != null;
    }

    private static TextureTarget target(String name, int width, int height) {
        TextureTarget target = new TextureTarget(name, width, height, false);
        target.setFilterMode(FilterMode.LINEAR);
        return target;
    }

    // Soul atlas 与窗口无关；仅首次启用或图集布局变化时重建，临时关闭不释放。
    private void ensureSoulPointShadowTargets(PointTargetLayout requested) {
        if (requested == null) return;
        if (requested.equals(this.soulPointShadowLayout) && this.soulPointShadowAtlas != null) return;
        destroySoulPointShadowTargets();
        this.soulPointShadowLayout = requested;
        this.soulPointShadowAtlas = new TextureTarget(
                "MineTale Battle Soul point-shadow atlas",
                requested.atlasWidth(),
                requested.atlasHeight(),
                true
        );
        this.soulPointShadowAtlas.setFilterMode(FilterMode.NEAREST);
        for (int level = 0; level < requested.levelCount(); level++) {
            TextureTarget target = new TextureTarget(
                    "MineTale Battle Soul point-shadow Min-Max level " + level,
                    requested.levelWidth(level),
                    requested.levelHeight(level),
                    false
            );
            target.setFilterMode(FilterMode.NEAREST);
            this.soulPointShadowMinMaxLevels[level] = target;
        }
    }

    private void ensureMinMaxTargets(HierarchyLayout requested) {
        if (requested == null || requested.levelCount() == 0) {
            destroyMinMaxTargets();
            return;
        }
        if (requested.isCompatibleWith(this.shadowHierarchyLayout)) {
            this.shadowHierarchyLayout = requested;
            return;
        }
        destroyMinMaxTargets();
        this.shadowHierarchyLayout = requested;
        for (int level = 0; level < requested.levelCount(); level++) {
            TextureTarget target = new TextureTarget(
                    "MineTale Battle shadowMinMax" + (level % 2 == 0 ? "A" : "B") + " level " + level,
                    requested.levelWidth(level),
                    requested.levelHeight(level),
                    false
            );
            target.setFilterMode(FilterMode.NEAREST);
            this.shadowMinMaxLevels[level] = target;
        }
    }

    @Override
    public void close() {
        destroySceneTargets();
        destroy(this.shadow);
        this.shadow = null;
        this.shadowMapSize = -1;
        destroyMinMaxTargets();
        destroySoulPointShadowTargets();
    }

    private void destroySceneTargets() {
        destroy(this.raw);
        destroy(this.crossfadeRaw);
        destroy(this.crossfadeUnmodified);
        destroy(this.crossfadeRendered);
        destroy(this.environmentBackground);
        destroy(this.bloomSource);
        destroyPyramidTargets();
        destroy(this.ping);
        destroy(this.pong);
        destroy(this.output);
        destroy(this.history);
        destroy(this.freeze);
        this.raw = null;
        this.crossfadeRaw = null;
        this.crossfadeUnmodified = null;
        this.crossfadeRendered = null;
        this.environmentBackground = null;
        this.bloomSource = null;
        this.ping = null;
        this.pong = null;
        this.output = null;
        this.history = null;
        this.freeze = null;
        this.width = -1;
        this.height = -1;
    }

    private void destroyPyramidTargets() {
        for (int level = 0; level < this.pyramidDown.length; level++) {
            destroy(this.pyramidDown[level]);
            this.pyramidDown[level] = null;
            if (level < this.pyramidUp.length) {
                destroy(this.pyramidUp[level]);
                this.pyramidUp[level] = null;
            }
        }
        this.postLayout = PostLayout.OFF;
    }

    private void destroyMinMaxTargets() {
        for (int index = 0; index < HierarchyLayout.MAX_LEVELS; index++) {
            destroy(this.shadowMinMaxLevels[index]);
            this.shadowMinMaxLevels[index] = null;
        }
        this.shadowHierarchyLayout = null;
    }

    private void destroySoulPointShadowTargets() {
        destroy(this.soulPointShadowAtlas);
        this.soulPointShadowAtlas = null;
        for (int index = 0; index < PointTargetLayout.MAX_LEVELS; index++) {
            destroy(this.soulPointShadowMinMaxLevels[index]);
            this.soulPointShadowMinMaxLevels[index] = null;
        }
        this.soulPointShadowLayout = null;
    }

    private static void destroy(TextureTarget target) {
        if (target != null) target.destroyBuffers();
    }

    public record TargetSet(
            TextureTarget raw,
            TextureTarget crossfadeRaw,
            TextureTarget crossfadeUnmodified,
            TextureTarget crossfadeRendered,
            TextureTarget environmentBackground,
            TextureTarget bloomSource,
            List<TextureTarget> pyramidDown,
            List<TextureTarget> pyramidUp,
            TextureTarget ping,
            TextureTarget pong,
            TextureTarget output,
            TextureTarget history,
            TextureTarget freeze,
            TextureTarget shadow,
            List<TextureTarget> shadowMinMaxLevels,
            HierarchyLayout shadowHierarchyLayout,
            TextureTarget soulPointShadowAtlas,
            List<TextureTarget> soulPointShadowMinMaxLevels,
            PointTargetLayout soulPointShadowLayout
    ) {
    }

    public record PostLayout(
            boolean bloomSource,
            int pyramidLevels,
            int upsampleLevels
    ) {
        public static final PostLayout OFF = new PostLayout(false, 0, 0);

        public PostLayout {
            if (pyramidLevels < 0 || pyramidLevels > BlurPyramid.MAX_LEVELS) {
                throw new IllegalArgumentException(
                        "后处理金字塔层数必须位于 [0, " + BlurPyramid.MAX_LEVELS + "]");
            }
            if (upsampleLevels < 0 || upsampleLevels > Math.max(0, pyramidLevels - 1)) {
                throw new IllegalArgumentException("Bloom 升采样目标数必须小于金字塔层数");
            }
            if (!bloomSource && upsampleLevels != 0) {
                throw new IllegalArgumentException("未启用 Bloom 时不得分配升采样目标");
            }
        }
    }
}
