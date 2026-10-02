package cn.jehorstudio.minetale.dimension.worldgen.region;

public class GenerationStages {
    private GenerationStages() {}

    public interface Stage<C extends RegionContext> {
        void run(C context);
    }

    public interface StagePass<C extends RegionContext> {
        void apply(C context);
    }

    public interface TerrainStage<C extends RegionContext> extends Stage<C> {}
    public interface ElementStage<C extends RegionContext> extends Stage<C> {}
    public interface MaterialStage<C extends RegionContext> extends Stage<C> {}
    public interface StructureStage<C extends RegionContext> extends Stage<C> {}
    public interface FeatureStage<C extends RegionContext> extends Stage<C> {}
}
