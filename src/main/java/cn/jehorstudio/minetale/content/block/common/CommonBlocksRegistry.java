package cn.jehorstudio.minetale.content.block.common;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TintedParticleLeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.minecraft.world.level.block.grower.TreeGrower;

import java.util.Optional;

public final class CommonBlocksRegistry {
    public static final DeferredRegister.Blocks BLOCKS=
            DeferredRegister.createBlocks(MineTale.MODID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MineTale.MODID);

    public static final TreeGrower SNOWTOWN_SPRUCE_TREE_GROWER = new TreeGrower(
            "minetale:snowtown_spruce",
            Optional.empty(),
            Optional.of(ModWorldgenKeys.SNOWTOWN_SPRUCE),
            Optional.empty()
    );

    public static final DeferredBlock<Block> RUIN_BRICK =
            BLOCKS.registerSimpleBlock("ruin_brick",
                    () -> BlockBehaviour.Properties.of()
                            .strength(1.5f,6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops());

    public static final DeferredBlock<Block> GEO_ROCK =
            BLOCKS.registerSimpleBlock("geo_rock",
                    () -> BlockBehaviour.Properties.of()
                            .strength(1.5f, 6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops());

    public static final DeferredBlock<Block> SNOW_ROCK =
            BLOCKS.registerSimpleBlock("snow_rock",
                    () -> BlockBehaviour.Properties.of()
                            .strength(1.5f, 6.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops());

    public static final DeferredBlock<RotatedPillarBlock> SNOWTOWN_SPRUCE_LOG =
            BLOCKS.registerBlock("snowtown_spruce_log",
                    RotatedPillarBlock::new,
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_LOG));

    public static final DeferredBlock<RotatedPillarBlock> SNOWTOWN_SPRUCE_WOOD =
            BLOCKS.registerBlock("snowtown_spruce_wood",
                    RotatedPillarBlock::new,
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_WOOD));

    public static final DeferredBlock<RotatedPillarBlock> STRIPPED_SNOWTOWN_SPRUCE_LOG =
            BLOCKS.registerBlock("stripped_snowtown_spruce_log",
                    RotatedPillarBlock::new,
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.STRIPPED_SPRUCE_LOG));

    public static final DeferredBlock<RotatedPillarBlock> STRIPPED_SNOWTOWN_SPRUCE_WOOD =
            BLOCKS.registerBlock("stripped_snowtown_spruce_wood",
                    RotatedPillarBlock::new,
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.STRIPPED_SPRUCE_WOOD));

    public static final DeferredBlock<TintedParticleLeavesBlock> SNOWTOWN_SPRUCE_LEAVES =
            BLOCKS.registerBlock("snowtown_spruce_leaves",
                    properties -> new TintedParticleLeavesBlock(
                            0.01f,
                            properties
                    ),
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_LEAVES));

    public static final DeferredBlock<SnowtownSpruceSaplingBlock> SNOWTOWN_SPRUCE_SAPLING =
            BLOCKS.registerBlock("snowtown_spruce_sapling",
                    properties -> new SnowtownSpruceSaplingBlock(
                            SNOWTOWN_SPRUCE_TREE_GROWER,
                            properties
                    ),
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.SPRUCE_SAPLING));

    public static final DeferredItem<BlockItem> RUIN_BRICK_ITEM =
            ITEMS.registerSimpleBlockItem("ruin_brick", RUIN_BRICK);
    public static final DeferredItem<BlockItem> GEO_ROCK_ITEM =
            ITEMS.registerSimpleBlockItem("geo_rock", GEO_ROCK);
    public static final DeferredItem<BlockItem> SNOW_ROCK_ITEM =
            ITEMS.registerSimpleBlockItem("snow_rock", SNOW_ROCK);
    public static final DeferredItem<BlockItem> SNOWTOWN_SPRUCE_LOG_ITEM =
            ITEMS.registerSimpleBlockItem("snowtown_spruce_log", SNOWTOWN_SPRUCE_LOG);
    public static final DeferredItem<BlockItem> SNOWTOWN_SPRUCE_WOOD_ITEM =
            ITEMS.registerSimpleBlockItem("snowtown_spruce_wood", SNOWTOWN_SPRUCE_WOOD);
    public static final DeferredItem<BlockItem> STRIPPED_SNOWTOWN_SPRUCE_LOG_ITEM =
            ITEMS.registerSimpleBlockItem("stripped_snowtown_spruce_log", STRIPPED_SNOWTOWN_SPRUCE_LOG);
    public static final DeferredItem<BlockItem> STRIPPED_SNOWTOWN_SPRUCE_WOOD_ITEM =
            ITEMS.registerSimpleBlockItem("stripped_snowtown_spruce_wood", STRIPPED_SNOWTOWN_SPRUCE_WOOD);
    public static final DeferredItem<BlockItem> SNOWTOWN_SPRUCE_LEAVES_ITEM =
            ITEMS.registerSimpleBlockItem("snowtown_spruce_leaves", SNOWTOWN_SPRUCE_LEAVES);
    public static final DeferredItem<BlockItem> SNOWTOWN_SPRUCE_SAPLING_ITEM =
            ITEMS.registerSimpleBlockItem("snowtown_spruce_sapling", SNOWTOWN_SPRUCE_SAPLING);

    public static void register(IEventBus modEventBus){
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    private CommonBlocksRegistry(){}
}
