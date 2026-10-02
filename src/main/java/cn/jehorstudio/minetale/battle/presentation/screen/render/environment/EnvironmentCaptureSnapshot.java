package cn.jehorstudio.minetale.battle.presentation.screen.render.environment;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// 单次 Renderer.State 显式携带的只读环境快照。
public final class EnvironmentCaptureSnapshot {
    private static final EnvironmentCaptureSnapshot IDLE =
            new EnvironmentCaptureSnapshot(null, null, null, null);

    private final UUID battleId;
    private final ActivationFrame activationFrame;
    private final EnvironmentField field;
    private final DebugTexture debugTexture;

    EnvironmentCaptureSnapshot(
            UUID battleId,
            ActivationFrame activationFrame,
            EnvironmentField field,
            DebugTexture debugTexture
    ) {
        this.battleId = battleId;
        this.activationFrame = activationFrame;
        this.field = field;
        this.debugTexture = debugTexture;
        boolean idle = battleId == null
                && activationFrame == null
                && field == null
                && debugTexture == null;
        boolean ready = battleId != null && activationFrame != null && field != null;
        if (!idle && !ready) {
            throw new IllegalArgumentException(
                    "环境快照必须为空闲或包含完整 Activation Frame 与 Environment Field"
            );
        }
    }

    public static EnvironmentCaptureSnapshot idle() {
        return IDLE;
    }

    public Optional<UUID> battleId() {
        return Optional.ofNullable(this.battleId);
    }

    public Optional<ActivationFrame> activationFrame() {
        return Optional.ofNullable(this.activationFrame);
    }

    public Optional<EnvironmentField> field() {
        return Optional.ofNullable(this.field);
    }

    public Optional<DebugTexture> debugTexture() {
        return Optional.ofNullable(this.debugTexture);
    }

    public boolean ready() {
        return this.activationFrame != null && this.field != null;
    }

    // 纹理在激活时冻结
    // 所有权属于当前捕获会话。
    public record ActivationFrame(
            GpuTextureView textureView,
            int width,
            int height
    ) {
        public ActivationFrame {
            Objects.requireNonNull(textureView, "textureView");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Activation Frame 参数无效");
            }
        }
    }

    public record DebugTexture(
            GpuTextureView textureView,
            int width,
            int height
    ) {
        public DebugTexture {
            Objects.requireNonNull(textureView, "textureView");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("环境调试纹理参数无效");
            }
        }
    }
}
