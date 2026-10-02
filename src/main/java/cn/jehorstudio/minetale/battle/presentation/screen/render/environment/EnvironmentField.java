package cn.jehorstudio.minetale.battle.presentation.screen.render.environment;

import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

// 稀疏环境纹理与冻结捕获基不可拆分；光瓣方向只能在对应基下解释。
public record EnvironmentField(
        GpuTextureView textureView,
        int width,
        int height,
        Vec3 right,
        Vec3 up,
        Vec3 forward
) {
    public EnvironmentField {
        Objects.requireNonNull(textureView, "textureView");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(up, "up");
        Objects.requireNonNull(forward, "forward");
        if (width != EnvironmentCaptureGpu.FIELD_TEXTURE_WIDTH
                || height != EnvironmentCaptureGpu.FIELD_TEXTURE_HEIGHT) {
            throw new IllegalArgumentException("Environment Field Texture 必须为 32×1");
        }
    }
}
