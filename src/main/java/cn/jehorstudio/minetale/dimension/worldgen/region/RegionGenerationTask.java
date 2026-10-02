package cn.jehorstudio.minetale.dimension.worldgen.region;

import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenContext;
import cn.jehorstudio.minetale.dimension.worldgen.network.UndergroundRegion;

// 冻结一次 Region pipeline 调用所需的世界、Chunk、随机源与区域样本。
public final class RegionGenerationTask {
    private final WorldgenContext world;
    private final RainbowCakeModel.CakeSample cakeSample;
    private final UndergroundRegion region;

    private final int localX;
    private final int localZ;
    private final int x;
    private final int z;
    private final int index;

    public RegionGenerationTask(
            WorldgenContext world,
            RainbowCakeModel.CakeSample cakeSample,
            UndergroundRegion region,
            int localX,
            int localZ,
            int x,
            int z
    ){
        this.world=world;
        this.cakeSample=cakeSample;
        this.region=region;
        this.localX=localX;
        this.localZ=localZ;
        this.x=x;
        this.z=z;
        this.index=(localZ<<4)|localX;
    }

    public WorldgenContext world() {
        return world;
    }

    public RainbowCakeModel.CakeSample cakeSample() {
        return cakeSample;
    }

    public RainbowCakeModel.CakeRegion cakeRegion() {
        return cakeSample.region();
    }

    public UndergroundRegion region() {
        return region;
    }

    public int localX() {
        return localX;
    }

    public int localZ() {
        return localZ;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public int index() {
        return index;
    }
}
