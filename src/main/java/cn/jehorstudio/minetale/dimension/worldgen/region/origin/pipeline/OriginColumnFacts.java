package cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline;

import cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain.OriginTerrainZone;

// 冻结单列的基础地形事实，供后续材质、结构与 Feature 阶段共享。
public record OriginColumnFacts(
        boolean insideOrigin,
        OriginTerrainZone zone,
        int floorY,
        int ceilingY,
        boolean flowerLandingProtected,
        boolean shaftWallColumn
) {
    public boolean shaftColumn() {
        return zone == OriginTerrainZone.SHAFT;
    }

    public boolean exitColumn() {
        return zone == OriginTerrainZone.EXIT;
    }

    public boolean openColumn() {
        return zone == OriginTerrainZone.CAVERN || exitColumn() || shaftColumn();
    }

    public boolean shouldCarveAir(int y) {
        if (!openColumn() || y <= floorY) {
            return false;
        }
        return shaftColumn() || y <= ceilingY;
    }
}
