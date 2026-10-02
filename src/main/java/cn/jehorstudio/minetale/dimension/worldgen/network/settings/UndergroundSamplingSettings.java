package cn.jehorstudio.minetale.dimension.worldgen.network.settings;

import cn.jehorstudio.minetale.dimension.worldgen.settings.UndergroundWorldgenDefaults;

public record UndergroundSamplingSettings(
        int cellSize,
        double regionBaseRadius,
        double tunnelBaseRadius
) {
    public UndergroundSamplingSettings {
        cellSize = UndergroundWorldgenDefaults.clampCellSize(cellSize);
        regionBaseRadius = UndergroundWorldgenDefaults.clampRoomBaseRadius(regionBaseRadius);
        tunnelBaseRadius = UndergroundWorldgenDefaults.clampTunnelBaseRadius(tunnelBaseRadius);
    }

    public static UndergroundSamplingSettings defaults() {
        return new UndergroundSamplingSettings(
                UndergroundWorldgenDefaults.CELL_SIZE,
                UndergroundWorldgenDefaults.STAGE1_REGION_BASE_RADIUS,
                UndergroundWorldgenDefaults.TUNNEL_BASE_RADIUS
        );
    }
}
