package cn.jehorstudio.minetale.dimension.worldgen.region;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenContext;
import cn.jehorstudio.minetale.dimension.worldgen.data.ColumnCache;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;

public interface RegionContext {
    WorldgenContext world();

    UndergroundRegion region();

    int x();

    int z();

    int localX();

    int localZ();

    int index();

    default ColumnCache columnCache() {
        return world().columnCache();
    }
}
