package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetCatalog;
import cn.jehorstudio.minetale.dimension.worldgen.generator.UndergroundNoiseGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// 将确定性计划投影为居民导航事实；连通域决定可走格，道路只提供移动偏好。
public final class SnowtownResidentNavigationSource {
    private static final int ENTRANCE_SNAP_RADIUS_CELLS = 6;

    private SnowtownResidentNavigationSource() {}

    public static Optional<AreaSnapshot> load(ServerLevel level, int areaX, int areaZ) {
        if (!isSnowdinArea(areaZ)
                || !(level.getChunkSource().getGenerator() instanceof UndergroundNoiseGenerator generator)) {
            return Optional.empty();
        }

        WorldgenSamplingContext sampling = new WorldgenSamplingContext(
                level.getSeed(),
                generator.samplingSettings()
        );
        SnowtownPlanningArea.Id areaId = new SnowtownPlanningArea.Id(areaX, areaZ);
        SnowtownPlanningArea.Analysis analysis = SnowtownPlanningArea.get(
                sampling,
                areaId,
                level.getMinY(),
                level.getMaxY()
        );
        SnowtownLotPlanner.Plan plan = SnowtownLotPlanner.plan(
                sampling,
                level.getMinY(),
                level.getMaxY(),
                areaId
        );

        Map<Integer, List<SnowtownLotPlanner.PlannedLot>> lotsByComponent = lotsByComponent(plan.lots());
        Set<Long> roadCells = roadCells(plan.roadPlan());
        List<TownSnapshot> towns = new ArrayList<>();
        for (SnowtownPlanningArea.Component component : analysis.components()) {
            List<SnowtownLotPlanner.PlannedLot> lots = lotsByComponent.getOrDefault(
                    component.componentIndex(),
                    List.of()
            );
            TownSnapshot town = buildTown(
                    areaX,
                    areaZ,
                    analysis.bounds(),
                    component,
                    lots,
                    roadCells
            );
            if (!town.cells().isEmpty() && town.entrances().size() >= 2) {
                towns.add(town);
            }
        }

        return Optional.of(new AreaSnapshot(towns));
    }

    public static int areaCoordinate(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, SnowtownSettings.PLANNING_AREA_CORE_SIZE);
    }

    public static boolean isSnowdinArea(int areaZ) {
        return SnowtownLocator.containsSnowdinAreaZ(areaZ);
    }

    public static long currentRevision() {
        return (StructureAssetCatalog.revision() << 32)
                ^ Integer.toUnsignedLong(SnowtownSettings.PLANNING_SETTINGS_VERSION);
    }

    private static TownSnapshot buildTown(
            int areaX,
            int areaZ,
            SnowtownPlanningArea.Bounds areaBounds,
            SnowtownPlanningArea.Component component,
            List<SnowtownLotPlanner.PlannedLot> lots,
            Set<Long> roadCells
    ) {
        Set<Long> blockedCells = blockedCells(lots);
        Map<Long, SnowtownPlanningArea.Cell> availableCells = new HashMap<>();
        List<NavigationCell> cells = new ArrayList<>();
        for (SnowtownPlanningArea.Cell cell : component.cells()) {
            if (!areaBounds.containsCoreCell(cell)) {
                continue;
            }
            long key = cellKey(cell.cellX(), cell.cellZ());
            if (blockedCells.contains(key)) {
                continue;
            }
            availableCells.put(key, cell);
            cells.add(new NavigationCell(
                    cell.cellX(),
                    cell.cellZ(),
                    cell.centerX(),
                    cell.surfaceBlockY() + 1,
                    cell.centerZ(),
                    roadCells.contains(key)
            ));
        }

        List<Entrance> entrances = new ArrayList<>();
        List<BuildingSnapshot> buildings = new ArrayList<>();
        for (SnowtownLotPlanner.PlannedLot lot : lots) {
            BlockPos target = lot.anchorPos().relative(
                    lot.facing(),
                    SnowtownSettings.ROAD_FRONTAGE_OFFSET_BLOCKS
            );
            SnowtownPlanningArea.Cell snapped = snapEntrance(target, availableCells);
            Entrance entrance = null;
            if (snapped != null) {
                entrance = new Entrance(
                        snapped.cellX(),
                        snapped.cellZ()
                );
                entrances.add(entrance);
            }
            var footprint = lot.sample().asset().footprint();
            buildings.add(new BuildingSnapshot(
                    lot.sample().asset().id(),
                    lot.anchorPos().immutable(),
                    footprint.width(),
                    footprint.height(),
                    footprint.depth(),
                    Optional.ofNullable(entrance)
            ));
        }

        return new TownSnapshot(
                new TownKey(areaX, areaZ, component.componentIndex()),
                cells,
                entrances,
                buildings
        );
    }

    private static Map<Integer, List<SnowtownLotPlanner.PlannedLot>> lotsByComponent(
            List<SnowtownLotPlanner.PlannedLot> lots
    ) {
        Map<Integer, List<SnowtownLotPlanner.PlannedLot>> result = new HashMap<>();
        for (SnowtownLotPlanner.PlannedLot lot : lots) {
            result.computeIfAbsent(lot.sample().componentIndex(), ignored -> new ArrayList<>()).add(lot);
        }
        return result;
    }

    private static Set<Long> roadCells(SnowtownRoadPlanner.RoadPlan roadPlan) {
        Set<Long> result = new HashSet<>();
        for (List<SnowtownRoadPlanner.RoadSurfaceBlock> blocks : roadPlan.blocksByChunk().values()) {
            for (SnowtownRoadPlanner.RoadSurfaceBlock block : blocks) {
                result.add(cellKey(
                        Math.floorDiv(block.x(), SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                        Math.floorDiv(block.z(), SnowtownSettings.PLANNING_AREA_CELL_SIZE)
                ));
            }
        }
        return result;
    }

    private static Set<Long> blockedCells(List<SnowtownLotPlanner.PlannedLot> lots) {
        Set<Long> blocked = new HashSet<>();
        int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
        for (SnowtownLotPlanner.PlannedLot lot : lots) {
            BoundingBox bounds = lot.bounds();
            int minCellX = Math.floorDiv(bounds.minX(), cellSize);
            int maxCellX = Math.floorDiv(bounds.maxX(), cellSize);
            int minCellZ = Math.floorDiv(bounds.minZ(), cellSize);
            int maxCellZ = Math.floorDiv(bounds.maxZ(), cellSize);
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    blocked.add(cellKey(cellX, cellZ));
                }
            }
        }
        return blocked;
    }

    private static SnowtownPlanningArea.Cell snapEntrance(
            BlockPos target,
            Map<Long, SnowtownPlanningArea.Cell> availableCells
    ) {
        int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
        int targetCellX = Math.floorDiv(target.getX(), cellSize);
        int targetCellZ = Math.floorDiv(target.getZ(), cellSize);
        SnowtownPlanningArea.Cell best = null;
        long bestDistanceSquared = Long.MAX_VALUE;
        for (int radius = 0; radius <= ENTRANCE_SNAP_RADIUS_CELLS; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    SnowtownPlanningArea.Cell candidate = availableCells.get(cellKey(
                            targetCellX + dx,
                            targetCellZ + dz
                    ));
                    if (candidate == null) {
                        continue;
                    }
                    long deltaX = (long)candidate.centerX() - target.getX();
                    long deltaY = (long)candidate.surfaceBlockY() + 1L - target.getY();
                    long deltaZ = (long)candidate.centerZ() - target.getZ();
                    long distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
                    if (distanceSquared < bestDistanceSquared) {
                        best = candidate;
                        bestDistanceSquared = distanceSquared;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long)cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    public record AreaKey(int areaX, int areaZ) {}

    public record TownKey(int areaX, int areaZ, int componentIndex) {}

    public record AreaSnapshot(List<TownSnapshot> towns) {
        public AreaSnapshot {
            towns = List.copyOf(towns);
        }
    }

    public record TownSnapshot(
            TownKey key,
            List<NavigationCell> cells,
            List<Entrance> entrances,
            List<BuildingSnapshot> buildings
    ) {
        public TownSnapshot {
            cells = List.copyOf(cells);
            entrances = List.copyOf(entrances);
            buildings = List.copyOf(buildings);
        }

        public TownSnapshot(TownKey key, List<NavigationCell> cells, List<Entrance> entrances) {
            this(key, cells, entrances, List.of());
        }
    }

    // 建筑容量从 accepted lot 派生，不扫描运行时方块。
    public record BuildingSnapshot(
            ResourceLocation assetId,
            BlockPos anchor,
            int width,
            int height,
            int depth,
            Optional<Entrance> entrance
    ) {
        public BuildingSnapshot {
            entrance = entrance == null ? Optional.empty() : entrance;
        }
    }

    public record NavigationCell(
            int cellX,
            int cellZ,
            double x,
            double y,
            double z,
            boolean roadPreferred
    ) {}

    public record Entrance(
            int cellX,
            int cellZ
    ) {}
}
