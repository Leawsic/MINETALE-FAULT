package cn.jehorstudio.minetale.dimension.worldgen.asset.blocker;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// 集中注册 Placement Blocker 功能拥有的 Block 与 BlockItem。
public final class PlacementBlockerRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MineTale.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MineTale.MODID);

    public static final DeferredBlock<Block> PLACEMENT_BLOCKER =
            BLOCKS.registerSimpleBlock(
                    "placement_blocker",
                    () -> BlockBehaviour.Properties.of()
                            .strength(0.3f)
                            .sound(SoundType.GLASS)
                            .noOcclusion()
            );

    public static final DeferredItem<BlockItem> PLACEMENT_BLOCKER_ITEM =
            ITEMS.registerSimpleBlockItem("placement_blocker", PLACEMENT_BLOCKER);

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    private PlacementBlockerRegistry() {}
}
