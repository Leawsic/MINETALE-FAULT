package cn.jehorstudio.minetale.configuration.client;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.contentpack.client.ContentPackManagementScreen;
import cn.jehorstudio.minetale.content.player.soul.SoulRecall;
import cn.jehorstudio.minetale.content.player.soul.SoulRecallClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;

public final class MineTaleConfigurationScreen extends OptionsSubScreen {
    private static final int BUTTON_WIDTH = 310;
    private final ModContainer mod;

    public MineTaleConfigurationScreen(ModContainer mod, Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("minetale.configuration.title"));
        this.mod = mod;
    }

    @Override
    protected void addOptions() {
        Button contentPacks = Button.builder(
                Component.translatable("minetale.configuration.content_packs"),
                ignored -> minecraft.setScreen(new ContentPackManagementScreen(this)))
                .width(BUTTON_WIDTH)
                .build();
        list.addSmall(contentPacks, null);

        for (ModConfig.Type type : ModConfig.Type.values()) {
            for (ModConfig config : ModConfigs.getConfigSet(type)) {
                if (!MineTale.MODID.equals(config.getModId())) {
                    continue;
                }
                boolean soulRecallConfig = config.getSpec() == SoulRecall.SPEC;
                Component sectionTitle;
                if (soulRecallConfig) {
                    sectionTitle = Component.translatable("minetale.configuration.interaction");
                } else if (config.getSpec() == VisualConfig.SPEC) {
                    sectionTitle = Component.translatable("minetale.configuration.combat");
                } else if (config.getSpec() == cn.jehorstudio.minetale.voxel.scene.runtime.SceneConfig.SPEC) {
                    sectionTitle = Component.translatable("minetale.configuration.voxel");
                } else {
                    sectionTitle = Component.translatable("minetale.configuration.general");
                }
                Button button = Button.builder(sectionTitle, ignored -> minecraft.setScreen(
                        soulRecallConfig
                                ? SoulRecallClient.configurationScreen(this, type, config, sectionTitle)
                                : new ConfigurationSectionScreen(this, type, config, sectionTitle)))
                        .width(BUTTON_WIDTH)
                        .build();
                button.active = config.getSpec() instanceof net.neoforged.neoforge.common.ModConfigSpec spec
                        && spec.isLoaded();
                list.addSmall(button, null);
            }
        }
    }
}
