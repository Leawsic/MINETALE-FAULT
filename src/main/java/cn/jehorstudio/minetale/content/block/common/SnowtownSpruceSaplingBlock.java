package cn.jehorstudio.minetale.content.block.common;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.feature.settings.SnowdinForestSettings;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public final class SnowtownSpruceSaplingBlock extends SaplingBlock {
    public static final MapCodec<SnowtownSpruceSaplingBlock> CODEC = simpleCodec(
            properties -> new SnowtownSpruceSaplingBlock(
                    CommonBlocksRegistry.SNOWTOWN_SPRUCE_TREE_GROWER,
                    properties
            )
    );

    public SnowtownSpruceSaplingBlock(TreeGrower treeGrower, BlockBehaviour.Properties properties) {
        super(treeGrower, properties);
    }

    @Override
    public MapCodec<? extends SaplingBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(CommonBlockTags.SNOWTOWN_SPRUCE_PLANTABLE_ON) || super.mayPlaceOn(state, level, pos);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.isAreaLoaded(pos, 1)) {
            return;
        }
        if (level.getMaxLocalRawBrightness(pos.above()) >= SnowdinForestSettings.SAPLING_MIN_GROWTH_BRIGHTNESS
                && random.nextInt(SnowdinForestSettings.SAPLING_GROWTH_CHANCE_DENOMINATOR) == 0) {
            this.advanceTree(level, pos, state, random);
        }
    }
}
