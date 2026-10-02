package cn.jehorstudio.minetale.battle.presentation.data;

import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class BattlePresentationTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.add("key.category.minetale.category", "MineTale Battle");
        provider.add("key.minetale.confirm", "Confirm");
        provider.add("key.minetale.confirm_alternate", "Confirm (Alternate)");
        provider.add("key.minetale.skip", "Skip");
        provider.add("key.minetale.skip_alternate", "Skip (Alternate)");
        provider.add("key.minetale.open_visual_effects_tuning", "Open Visual Effects Tuning Panel");
        provider.add("minetale.configuration.post_processing", "Post-processing");
        addConfiguration(provider, "post_bloom_enabled", "Enable Bloom",
                "Enables material-driven Bloom from the B channel of valid *_s.png textures.");
        addConfiguration(provider, "post_bloom_quality", "Bloom Sampling Quality",
                "Low and Medium use the standard four-read Tent filter; High increases first-level precision.");
        addConfiguration(provider, "post_bloom_emissive_curve", "Bloom Emissive Curve",
                "Applies an exponent to the linear *_s.b material energy before building the Bloom seed.");
        addConfiguration(provider, "post_bloom_intensity", "Bloom Intensity",
                "Controls only the final soft LDR composite gain and never changes scene alpha.");
        addConfiguration(provider, "post_bloom_blur_radius_pixels", "Bloom Radius (pixels)",
                "Selects and continuously blends real pyramid scales; quality does not change the radius.");
        provider.add("screen.minetale.battle_script_selection.title", "Select Battle Script");
        provider.add("screen.minetale.battle_script_selection.empty", "No validated battle scripts are available");
        provider.add("screen.minetale.battle_script_selection.start", "Start Battle");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.add("key.category.minetale.category", "MINETALE 战斗");
        provider.add("key.minetale.confirm", "确认");
        provider.add("key.minetale.confirm_alternate", "确认（备用）");
        provider.add("key.minetale.skip", "跳过");
        provider.add("key.minetale.skip_alternate", "跳过（备用）");
        provider.add("key.minetale.open_visual_effects_tuning", "打开视效实时调参面板");
        VisualConfig.addConfigurationTranslations(provider::add);
        provider.add("screen.minetale.battle_script_selection.title", "选择战斗剧本");
        provider.add("screen.minetale.battle_script_selection.empty", "没有已通过校验的战斗剧本");
        provider.add("screen.minetale.battle_script_selection.start", "开始战斗");
    }

    private static void addConfiguration(
            LanguageProvider provider,
            String key,
            String label,
            String tooltip
    ) {
        provider.add("minetale.configuration." + key, label);
        provider.add("minetale.configuration." + key + ".tooltip", tooltip);
    }

    private BattlePresentationTranslations() {}
}
