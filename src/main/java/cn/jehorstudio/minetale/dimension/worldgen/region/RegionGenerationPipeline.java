package cn.jehorstudio.minetale.dimension.worldgen.region;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;

// Region 的窄生成边界；实现负责把该 Region 的规则应用到当前任务。
public interface RegionGenerationPipeline<C extends RegionContext> {
    RainbowCakeModel.CakeRegion cakeRegion();

    C createContext(RegionGenerationTask task);

    GenerationStages.TerrainStage<C> stage1();
    GenerationStages.ElementStage<C> stage2();
    GenerationStages.MaterialStage<C> stage3();
    GenerationStages.StructureStage<C> stage4();
    GenerationStages.FeatureStage<C> stage5();

    default void runStage1(RegionGenerationTask task) {
        stage1().run(createContext(task));
    }

    default void runStage2(RegionGenerationTask task) {
        stage2().run(createContext(task));
    }

    default void runStage3(RegionGenerationTask task) {
        stage3().run(createContext(task));
    }

    default void runStage4(RegionGenerationTask task) {
        stage4().run(createContext(task));
    }

    default void runStage5(RegionGenerationTask task) {
        stage5().run(createContext(task));
    }
}
