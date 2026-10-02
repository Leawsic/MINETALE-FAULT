package cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline;

import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenContext;
import cn.jehorstudio.minetale.dimension.worldgen.data.DataKey;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationTask;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain.OriginTerrainFactsKernel;

// 拥有 ORIGIN 各阶段共享的列缓存，避免阶段间重复采样或产生分叉。
public record OriginContext(RegionGenerationTask task) implements RegionContext {
    private static final DataKey<OriginColumnFacts> FACTS_KEY =
            DataKey.of("minetale:origin/column_facts", OriginColumnFacts.class);

    public OriginColumnFacts facts() {
        return columnCache().getExtra(region(), index(), FACTS_KEY).orElseGet(() -> {
            OriginColumnFacts facts = OriginTerrainFactsKernel.sample(
                    world().samplingContext().worldgenSeed(),
                    x(),
                    z()
            );
            columnCache().putExtra(region(), index(), FACTS_KEY, facts);
            return facts;
        });
    }

    public ShaftData shaft() {
        return ShaftData.create(
                world().samplingContext().worldgenSeed(),
                ShaftData.DEFAULT_SOURCE_SEAM_Y
        );
    }

    @Override
    public WorldgenContext world() {
        return task.world();
    }

    @Override
    public UndergroundRegion region() {
        return task.region();
    }

    @Override
    public int x() {
        return task.x();
    }

    @Override
    public int z() {
        return task.z();
    }

    @Override
    public int localX() {
        return task.localX();
    }

    @Override
    public int localZ() {
        return task.localZ();
    }

    @Override
    public int index() {
        return task.index();
    }
}
