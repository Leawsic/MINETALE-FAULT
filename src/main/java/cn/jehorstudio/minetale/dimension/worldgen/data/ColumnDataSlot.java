package cn.jehorstudio.minetale.dimension.worldgen.data;

import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;

public record ColumnDataSlot<T>(
        UndergroundRegion region,
        int index,
        DataKey<T> key
) {}
