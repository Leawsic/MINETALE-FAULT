package cn.jehorstudio.minetale.battle.presentation.screen.render.environment;

import cn.jehorstudio.minetale.battle.presentation.screen.render.PipelineRegister;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

// 独占 Host 冻结颜色、Atlas、Raw Lobe、Field 及其 Pass；外部驱动捕获生命周期。
// Host 目标先保护准备帧，激活时再覆盖为持久 Activation Frame。
final class EnvironmentCaptureGpu implements AutoCloseable {
    static final int ENVIRONMENT_LOBE_COUNT = 12;
    static final int RAW_LOBE_TEXTURE_WIDTH = ENVIRONMENT_LOBE_COUNT;
    static final int RAW_LOBE_TEXTURE_HEIGHT = 1;
    static final int FIELD_TEXTURE_WIDTH = 32;
    static final int FIELD_TEXTURE_HEIGHT = 1;

    private TextureTarget captureAtlas;
    private TextureTarget rawLobeTarget;
    private TextureTarget fieldTarget;
    private TextureTarget hostFrameTarget;

    private EnvironmentCaptureGpu(
            TextureTarget captureAtlas,
            TextureTarget rawLobeTarget,
            TextureTarget fieldTarget,
            TextureTarget hostFrameTarget
    ) {
        this.captureAtlas = captureAtlas;
        this.rawLobeTarget = rawLobeTarget;
        this.fieldTarget = fieldTarget;
        this.hostFrameTarget = hostFrameTarget;
    }

    static EnvironmentCaptureGpu create(int sourceWidth, int sourceHeight) {
        TextureTarget atlas = null;
        TextureTarget raw = null;
        TextureTarget field = null;
        TextureTarget hostFrame = null;
        try {
            atlas = new TextureTarget(
                    "MineTale Battle environment capture atlas",
                    CaptureFace.ATLAS_WIDTH,
                    CaptureFace.ATLAS_HEIGHT,
                    false
            );
            raw = new TextureTarget(
                    "MineTale Battle environment raw lobes",
                    RAW_LOBE_TEXTURE_WIDTH,
                    RAW_LOBE_TEXTURE_HEIGHT,
                    false
            );
            field = new TextureTarget(
                    "MineTale Battle environment field",
                    FIELD_TEXTURE_WIDTH,
                    FIELD_TEXTURE_HEIGHT,
                    false
            );
            hostFrame = new TextureTarget(
                    "MineTale Battle preparation host frame",
                    sourceWidth,
                    sourceHeight,
                    false
            );
            atlas.setFilterMode(FilterMode.LINEAR);
            raw.setFilterMode(FilterMode.NEAREST);
            field.setFilterMode(FilterMode.NEAREST);
            hostFrame.setFilterMode(FilterMode.LINEAR);
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorTexture(atlas.getColorTexture(), 0xFF000000);
            return new EnvironmentCaptureGpu(atlas, raw, field, hostFrame);
        } catch (RuntimeException exception) {
            destroy(atlas);
            destroy(raw);
            destroy(field);
            destroy(hostFrame);
            throw exception;
        }
    }

    void captureHostFrame(RenderTarget source) {
        copyColor(source, hostFrameTarget());
    }

    void restoreHostFrame(RenderTarget destination) {
        copyColor(hostFrameTarget(), destination);
    }

    void resizeHostFrame(int width, int height) {
        TextureTarget current = hostFrameTarget();
        if (current.width == width && current.height == height) {
            return;
        }
        TextureTarget replacement = new TextureTarget(
                "MineTale Battle activation frame",
                width,
                height,
                false
        );
        replacement.setFilterMode(FilterMode.LINEAR);
        this.hostFrameTarget = replacement;
        destroy(current);
    }

    EnvironmentCaptureSnapshot.ActivationFrame activationFrame() {
        TextureTarget target = hostFrameTarget();
        return new EnvironmentCaptureSnapshot.ActivationFrame(
                target.getColorTextureView(),
                target.width,
                target.height
        );
    }

    void capture(CaptureFace face, RenderTarget source) {
        TextureTarget atlas = captureAtlas();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle environment capture " + face.debugName,
                atlas.getColorTextureView(), OptionalInt.empty(),
                null, OptionalDouble.empty()
        )) {
            pass.setViewport(
                    face.viewportX(),
                    face.viewportY(),
                    CaptureFace.FACE_SIZE,
                    CaptureFace.FACE_SIZE
            );
            pass.setPipeline(PipelineRegister.BATTLE_ENVIRONMENT_CAPTURE);
            pass.bindSampler("SourceSampler", source.getColorTextureView());
            pass.draw(0, 3);
        }
    }

    void extractField() {
        TextureTarget raw = rawLobeTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle environment raw lobe extract",
                raw.getColorTextureView(), OptionalInt.of(0x00000000),
                null, OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, raw.width, raw.height);
            pass.setPipeline(PipelineRegister.BATTLE_ENVIRONMENT_LOBE_EXTRACT);
            pass.bindSampler("EnvironmentAtlas", captureAtlas().getColorTextureView());
            pass.draw(0, 3);
        }

        TextureTarget field = fieldTarget();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle environment field resolve",
                field.getColorTextureView(), OptionalInt.of(0x00000000),
                null, OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, field.width, field.height);
            pass.setPipeline(PipelineRegister.BATTLE_ENVIRONMENT_FIELD_RESOLVE);
            pass.bindSampler("EnvironmentAtlas", captureAtlas().getColorTextureView());
            pass.bindSampler("RawLobeSampler", raw.getColorTextureView());
            pass.draw(0, 3);
        }
    }

    EnvironmentField field(Vec3 right, Vec3 up, Vec3 forward) {
        TextureTarget target = fieldTarget();
        return new EnvironmentField(
                target.getColorTextureView(),
                target.width,
                target.height,
                right,
                up,
                forward
        );
    }

    EnvironmentCaptureSnapshot.DebugTexture debugTexture(DebugMode mode) {
        TextureTarget target = switch (mode) {
            case RAW_ATLAS -> captureAtlas();
            case RAW_LOBES -> rawLobeTarget();
            case RESOLVED_FIELD -> fieldTarget();
            case NORMAL -> throw new IllegalArgumentException("NORMAL 模式没有调试纹理");
        };
        return new EnvironmentCaptureSnapshot.DebugTexture(
                target.getColorTextureView(),
                target.width,
                target.height
        );
    }

    void releaseTransientResources(DebugMode debugMode) {
        if (debugMode != DebugMode.RAW_LOBES) {
            TextureTarget raw = this.rawLobeTarget;
            this.rawLobeTarget = null;
            destroy(raw);
        }
        if (debugMode != DebugMode.RAW_ATLAS) {
            TextureTarget atlas = this.captureAtlas;
            this.captureAtlas = null;
            destroy(atlas);
        }
    }

    @Override
    public void close() {
        TextureTarget atlas = this.captureAtlas;
        TextureTarget raw = this.rawLobeTarget;
        TextureTarget field = this.fieldTarget;
        TextureTarget hostFrame = this.hostFrameTarget;
        this.captureAtlas = null;
        this.rawLobeTarget = null;
        this.fieldTarget = null;
        this.hostFrameTarget = null;
        destroy(atlas);
        destroy(raw);
        destroy(field);
        destroy(hostFrame);
    }

    private TextureTarget captureAtlas() {
        if (this.captureAtlas == null) {
            throw new IllegalStateException("Capture Atlas 已释放");
        }
        return this.captureAtlas;
    }

    private TextureTarget rawLobeTarget() {
        if (this.rawLobeTarget == null) {
            throw new IllegalStateException("Raw Lobe Target 已释放");
        }
        return this.rawLobeTarget;
    }

    private TextureTarget fieldTarget() {
        if (this.fieldTarget == null) {
            throw new IllegalStateException("Environment Field Target 已释放");
        }
        return this.fieldTarget;
    }

    private TextureTarget hostFrameTarget() {
        if (this.hostFrameTarget == null) {
            throw new IllegalStateException("Host Frame Target 已释放");
        }
        return this.hostFrameTarget;
    }

    private static void copyColor(RenderTarget source, RenderTarget destination) {
        if (source.width != destination.width || source.height != destination.height) {
            throw new IllegalArgumentException(
                    "Host Frame 尺寸不匹配: source=" + source.width + "x" + source.height
                            + ", destination=" + destination.width + "x" + destination.height
            );
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                Objects.requireNonNull(source.getColorTexture(), "source color texture"),
                Objects.requireNonNull(destination.getColorTexture(), "destination color texture"),
                0,
                0,
                0,
                0,
                0,
                source.width,
                source.height
        );
    }

    private static void destroy(TextureTarget target) {
        if (target != null) {
            target.destroyBuffers();
        }
    }

    enum DebugMode {
        NORMAL,
        RAW_ATLAS,
        RAW_LOBES,
        RESOLVED_FIELD;

        static DebugMode configured() {
            String value = System.getProperty("minetale.environment.debug", NORMAL.name());
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return NORMAL;
            }
        }
    }

    // 六面方向、相机角与 3×2 Atlas 的唯一 Java 定义；顺序必须匹配 lobe_extract.fsh 的 face index。
    enum CaptureFace {
        FRONT(0.0F, 0.0F, 0.0F, 0, 0, "front"),
        RIGHT(90.0F, 0.0F, 0.0F, 1, 0, "right"),
        BACK(180.0F, 0.0F, 0.0F, 2, 0, "back"),
        LEFT(-90.0F, 0.0F, 0.0F, 0, 1, "left"),
        UP(0.0F, -90.0F, 0.0F, 1, 1, "up"),
        DOWN(0.0F, 90.0F, 0.0F, 2, 1, "down");

        static final int COLUMNS = 3;
        static final int ROWS = 2;
        static final int FACE_SIZE = 128;
        static final int GUARD_TEXELS = 2;
        static final int TILE_STRIDE = FACE_SIZE + GUARD_TEXELS * 2;
        static final int ATLAS_WIDTH = COLUMNS * TILE_STRIDE;
        static final int ATLAS_HEIGHT = ROWS * TILE_STRIDE;

        static {
            Set<Integer> tiles = new HashSet<>();
            for (CaptureFace face : values()) {
                if (!tiles.add(face.tileY * COLUMNS + face.tileX)) {
                    throw new IllegalStateException("环境图集存在重复 tile: " + face);
                }
            }
            if (tiles.size() != COLUMNS * ROWS) {
                throw new IllegalStateException("环境图集没有覆盖完整 3x2 tile");
            }
        }

        private final float yawOffsetDegrees;
        private final float pitchDegrees;
        private final float rollDegrees;
        private final int tileX;
        private final int tileY;
        private final String debugName;

        CaptureFace(
                float yawOffsetDegrees,
                float pitchDegrees,
                float rollDegrees,
                int tileX,
                int tileY,
                String debugName
        ) {
            this.yawOffsetDegrees = yawOffsetDegrees;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            this.tileX = tileX;
            this.tileY = tileY;
            this.debugName = debugName;
        }

        static CaptureFace at(int index) {
            CaptureFace[] faces = values();
            if (index < 0 || index >= faces.length) {
                throw new IllegalArgumentException("环境捕获面索引越界: " + index);
            }
            return faces[index];
        }

        float yaw(float baseYawDegrees) {
            return wrapDegrees(baseYawDegrees + this.yawOffsetDegrees);
        }

        float pitchDegrees() {
            return this.pitchDegrees;
        }

        float rollDegrees() {
            return this.rollDegrees;
        }

        int viewportX() {
            return this.tileX * TILE_STRIDE + GUARD_TEXELS;
        }

        int viewportY() {
            return this.tileY * TILE_STRIDE + GUARD_TEXELS;
        }

        private static float wrapDegrees(float degrees) {
            float wrapped = degrees % 360.0F;
            return wrapped < -180.0F
                    ? wrapped + 360.0F
                    : (wrapped >= 180.0F ? wrapped - 360.0F : wrapped);
        }
    }
}
