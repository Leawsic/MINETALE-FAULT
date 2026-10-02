package cn.jehorstudio.minetale.magic.visual.vfx;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class CameraShakeConfig {
    private static final ModConfigSpec SPEC;
    private static final ModConfigSpec.DoubleValue SCALE;

    static {
        var builder = new ModConfigSpec.Builder();
        SCALE = builder.translation("minetale.options.camera_shake")
                .comment("镜头震动倍率；0 完全关闭，1 为完整强度。")
                .defineInRange("camera_shake_scale", 1.0, 0.0, 1.0);
        SPEC = builder.build();
    }

    private CameraShakeConfig() {}

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, "minetale-vfx-client.toml");
    }

    static double scale() { return SPEC.isLoaded() ? SCALE.getAsDouble() : 1.0; }

    static void setScale(double scale) {
        SCALE.set(scale);
    }

    static void save() { SCALE.save(); }
}
