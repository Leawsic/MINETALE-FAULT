package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;

import java.util.OptionalInt;

// 冻结结构与 Feature 写入前的自然列事实，供 Snowdin pipeline 和 Snowtown 规划共同消费。
public record SnowdinColumnFacts(
        RainbowCakeModel.CakeSample cakeSample,
        double floorY,
        double ceilingY,
        double terraceTopY,
        double terraceMask,
        double plateauPresence,
        OptionalInt surfaceY,
        double pillarMaskAtSurface
) {
    public OptionalInt surfaceBlockY() {
        return surfaceY;
    }
}
