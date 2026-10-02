package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// 集中注册 Template Marker 功能拥有的 Block 与 BlockItem。
public final class TemplateMarkerRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MineTale.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MineTale.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MineTale.MODID);
    private static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, MineTale.MODID);

    public static final DeferredBlock<TemplateMarkerBlock> TEMPLATE_MARKER =
            BLOCKS.registerBlock(
                    "template_marker",
                    TemplateMarkerBlock::new,
                    TemplateMarkerBlock::markerProperties
            );

    public static final DeferredItem<BlockItem> TEMPLATE_MARKER_ITEM =
            ITEMS.registerSimpleBlockItem("template_marker", TEMPLATE_MARKER);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TemplateMarkerBlockEntity>>
            TEMPLATE_MARKER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "template_marker",
                    () -> new BlockEntityType<>(
                            TemplateMarkerBlockEntity::new,
                            true,
                            TEMPLATE_MARKER.get()
                    )
            );

    public static final DeferredHolder<MenuType<?>, MenuType<TemplateMarkerMenu>> TEMPLATE_MARKER_MENU =
            MENU_TYPES.register(
                    "template_marker",
                    () -> IMenuTypeExtension.create(TemplateMarkerMenu::new)
            );

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
    }

    private TemplateMarkerRegistry() {}
}
