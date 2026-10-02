package cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.data;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerRegistry;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

// 生成 Placement Blocker 的 blockstate、方块模型与物品模型数据。
public final class PlacementBlockerModels {
    private static final ResourceLocation REDSTONE_BLOCK =
            ResourceLocation.withDefaultNamespace("block/redstone_block");
    private static final ResourceLocation BARRIER_ITEM =
            ResourceLocation.withDefaultNamespace("item/barrier");

    public static void register(BlockModelGenerators blockModels) {
        Block block = PlacementBlockerRegistry.PLACEMENT_BLOCKER.get();
        ResourceLocation blockModel = ModelTemplates.CUBE_ALL.create(
                block,
                TextureMapping.cube(REDSTONE_BLOCK),
                blockModels.modelOutput
        );
        blockModels.blockStateOutput.accept(
                BlockModelGenerators.createSimpleBlock(block, BlockModelGenerators.plainVariant(blockModel))
        );

        ResourceLocation itemModel = ResourceLocation.fromNamespaceAndPath(
                MineTale.MODID,
                "item/placement_blocker"
        );
        ModelTemplates.FLAT_ITEM.create(
                itemModel,
                new TextureMapping().put(TextureSlot.LAYER0, BARRIER_ITEM),
                blockModels.modelOutput
        );
        blockModels.itemModelOutput.accept(block.asItem(), ItemModelUtils.plainModel(itemModel));
    }

    private PlacementBlockerModels() {}
}
