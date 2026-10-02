package cn.jehorstudio.minetale.lib.client.particle.gpu;

import cn.jehorstudio.minetale.lib.ObjModels;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

// 粒子动作注册后保持不变的资源与绘制定义。
public record ParticleDefinition(
        ResourceLocation id,
        int particleCount,
        ResourceLocation spriteTexture,
        ResourceLocation vertexShader,
        float alphaCutout,
        @Nullable AnchorModel anchorModel
) {
    public static final int VERTICES_PER_PARTICLE = 6;

    public ParticleDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(spriteTexture, "spriteTexture");
        Objects.requireNonNull(vertexShader, "vertexShader");
        if (particleCount <= 0) {
            throw new IllegalArgumentException("粒子数量必须大于 0");
        }
        try {
            Math.multiplyExact(particleCount, VERTICES_PER_PARTICLE);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("粒子数量超出 draw 范围", exception);
        }
        requireUnit(alphaCutout, "alphaCutout");
    }

    // 中心模型走原版 RenderType。
    public record AnchorModel(ObjModels model, float scale, Color tint, float opacity) {
        public AnchorModel {
            Objects.requireNonNull(model, "model");
            if (!Float.isFinite(scale) || scale <= 0.0F) {
                throw new IllegalArgumentException("anchorModel.scale 必须是有限正数");
            }
            Objects.requireNonNull(tint, "tint");
            requireUnit(opacity, "anchorModel.opacity");
        }
    }

    // 颜色使用 0..1 线性 RGB。
    public record Color(float red, float green, float blue) {
        public Color {
            requireUnit(red, "color.red");
            requireUnit(green, "color.green");
            requireUnit(blue, "color.blue");
        }
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " 必须在 0 到 1 之间");
        }
    }
}
