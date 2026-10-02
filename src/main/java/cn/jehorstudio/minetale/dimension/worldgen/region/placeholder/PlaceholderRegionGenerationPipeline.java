package cn.jehorstudio.minetale.dimension.worldgen.region.placeholder;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.region.GenerationStages;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationPipeline;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationTask;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain.OriginExitConnector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

public final class PlaceholderRegionGenerationPipeline implements RegionGenerationPipeline<PlaceholderRegionContext> {
    public static final PlaceholderRegionGenerationPipeline TRANSITION_TUNNEL =
            new PlaceholderRegionGenerationPipeline(RainbowCakeModel.CakeRegion.TRANSITION_TUNNEL);
    public static final PlaceholderRegionGenerationPipeline RUINS =
            new PlaceholderRegionGenerationPipeline(RainbowCakeModel.CakeRegion.RUINS);
    public static final PlaceholderRegionGenerationPipeline WATERFALL =
            new PlaceholderRegionGenerationPipeline(RainbowCakeModel.CakeRegion.WATERFALL);
    public static final PlaceholderRegionGenerationPipeline HOT_LAND =
            new PlaceholderRegionGenerationPipeline(RainbowCakeModel.CakeRegion.HOT_LAND);

    private final RainbowCakeModel.CakeRegion cakeRegion;
    private final GenerationStages.TerrainStage<PlaceholderRegionContext> stage1 = this::carveOriginExitConnector;
    private final GenerationStages.ElementStage<PlaceholderRegionContext> stage2 = context -> {};
    private final GenerationStages.MaterialStage<PlaceholderRegionContext> stage3 = context -> {};
    private final GenerationStages.StructureStage<PlaceholderRegionContext> stage4 = context -> {};
    private final GenerationStages.FeatureStage<PlaceholderRegionContext> stage5 = context -> {};

    private PlaceholderRegionGenerationPipeline(RainbowCakeModel.CakeRegion cakeRegion) {
        this.cakeRegion = cakeRegion;
    }

    @Override
    public RainbowCakeModel.CakeRegion cakeRegion() {
        return cakeRegion;
    }

    @Override
    public PlaceholderRegionContext createContext(RegionGenerationTask task) {
        return new PlaceholderRegionContext(task);
    }

    @Override
    public GenerationStages.TerrainStage<PlaceholderRegionContext> stage1() {
        return stage1;
    }

    @Override
    public GenerationStages.ElementStage<PlaceholderRegionContext> stage2() {
        return stage2;
    }

    @Override
    public GenerationStages.MaterialStage<PlaceholderRegionContext> stage3() {
        return stage3;
    }

    @Override
    public GenerationStages.StructureStage<PlaceholderRegionContext> stage4() {
        return stage4;
    }

    @Override
    public GenerationStages.FeatureStage<PlaceholderRegionContext> stage5() {
        return stage5;
    }

    private void carveOriginExitConnector(PlaceholderRegionContext context) {
        if (cakeRegion != RainbowCakeModel.CakeRegion.TRANSITION_TUNNEL) {
            return;
        }
        ChunkAccess chunk = context.world().chunk();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = context.world().minY(); y <= context.world().maxY(); y++) {
            if (OriginExitConnector.shouldCarve(context.task().cakeSample(), y)) {
                cursor.set(context.x(), y, context.z());
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState());
            }
        }
    }
}
