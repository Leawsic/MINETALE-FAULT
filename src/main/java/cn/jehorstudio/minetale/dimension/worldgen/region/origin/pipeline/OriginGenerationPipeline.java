package cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.region.GenerationStages;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationPipeline;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationTask;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.feature.OriginFeature;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.structure.OriginLandmarkAnchors;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain.OriginTerrainGenerator;

import java.util.List;

// 固定按 Terrain、Element、Material、Structure、Feature 推进，后阶段依赖前阶段冻结的列事实。
public final class OriginGenerationPipeline implements RegionGenerationPipeline<OriginContext> {
    public static final OriginGenerationPipeline INSTANCE = new OriginGenerationPipeline();

    private final List<GenerationStages.StagePass<OriginContext>> stage1Passes =
            List.of(this::terrainPass);
    private final List<GenerationStages.StagePass<OriginContext>> stage2Passes =
            List.of(this::elementFactsPass);
    private final List<GenerationStages.StagePass<OriginContext>> stage3Passes =
            List.of(this::materialPass);
    private final List<GenerationStages.StagePass<OriginContext>> stage4Passes =
            List.of(this::structureReservationPass);
    private final List<GenerationStages.StagePass<OriginContext>> stage5Passes =
            List.of(this::featureProtectionPass);

    private final GenerationStages.TerrainStage<OriginContext> stage1 =
            context -> runPasses(context, stage1Passes);
    private final GenerationStages.ElementStage<OriginContext> stage2 =
            context -> runPasses(context, stage2Passes);
    private final GenerationStages.MaterialStage<OriginContext> stage3 =
            context -> runPasses(context, stage3Passes);
    private final GenerationStages.StructureStage<OriginContext> stage4 =
            context -> runPasses(context, stage4Passes);
    private final GenerationStages.FeatureStage<OriginContext> stage5 =
            context -> runPasses(context, stage5Passes);

    private OriginGenerationPipeline() {
    }

    @Override
    public RainbowCakeModel.CakeRegion cakeRegion() {
        return RainbowCakeModel.CakeRegion.ORIGIN;
    }

    @Override
    public OriginContext createContext(RegionGenerationTask task) {
        return new OriginContext(task);
    }

    @Override
    public GenerationStages.TerrainStage<OriginContext> stage1() {
        return stage1;
    }

    @Override
    public GenerationStages.ElementStage<OriginContext> stage2() {
        return stage2;
    }

    @Override
    public GenerationStages.MaterialStage<OriginContext> stage3() {
        return stage3;
    }

    @Override
    public GenerationStages.StructureStage<OriginContext> stage4() {
        return stage4;
    }

    @Override
    public GenerationStages.FeatureStage<OriginContext> stage5() {
        return stage5;
    }

    private static void runPasses(
            OriginContext context,
            List<GenerationStages.StagePass<OriginContext>> passes
    ) {
        for (GenerationStages.StagePass<OriginContext> pass : passes) {
            pass.apply(context);
        }
    }

    private void terrainPass(OriginContext context) {
        OriginTerrainGenerator.carveColumn(context, context.facts());
    }

    private void elementFactsPass(OriginContext context) {
        context.facts();
    }

    private void materialPass(OriginContext context) {
        OriginTerrainMaterial.applyColumn(context, context.facts());
    }

    private void structureReservationPass(OriginContext context) {
        OriginLandmarkAnchors.reserve(context);
    }

    private void featureProtectionPass(OriginContext context) {
        OriginFeature.finish(context);
    }
}
