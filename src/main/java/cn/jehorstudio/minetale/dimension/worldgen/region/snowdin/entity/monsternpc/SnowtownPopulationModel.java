package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.BuildingSnapshot;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.TownSnapshot;

// 唯一拥有 accepted building 容量到宏观人口的换算公式。
final class SnowtownPopulationModel {
    private static final int APPROXIMATE_FLOOR_HEIGHT = 7;
    private static final int MAX_EFFECTIVE_FLOORS = 4;
    private static final int GROSS_FLOOR_AREA_PER_RESIDENT = 64;
    private static final double OUTDOOR_RATIO = 0.70D;

    // GPU 街景只展示户外人口的四分之一
    private static final double VISUAL_AGENT_SCALE = 0.25D;

    private final int buildingCount;
    private final int residentCapacity;

    private SnowtownPopulationModel(int buildingCount, int residentCapacity) {
        this.buildingCount = buildingCount;
        this.residentCapacity = residentCapacity;
    }

    static SnowtownPopulationModel from(TownSnapshot town) {
        long capacity = 0L;
        for (BuildingSnapshot building : town.buildings()) {
            capacity += capacityForFootprint(
                    building.width(),
                    building.height(),
                    building.depth()
            );
        }
        return new SnowtownPopulationModel(
                town.buildings().size(),
                (int)Math.min(Integer.MAX_VALUE, capacity)
        );
    }

    int buildingCount() {
        return this.buildingCount;
    }

    int residentCapacity() {
        return this.residentCapacity;
    }

    int outdoorPopulation() {
        return outdoorPopulation(this.residentCapacity);
    }

    static int outdoorPopulation(int residentCapacity) {
        if (residentCapacity <= 0) {
            return 0;
        }
        int unscaledPopulation = Math.max(
                1,
                (int)Math.round(residentCapacity * OUTDOOR_RATIO));
        return Math.max(
                1,
                (int)Math.round(unscaledPopulation * VISUAL_AGENT_SCALE));
    }

    static int capacityForFootprint(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("建筑 footprint 尺寸必须全部大于 0");
        }
        int floors = Math.clamp(
                (height + APPROXIMATE_FLOOR_HEIGHT - 1) / APPROXIMATE_FLOOR_HEIGHT,
                1,
                MAX_EFFECTIVE_FLOORS
        );
        long grossFloorArea = (long)width * depth * floors;
        return (int)Math.max(1L, Math.min(
                Integer.MAX_VALUE,
                (grossFloorArea + GROSS_FLOOR_AREA_PER_RESIDENT - 1L)
                        / GROSS_FLOOR_AREA_PER_RESIDENT
        ));
    }
}
