package cn.jehorstudio.minetale.dimension.worldgen.region.edge;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenContext;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGenerationTask;

public record EdgeRegionContext(RegionGenerationTask task) implements RegionContext {
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
