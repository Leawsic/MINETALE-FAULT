package cn.jehorstudio.minetale.dimension.worldgen.network.sample;

import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;

public record RegionSample(
        UndergroundRegion primaryRegion,
        UndergroundRegion secondaryRegion,
        double transitionBlend,
        boolean inTransition,
        double regionPresence
) {}
