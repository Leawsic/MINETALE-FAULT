package cn.jehorstudio.minetale.dimension.worldgen.region;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenContext;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;

import java.util.Collection;
import java.util.Objects;
import java.util.function.BiConsumer;

public final class RegionGeneration {
    private final RegionGenerationPipeline<? extends RegionContext>[] pipelinesByRegion;
    private final UndergroundRegion[] taskRegionsByCakeRegion;

    @SuppressWarnings("unchecked")
    public RegionGeneration(Collection<? extends RegionGenerationPipeline<? extends RegionContext>> pipelines) {
        Objects.requireNonNull(pipelines, "pipelines");
        this.pipelinesByRegion = new RegionGenerationPipeline[RainbowCakeModel.CakeRegion.values().length];
        this.taskRegionsByCakeRegion = createTaskRegionLookup();
        for (RegionGenerationPipeline<? extends RegionContext> pipeline : pipelines) {
            if (this.pipelinesByRegion[pipeline.cakeRegion().ordinal()] != null) {
                throw new IllegalArgumentException("Duplicate pipeline for " + pipeline.cakeRegion());
            }
            this.pipelinesByRegion[pipeline.cakeRegion().ordinal()] = pipeline;
        }
        for (RainbowCakeModel.CakeRegion region : RainbowCakeModel.CakeRegion.values()) {
            if (this.pipelinesByRegion[region.ordinal()] == null) {
                throw new IllegalArgumentException("Missing pipeline for " + region);
            }
        }
    }

    public void runStage1(WorldgenContext world) {
        runStage(world, RegionGenerationPipeline::runStage1);
    }

    public void runStage2(WorldgenContext world) {
        runStage(world, RegionGenerationPipeline::runStage2);
    }

    public void runStage3(WorldgenContext world) {
        runStage(world, RegionGenerationPipeline::runStage3);
    }

    public void runStage4(WorldgenContext world) {
        runStage(world, RegionGenerationPipeline::runStage4);
    }

    public void runStage5(WorldgenContext world) {
        runStage(world, RegionGenerationPipeline::runStage5);
    }

    private void runStage(
            WorldgenContext world,
            BiConsumer<RegionGenerationPipeline<? extends RegionContext>, RegionGenerationTask> runner
    ) {
        int minBlockX = world.chunk().getPos().getMinBlockX();
        int minBlockZ = world.chunk().getPos().getMinBlockZ();
        RainbowCakeModel model = null;

        for (int localZ = 0; localZ < 16; localZ++) {
            int z = minBlockZ + localZ;
            for (int localX = 0; localX < 16; localX++) {
                int x = minBlockX + localX;
                int index = (localZ << 4) | localX;
                RainbowCakeModel.CakeSample sample = world.columnCache().cakeSamples[index];
                if (sample == null) {
                    if (model == null) {
                        model = RainbowCakeModel.create(world.samplingContext().worldgenSeed());
                    }
                    sample = model.sample(x, z);
                    world.columnCache().cakeSamples[index] = sample;
                }
                RegionGenerationPipeline<? extends RegionContext> pipeline = pipelinesByRegion[sample.region().ordinal()];
                if (pipeline == null) {
                    continue;
                }

                RegionGenerationTask task = new RegionGenerationTask(
                        world,
                        sample,
                        taskRegionsByCakeRegion[sample.region().ordinal()],
                        localX,
                        localZ,
                        x,
                        z
                );
                runner.accept(pipeline, task);
            }
        }
    }

    private static UndergroundRegion[] createTaskRegionLookup() {
        RainbowCakeModel.CakeRegion[] cakeRegions = RainbowCakeModel.CakeRegion.values();
        UndergroundRegion[] result = new UndergroundRegion[cakeRegions.length];
        for (RainbowCakeModel.CakeRegion region : cakeRegions) {
            result[region.ordinal()] = switch (region) {
                case RUINS -> UndergroundRegion.RUINS;
                case SNOWDIN -> UndergroundRegion.SNOWDIN;
                case WATERFALL -> UndergroundRegion.WATERFALL;
                case HOT_LAND -> UndergroundRegion.HOT_LAND;
                case EDGE_REGION, ORIGIN, TRANSITION_TUNNEL -> UndergroundRegion.DEEP_TUNNEL;
            };
        }
        return result;
    }
}
