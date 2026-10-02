package cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.data;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.MysteriousCampfireRegistry;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class MysteriousCampfireModels {
    private static final ResourceLocation CAMPFIRE_ITEM =
            ResourceLocation.withDefaultNamespace("item/campfire");

    public static void register(BlockModelGenerators blockModels) {
        var campfire = PropertyDispatch.initial(
                BlockStateProperties.HORIZONTAL_FACING,
                BlockStateProperties.LIT
        );
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (boolean lit : new boolean[]{false, true}) {
                ResourceLocation model = lit
                        ? ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "block/mysterious_campfire")
                        : ResourceLocation.withDefaultNamespace("block/campfire_off");
                var variant = BlockModelGenerators.plainVariant(model);
                variant = switch (facing) {
                    case EAST -> variant.with(BlockModelGenerators.Y_ROT_270);
                    case NORTH -> variant.with(BlockModelGenerators.Y_ROT_180);
                    case WEST -> variant.with(BlockModelGenerators.Y_ROT_90);
                    default -> variant;
                };
                campfire.select(facing, lit, variant);
            }
        }
        blockModels.blockStateOutput.accept(
                MultiVariantGenerator.dispatch(MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE.get())
                        .with(campfire)
        );
        blockModels.registerSimpleItemModel(
                MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE.get(),
                CAMPFIRE_ITEM
        );
    }

    private MysteriousCampfireModels() {}
}
