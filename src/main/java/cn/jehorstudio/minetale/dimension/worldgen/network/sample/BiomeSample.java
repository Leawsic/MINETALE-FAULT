package cn.jehorstudio.minetale.dimension.worldgen.network.sample;

import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;

public record BiomeSample(
        UndergroundRegion primaryRegion,
        UndergroundRegion secondaryRegion,
        double transitionBlend,
        boolean inTransition,
        boolean insideRoom,
        boolean insideTunnel
) {
    public boolean insideNetwork() {
        return insideRoom || insideTunnel;
    }

    public UndergroundRegion region(double transitionNoise) {
        if (inTransition && transitionNoise < transitionBlend) {
            return secondaryRegion;
        }

        return primaryRegion;
    }
}
