package cn.jehorstudio.minetale.dimension.worldgen.region.edge;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.region.GenerationStages;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationPipeline;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

public final class EdgeRegionGenerationPipeline implements RegionGenerationPipeline<EdgeRegionContext> {
    public static final EdgeRegionGenerationPipeline INSTANCE = new EdgeRegionGenerationPipeline();

    private final GenerationStages.TerrainStage<EdgeRegionContext> stage1 = this::fillBedrock;
    private final GenerationStages.ElementStage<EdgeRegionContext> stage2 = context -> {};
    private final GenerationStages.MaterialStage<EdgeRegionContext> stage3 = context -> {};
    private final GenerationStages.StructureStage<EdgeRegionContext> stage4 = context -> {};
    private final GenerationStages.FeatureStage<EdgeRegionContext> stage5 = context -> {};

    private EdgeRegionGenerationPipeline() {}

    @Override
    public RainbowCakeModel.CakeRegion cakeRegion() {
        return RainbowCakeModel.CakeRegion.EDGE_REGION;
    }

    @Override
    public EdgeRegionContext createContext(RegionGenerationTask task) {
        return new EdgeRegionContext(task);
    }

    @Override
    public GenerationStages.TerrainStage<EdgeRegionContext> stage1() {
        return stage1;
    }

    @Override
    public GenerationStages.ElementStage<EdgeRegionContext> stage2() {
        return stage2;
    }

    @Override
    public GenerationStages.MaterialStage<EdgeRegionContext> stage3() {
        return stage3;
    }

    @Override
    public GenerationStages.StructureStage<EdgeRegionContext> stage4() {
        return stage4;
    }

    @Override
    public GenerationStages.FeatureStage<EdgeRegionContext> stage5() {
        return stage5;
    }

    private void fillBedrock(EdgeRegionContext context) {
        ChunkAccess chunk = context.world().chunk();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = context.world().minY(); y <= context.world().maxY(); y++) {
            cursor.set(context.x(), y, context.z());
            chunk.setBlockState(cursor, Blocks.BEDROCK.defaultBlockState());
        }
    }
}
