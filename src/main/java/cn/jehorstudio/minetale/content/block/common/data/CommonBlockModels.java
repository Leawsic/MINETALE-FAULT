package cn.jehorstudio.minetale.content.block.common.data;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

public final class CommonBlockModels {
    private static final int SPRUCE_LEAVES_ITEM_TINT = FoliageColor.FOLIAGE_EVERGREEN;
    private static final ResourceLocation SPRUCE_LOG = minecraftBlock("spruce_log");
    private static final ResourceLocation SPRUCE_LOG_TOP = minecraftBlock("spruce_log_top");
    private static final ResourceLocation SPRUCE_WOOD_MODEL = minecraftBlock("spruce_wood");
    private static final ResourceLocation STRIPPED_SPRUCE_LOG = minecraftBlock("stripped_spruce_log");
    private static final ResourceLocation STRIPPED_SPRUCE_LOG_TOP = minecraftBlock("stripped_spruce_log_top");
    private static final ResourceLocation STRIPPED_SPRUCE_WOOD_MODEL = minecraftBlock("stripped_spruce_wood");
    private static final ResourceLocation SPRUCE_LEAVES = minecraftBlock("spruce_leaves");
    private static final ResourceLocation SPRUCE_SAPLING = minecraftBlock("spruce_sapling");

    public static void register(BlockModelGenerators blockModels) {
        blockModels.createTrivialCube(CommonBlocksRegistry.RUIN_BRICK.get());
        blockModels.createRotatedMirroredVariantBlock(CommonBlocksRegistry.GEO_ROCK.get());
        blockModels.createTrivialCube(CommonBlocksRegistry.SNOW_ROCK.get());
        registerLogWithHorizontal(
                blockModels,
                CommonBlocksRegistry.SNOWTOWN_SPRUCE_LOG.get(),
                TextureMapping.column(SPRUCE_LOG, SPRUCE_LOG_TOP)
        );
        registerWood(blockModels, CommonBlocksRegistry.SNOWTOWN_SPRUCE_WOOD.get(), SPRUCE_LOG);
        registerLogWithHorizontal(
                blockModels,
                CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_LOG.get(),
                TextureMapping.column(STRIPPED_SPRUCE_LOG, STRIPPED_SPRUCE_LOG_TOP)
        );
        registerWood(blockModels, CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_WOOD.get(), STRIPPED_SPRUCE_LOG);
        registerPlainBlockItem(blockModels, CommonBlocksRegistry.SNOWTOWN_SPRUCE_LOG.get(), SPRUCE_LOG);
        registerPlainBlockItem(blockModels, CommonBlocksRegistry.SNOWTOWN_SPRUCE_WOOD.get(), SPRUCE_WOOD_MODEL);
        registerPlainBlockItem(blockModels, CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_LOG.get(), STRIPPED_SPRUCE_LOG);
        registerPlainBlockItem(blockModels, CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_WOOD.get(), STRIPPED_SPRUCE_WOOD_MODEL);
        registerSnowtownSpruceLeaves(blockModels);
        registerSnowtownSpruceSapling(blockModels);
    }

    private static void registerSnowtownSpruceLeaves(BlockModelGenerators blockModels) {
        Block leaves = CommonBlocksRegistry.SNOWTOWN_SPRUCE_LEAVES.get();
        ResourceLocation model = ModelTemplates.LEAVES.create(
                leaves,
                TextureMapping.cube(SPRUCE_LEAVES),
                blockModels.modelOutput
        );
        blockModels.blockStateOutput.accept(
                BlockModelGenerators.createSimpleBlock(leaves, BlockModelGenerators.plainVariant(model))
        );
        blockModels.itemModelOutput.accept(
                leaves.asItem(),
                ItemModelUtils.tintedModel(SPRUCE_LEAVES, ItemModelUtils.constantTint(SPRUCE_LEAVES_ITEM_TINT))
        );
    }

    private static void registerSnowtownSpruceSapling(BlockModelGenerators blockModels) {
        Block sapling = CommonBlocksRegistry.SNOWTOWN_SPRUCE_SAPLING.get();
        ExtendedModelTemplateBuilder.of(ModelTemplates.CROSS)
                .renderType("minecraft:cutout_mipped")
                .build()
                .create(sapling, TextureMapping.cross(SPRUCE_SAPLING), blockModels.modelOutput);
        blockModels.blockStateOutput.accept(BlockModelGenerators.createSimpleBlock(
                sapling,
                BlockModelGenerators.plainVariant(
                        ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "block/snowtown_spruce_sapling")
                )
        ));

        ResourceLocation itemModel = ResourceLocation.fromNamespaceAndPath(
                MineTale.MODID,
                "item/snowtown_spruce_sapling"
        );
        ModelTemplates.FLAT_ITEM.create(
                itemModel,
                new TextureMapping().put(TextureSlot.LAYER0, SPRUCE_SAPLING),
                blockModels.modelOutput
        );
        blockModels.itemModelOutput.accept(sapling.asItem(), ItemModelUtils.plainModel(itemModel));
    }

    private static void registerLogWithHorizontal(
            BlockModelGenerators blockModels,
            Block log,
            TextureMapping mapping
    ) {
        ResourceLocation verticalModel = ModelTemplates.CUBE_COLUMN.create(log, mapping, blockModels.modelOutput);
        ResourceLocation horizontalModel = ModelTemplates.CUBE_COLUMN_HORIZONTAL.create(
                log,
                mapping,
                blockModels.modelOutput
        );
        blockModels.blockStateOutput.accept(BlockModelGenerators.createRotatedPillarWithHorizontalVariant(
                log,
                BlockModelGenerators.plainVariant(verticalModel),
                BlockModelGenerators.plainVariant(horizontalModel)
        ));
    }

    private static void registerWood(BlockModelGenerators blockModels, Block wood, ResourceLocation texture) {
        ResourceLocation model = ModelTemplates.CUBE_COLUMN.create(
                wood,
                TextureMapping.column(texture, texture),
                blockModels.modelOutput
        );
        blockModels.blockStateOutput.accept(
                BlockModelGenerators.createAxisAlignedPillarBlock(wood, BlockModelGenerators.plainVariant(model))
        );
    }

    private static void registerPlainBlockItem(
            BlockModelGenerators blockModels,
            Block block,
            ResourceLocation model
    ) {
        blockModels.itemModelOutput.accept(block.asItem(), ItemModelUtils.plainModel(model));
    }

    private static ResourceLocation minecraftBlock(String path) {
        return ResourceLocation.withDefaultNamespace("block/" + path);
    }

    private CommonBlockModels() {}
}
