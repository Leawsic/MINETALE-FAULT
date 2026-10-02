package cn.jehorstudio.minetale.content;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.content.entity.flowey.FloweyRegistry;
import cn.jehorstudio.minetale.content.item.common.CommonItemsRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MineTaleCreativeTab {
    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MineTale.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MINETALE =
            CREATIVE_MODE_TABS.register("minetale", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.minetale"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> CommonItemsRegistry.MONSTER_CANDY.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(CommonItemsRegistry.MONSTER_CANDY.get());
                        output.accept(CommonItemsRegistry.BONE_FRAGMENT.get());
                        output.accept(FloweyRegistry.FLOWEY_SPAWN_EGG.get());

                        output.accept(CommonBlocksRegistry.RUIN_BRICK_ITEM.get());
                        output.accept(CommonBlocksRegistry.GEO_ROCK_ITEM.get());
                        output.accept(CommonBlocksRegistry.SNOW_ROCK_ITEM.get());
                        output.accept(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LOG_ITEM.get());
                        output.accept(CommonBlocksRegistry.SNOWTOWN_SPRUCE_WOOD_ITEM.get());
                        output.accept(CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_LOG_ITEM.get());
                        output.accept(CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_WOOD_ITEM.get());
                        output.accept(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LEAVES_ITEM.get());
                        output.accept(CommonBlocksRegistry.SNOWTOWN_SPRUCE_SAPLING_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }

    private MineTaleCreativeTab() {}
}
