package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.MineTale;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.ModConfigSpec.ValueSpec;

@EventBusSubscriber(modid = MineTale.MODID, value = Dist.CLIENT)
public final class SoulRecallClient {
    private static final int TICKS_PER_SECOND = 20;
    private static int heldTicks;
    private static boolean recallRequested;

    private SoulRecallClient() {
    }

    public static Screen configurationScreen(
            Screen parent,
            ModConfig.Type type,
            ModConfig config,
            Component title
    ) {
        return new RecallConfigurationScreen(parent, type, config, title);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!canContinueHolding(minecraft)) {
            heldTicks = 0;
            recallRequested = false;
            return;
        }
        if (recallRequested) {
            return;
        }

        heldTicks++;
        int requiredTicks = Mth.ceil(SoulRecall.holdSeconds() * TICKS_PER_SECOND);
        if (heldTicks >= requiredTicks) {
            ClientPacketDistributor.sendToServer(new SoulRecall.RecallRequest());
            recallRequested = true;
        }
    }

    private static boolean canContinueHolding(Minecraft minecraft) {
        return minecraft.player != null
                && minecraft.level != null
                && minecraft.screen == null
                && minecraft.options.keyUse.isDown()
                && minecraft.player.getMainHandItem().isEmpty()
                && minecraft.player.getOffhandItem().isEmpty();
    }

    // NeoForge 默认将 DoubleValue 显示为输入框；此处改为 0.1 秒步进滑条。
    private static final class RecallConfigurationScreen extends ConfigurationSectionScreen {
        private static final int MIN_TENTHS = (int) (SoulRecall.MIN_HOLD_SECONDS * 10.0D);
        private static final int MAX_TENTHS = (int) (SoulRecall.MAX_HOLD_SECONDS * 10.0D);

        private RecallConfigurationScreen(
                Screen parent,
                ModConfig.Type type,
                ModConfig config,
                Component title
        ) {
            super(parent, type, config, title);
        }

        @Override
        protected Element createDoubleValue(
                String key,
                ValueSpec spec,
                Supplier<Double> source,
                Consumer<Double> target
        ) {
            var values = new OptionInstance.IntRange(MIN_TENTHS, MAX_TENTHS).xmap(
                    tenths -> tenths / 10.0D,
                    seconds -> Mth.clamp((int) Math.round(seconds * 10.0D), MIN_TENTHS, MAX_TENTHS)
            );
            OptionInstance<Double> option = new OptionInstance<>(
                    getTranslationKey(key),
                    getTooltip(key, spec.getRange()),
                    (caption, seconds) -> Component.literal(String.format(Locale.ROOT, "%.1f s", seconds)),
                    values,
                    source.get(),
                    newValue -> {
                        if (!newValue.equals(source.get())) {
                            undoManager.add(value -> {
                                target.accept(value);
                                onChanged(key);
                            }, newValue, value -> {
                                target.accept(value);
                                onChanged(key);
                            }, source.get());
                        }
                    }
            );
            return new Element(getTranslationComponent(key), getTooltipComponent(key, spec.getRange()), option);
        }
    }
}
