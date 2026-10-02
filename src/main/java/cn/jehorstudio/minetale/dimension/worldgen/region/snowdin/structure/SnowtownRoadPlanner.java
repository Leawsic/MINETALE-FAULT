package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// 先连接高优先级 terminal 形成骨架，再以受限支路补齐并栅格化道路。
final class SnowtownRoadPlanner {
    private static final int BUILDING_TILE_SIZE = 16;
    private static final int NEAR_BUILDING_RADIUS_BLOCKS = 5;
    private static final int MIN_VISIBLE_ROAD_COMPONENT_BLOCKS = 10;
    private static final int UNREACHABLE = Integer.MAX_VALUE;
    private static final int[][] NEIGHBORS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1},
            {1, 1},
            {1, -1},
            {-1, 1},
            {-1, -1}
    };

    private SnowtownRoadPlanner() {}

    static RoadPlan plan(
            SnowtownPlanningArea.Analysis analysis,
            List<LotInput> acceptedLots,
            SnowtownTerrainGrid terraces
    ) {
        if (acceptedLots.isEmpty() || analysis.components().isEmpty()) {
            return RoadPlan.empty();
        }
        return new Planner(analysis, acceptedLots, terraces).plan();
    }

    record LotInput(
            int componentIndex,
            BlockPos anchor,
            Direction facing,
            BoundingBox bounds
    ) {
        int footprintArea() {
            return (bounds.maxX() - bounds.minX() + 1) * (bounds.maxZ() - bounds.minZ() + 1);
        }
    }

    record RoadSurfaceBlock(int x, int y, int z) {}

    record RoadPlan(
            Map<Long, List<RoadSurfaceBlock>> blocksByChunk,
            int roadBlockCount,
            RoadStats stats
    ) {
        static RoadPlan empty() {
            return new RoadPlan(Map.of(), 0, RoadStats.empty());
        }

        List<RoadSurfaceBlock> roadBlocksForChunk(ChunkAccess chunk) {
            int chunkX = Math.floorDiv(chunk.getPos().getMinBlockX(), 16);
            int chunkZ = Math.floorDiv(chunk.getPos().getMinBlockZ(), 16);
            return blocksByChunk.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
        }
    }

    record RoadStats(
            int components,
            int terminals,
            int snappedTerminals,
            int navigableRegions,
            int backboneCandidates,
            int routeTrees,
            int settledCells,
            int landmarkTerminals,
            int snappedLandmarks,
            int connectedLandmarks,
            int regularTerminals,
            int snappedRegulars,
            int connectedRegulars,
            int infillTerminals,
            int snappedInfills,
            int connectedInfills,
            int connectorBranches,
            int maxRegularConnectorCells,
            int maxInfillConnectorCells,
            int roadCells,
            int roadBlocks,
            long gridNanos,
            long selectionNanos,
            long routeNanos,
            long compileNanos
    ) {
        static RoadStats empty() {
            return new MutableStats().freeze();
        }

        String summary() {
            return "components=" + components
                    + ", terminals=" + terminals
                    + ", snapped=" + snappedTerminals
                    + ", regions=" + navigableRegions
                    + ", backboneCandidates=" + backboneCandidates
                    + ", routeTrees=" + routeTrees
                    + ", settledCells=" + settledCells
                    + ", landmarks[connected/snapped/total]="
                    + connectedLandmarks + '/' + snappedLandmarks + '/' + landmarkTerminals
                    + ", regulars[connected/snapped/total]="
                    + connectedRegulars + '/' + snappedRegulars + '/' + regularTerminals
                    + ", infills[connected/snapped/total]="
                    + connectedInfills + '/' + snappedInfills + '/' + infillTerminals
                    + ", connectorBranches=" + connectorBranches
                    + ", maxConnectorCells[regular=" + maxRegularConnectorCells
                    + ", infill=" + maxInfillConnectorCells + ']'
                    + ", roadCells=" + roadCells
                    + ", roadBlocks=" + roadBlocks
                    + ", gridMs=" + nanosToMillis(gridNanos)
                    + ", selectionMs=" + nanosToMillis(selectionNanos)
                    + ", routeMs=" + nanosToMillis(routeNanos)
                    + ", compileMs=" + nanosToMillis(compileNanos);
        }
    }

    private static final class Planner {
        private static final Comparator<DoorTerminal> TERMINAL_PRIORITY = Comparator
                .comparingInt((DoorTerminal terminal) -> terminal.tier().priority())
                .thenComparing(Comparator.comparingInt(DoorTerminal::area).reversed())
                .thenComparingInt(DoorTerminal::cellZ)
                .thenComparingInt(DoorTerminal::cellX)
                .thenComparingInt(DoorTerminal::doorZ)
                .thenComparingInt(DoorTerminal::doorX)
                .thenComparingInt(DoorTerminal::id);

        private final SnowtownPlanningArea.Analysis analysis;
        private final SnowtownPlanningArea.Bounds bounds;
        private final List<LotInput> acceptedLots;
        private final SnowtownTerrainGrid terraces;
        private final Map<Long, RoadSurfaceBlock> roadBlocks = new HashMap<>();
        private final MutableStats stats = new MutableStats();

        private Planner(
                SnowtownPlanningArea.Analysis analysis,
                List<LotInput> acceptedLots,
                SnowtownTerrainGrid terraces
        ) {
            this.analysis = analysis;
            this.bounds = analysis.bounds();
            this.acceptedLots = acceptedLots;
            this.terraces = terraces;
        }

        private RoadPlan plan() {
            long gridStartedAt = System.nanoTime();
            RoadGrid grid = RoadGrid.of(bounds, analysis.components(), acceptedLots);
            List<DoorTerminal> terminals = doorTerminals(grid);
            Map<Integer, List<DoorTerminal>> terminalsByRegion = terminalsByRegion(terminals);
            stats.gridNanos = System.nanoTime() - gridStartedAt;
            stats.components = distinctLotComponents();
            stats.terminals = terminals.size();
            stats.navigableRegions = grid.regionCount();

            RoadNetwork network = new RoadNetwork(grid.cellCount(), grid.width());
            List<Integer> regionIds = terminalsByRegion.keySet().stream().sorted().toList();
            for (int regionId : regionIds) {
                planRegion(grid, network, regionId, terminalsByRegion.get(regionId));
            }

            long compileStartedAt = System.nanoTime();
            compileNetwork(grid, network);
            removeTinyRoadComponents();
            Map<Long, List<RoadSurfaceBlock>> byChunk = blocksByChunk();
            stats.compileNanos = System.nanoTime() - compileStartedAt;
            stats.roadCells = network.roadCellCount();
            stats.roadBlocks = roadBlocks.size();
            return new RoadPlan(byChunk, roadBlocks.size(), stats.freeze());
        }

        private int distinctLotComponents() {
            Set<Integer> components = new HashSet<>();
            for (LotInput lot : acceptedLots) {
                components.add(lot.componentIndex());
            }
            return components.size();
        }

        private List<DoorTerminal> doorTerminals(RoadGrid grid) {
            List<DoorTerminal> result = new ArrayList<>(acceptedLots.size());
            for (int id = 0; id < acceptedLots.size(); id++) {
                LotInput lot = acceptedLots.get(id);
                int doorX = lot.anchor().getX()
                        + lot.facing().getStepX() * SnowtownSettings.ROAD_FRONTAGE_OFFSET_BLOCKS;
                int doorZ = lot.anchor().getZ()
                        + lot.facing().getStepZ() * SnowtownSettings.ROAD_FRONTAGE_OFFSET_BLOCKS;
                int cellIndex = snapDoor(grid, lot.componentIndex(), doorX, doorZ);
                LotTier tier = LotTier.forArea(lot.footprintArea());
                switch (tier) {
                    case LANDMARK -> stats.landmarkTerminals++;
                    case REGULAR -> stats.regularTerminals++;
                    case INFILL -> stats.infillTerminals++;
                }
                if (cellIndex >= 0) {
                    stats.snappedTerminals++;
                    switch (tier) {
                        case LANDMARK -> stats.snappedLandmarks++;
                        case REGULAR -> stats.snappedRegulars++;
                        case INFILL -> stats.snappedInfills++;
                    }
                }
                result.add(new DoorTerminal(
                        id,
                        doorX,
                        doorZ,
                        cellIndex,
                        cellIndex < 0 ? -1 : grid.regionAt(cellIndex),
                        tier,
                        lot.footprintArea(),
                        cellIndex < 0 ? Integer.MAX_VALUE : grid.cell(cellIndex).cellX(),
                        cellIndex < 0 ? Integer.MAX_VALUE : grid.cell(cellIndex).cellZ()
                ));
            }
            return result;
        }

        private int snapDoor(RoadGrid grid, int componentIndex, int doorX, int doorZ) {
            int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
            int doorCellX = Math.floorDiv(doorX, cellSize);
            int doorCellZ = Math.floorDiv(doorZ, cellSize);
            int bestIndex = -1;
            int bestDistance = Integer.MAX_VALUE;
            int radius = SnowtownSettings.ROAD_DOOR_SNAP_RADIUS_CELLS;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int index = grid.index(doorCellX + dx, doorCellZ + dz);
                    if (index < 0 || !grid.passable(index) || grid.componentAt(index) != componentIndex) {
                        continue;
                    }
                    SnowtownPlanningArea.Cell cell = grid.cell(index);
                    int distance = horizontalDistanceSquared(doorX, doorZ, cell.centerX(), cell.centerZ());
                    if (distance < bestDistance
                            || distance == bestDistance
                            && (bestIndex < 0
                            || cell.cellZ() < grid.cell(bestIndex).cellZ()
                            || cell.cellZ() == grid.cell(bestIndex).cellZ()
                            && cell.cellX() < grid.cell(bestIndex).cellX())) {
                        bestIndex = index;
                        bestDistance = distance;
                    }
                }
            }
            return bestIndex;
        }

        private static Map<Integer, List<DoorTerminal>> terminalsByRegion(List<DoorTerminal> terminals) {
            Map<Integer, List<DoorTerminal>> result = new HashMap<>();
            for (DoorTerminal terminal : terminals) {
                if (terminal.cellIndex() >= 0) {
                    result.computeIfAbsent(terminal.regionId(), ignored -> new ArrayList<>()).add(terminal);
                }
            }
            return result;
        }

        private void planRegion(
                RoadGrid grid,
                RoadNetwork network,
                int regionId,
                List<DoorTerminal> regionTerminals
        ) {
            DoorTerminal root = regionTerminals.stream()
                    .filter(terminal -> terminal.tier() != LotTier.INFILL)
                    .min(TERMINAL_PRIORITY)
                    .orElse(null);
            if (root == null) {
                return;
            }

            long selectionStartedAt = System.nanoTime();
            List<DoorTerminal> backbone = backboneTerminals(grid, regionTerminals, root);
            stats.selectionNanos += System.nanoTime() - selectionStartedAt;
            stats.backboneCandidates += backbone.size();

            boolean[] connected = new boolean[acceptedLots.size()];
            network.markCell(root.cellIndex(), SnowtownSettings.ROAD_MAIN_WIDTH);
            markConnected(root, connected, false, 0);
            if (regionTerminals.size() == 1) {
                return;
            }

            long routeStartedAt = System.nanoTime();
            RouteTree tree = RouteTree.build(grid, regionId, root.cellIndex(), regionTerminals);
            stats.routeTrees++;
            stats.settledCells += tree.settledCells();

            for (DoorTerminal terminal : backbone) {
                if (connected[terminal.id()]) {
                    continue;
                }
                Branch branch = tree.branchTo(network, terminal.cellIndex());
                if (!branch.reachable()
                        || terminal.tier() != LotTier.LANDMARK
                        && !withinRouteBudget(branch, regularBackboneCellLimit(terminal.area()))) {
                    continue;
                }
                network.addBranch(branch, SnowtownSettings.ROAD_MAIN_WIDTH);
                markConnected(terminal, connected, false, branch.newCells());
            }

            List<DoorTerminal> optional = regionTerminals.stream()
                    .filter(terminal -> !connected[terminal.id()])
                    .sorted(TERMINAL_PRIORITY)
                    .toList();
            for (DoorTerminal terminal : optional) {
                Branch branch = tree.branchTo(network, terminal.cellIndex());
                if (!branch.reachable()) {
                    continue;
                }
                int limit = switch (terminal.tier()) {
                    case LANDMARK -> Integer.MAX_VALUE;
                    case REGULAR -> regularConnectorCellLimit(terminal.area());
                    case INFILL -> SnowtownSettings.ROAD_INFILL_CONNECTOR_MAX_NEW_CELLS;
                };
                if (terminal.tier() != LotTier.LANDMARK) {
                    if (!withinRouteBudget(branch, limit) || !hasOnlyCardinalEdges(grid, branch)) {
                        continue;
                    }
                }
                network.addBranch(branch, SnowtownSettings.ROAD_CONNECTOR_WIDTH);
                markConnected(terminal, connected, branch.newCells() > 0, branch.newCells());
            }
            stats.routeNanos += System.nanoTime() - routeStartedAt;
        }

        private List<DoorTerminal> backboneTerminals(
                RoadGrid grid,
                List<DoorTerminal> terminals,
                DoorTerminal root
        ) {
            List<DoorTerminal> selected = new ArrayList<>();
            selected.add(root);
            terminals.stream()
                    .filter(terminal -> terminal.tier() == LotTier.LANDMARK)
                    .sorted(TERMINAL_PRIORITY)
                    .forEach(terminal -> addUniqueCell(selected, terminal));

            Map<Long, DoorTerminal> bestRegularByBucket = new HashMap<>();
            int bucketSize = SnowtownSettings.ROAD_BUCKET_SIZE_BLOCKS;
            for (DoorTerminal terminal : terminals) {
                if (terminal.tier() != LotTier.REGULAR) {
                    continue;
                }
                SnowtownPlanningArea.Cell cell = grid.cell(terminal.cellIndex());
                long bucketKey = tileKey(
                        Math.floorDiv(cell.centerX(), bucketSize),
                        Math.floorDiv(cell.centerZ(), bucketSize)
                );
                DoorTerminal existing = bestRegularByBucket.get(bucketKey);
                if (existing == null || TERMINAL_PRIORITY.compare(terminal, existing) < 0) {
                    bestRegularByBucket.put(bucketKey, terminal);
                }
            }

            List<DoorTerminal> candidates = new ArrayList<>(bestRegularByBucket.values());
            candidates.removeIf(terminal -> containsCell(selected, terminal.cellIndex()));
            candidates.sort(TERMINAL_PRIORITY);
            int max = Math.max(
                    SnowtownSettings.ROAD_MAX_BACKBONE_TERMINALS_PER_REGION,
                    selected.size()
            );
            while (selected.size() < max && !candidates.isEmpty()) {
                DoorTerminal best = candidates.stream()
                        .max(Comparator
                                .comparingLong((DoorTerminal terminal) -> spatialValue(grid, terminal, selected))
                                .thenComparing(TERMINAL_PRIORITY.reversed()))
                        .orElseThrow();
                selected.add(best);
                candidates.remove(best);
            }
            return List.copyOf(selected);
        }

        private static long spatialValue(
                RoadGrid grid,
                DoorTerminal terminal,
                List<DoorTerminal> selected
        ) {
            SnowtownPlanningArea.Cell cell = grid.cell(terminal.cellIndex());
            long nearestDistanceSquared = Long.MAX_VALUE;
            for (DoorTerminal other : selected) {
                SnowtownPlanningArea.Cell otherCell = grid.cell(other.cellIndex());
                long dx = cell.cellX() - otherCell.cellX();
                long dz = cell.cellZ() - otherCell.cellZ();
                nearestDistanceSquared = Math.min(nearestDistanceSquared, dx * dx + dz * dz);
            }
            return nearestDistanceSquared * terminal.area();
        }

        private static void addUniqueCell(List<DoorTerminal> terminals, DoorTerminal candidate) {
            if (!containsCell(terminals, candidate.cellIndex())) {
                terminals.add(candidate);
            }
        }

        private static boolean containsCell(List<DoorTerminal> terminals, int cellIndex) {
            for (DoorTerminal terminal : terminals) {
                if (terminal.cellIndex() == cellIndex) {
                    return true;
                }
            }
            return false;
        }

        private static boolean withinRouteBudget(Branch branch, int cellLimit) {
            if (branch.newCells() > cellLimit) {
                return false;
            }
            return branch.cost() <= cellLimit * SnowtownSettings.ROAD_MAX_ROUTE_COST_PER_CELL;
        }

        private static boolean hasOnlyCardinalEdges(RoadGrid grid, Branch branch) {
            int next = branch.joinCell();
            List<Integer> cells = branch.cellsFromTerminal();
            for (int index = cells.size() - 1; index >= 0; index--) {
                int cell = cells.get(index);
                if (grid.isDiagonalEdge(cell, next)) {
                    return false;
                }
                next = cell;
            }
            return true;
        }

        private static int regularBackboneCellLimit(int area) {
            return scaledRegularLimit(
                    area,
                    SnowtownSettings.ROAD_REGULAR_BACKBONE_MIN_NEW_CELLS,
                    SnowtownSettings.ROAD_REGULAR_BACKBONE_MAX_NEW_CELLS
            );
        }

        private static int regularConnectorCellLimit(int area) {
            return scaledRegularLimit(
                    area,
                    SnowtownSettings.ROAD_REGULAR_CONNECTOR_MIN_NEW_CELLS,
                    SnowtownSettings.ROAD_REGULAR_CONNECTOR_MAX_NEW_CELLS
            );
        }

        private static int scaledRegularLimit(int area, int min, int max) {
            int regularMinArea = SnowtownSettings.LOT_PACKING_INFILL_MAX_AREA + 1;
            int regularMaxArea = SnowtownSettings.LOT_PACKING_LANDMARK_MIN_AREA - 1;
            int clampedArea = Math.max(regularMinArea, Math.min(regularMaxArea, area));
            return min + (clampedArea - regularMinArea) * (max - min)
                    / Math.max(1, regularMaxArea - regularMinArea);
        }

        private void markConnected(
                DoorTerminal terminal,
                boolean[] connected,
                boolean connector,
                int connectorCells
        ) {
            if (connected[terminal.id()]) {
                return;
            }
            connected[terminal.id()] = true;
            switch (terminal.tier()) {
                case LANDMARK -> stats.connectedLandmarks++;
                case REGULAR -> {
                    stats.connectedRegulars++;
                    if (connector) {
                        stats.maxRegularConnectorCells = Math.max(
                                stats.maxRegularConnectorCells,
                                connectorCells
                        );
                    }
                }
                case INFILL -> {
                    stats.connectedInfills++;
                    if (connector) {
                        stats.maxInfillConnectorCells = Math.max(
                                stats.maxInfillConnectorCells,
                                connectorCells
                        );
                    }
                }
            }
            if (connector) {
                stats.connectorBranches++;
            }
        }

        private void compileNetwork(RoadGrid grid, RoadNetwork network) {
            for (int index = 0; index < grid.cellCount(); index++) {
                int width = network.width(index);
                if (width > 0) {
                    SnowtownPlanningArea.Cell cell = grid.cell(index);
                    compileBrush(
                            grid,
                            grid.componentAt(index),
                            cell.centerX(),
                            cell.centerZ(),
                            width
                    );
                }
            }
            for (int index = 0; index < grid.cellCount(); index++) {
                int edgeMask = network.edgeMask(index);
                if (edgeMask == 0) {
                    continue;
                }
                for (int direction = 0; direction < NEIGHBORS.length; direction++) {
                    if ((edgeMask & (1 << direction)) == 0) {
                        continue;
                    }
                    int neighbor = grid.neighbor(index, NEIGHBORS[direction][0], NEIGHBORS[direction][1]);
                    if (neighbor <= index) {
                        continue;
                    }
                    rasterizeSegment(
                            grid,
                            grid.componentAt(index),
                            grid.cell(index),
                            grid.cell(neighbor),
                            Math.max(network.width(index), network.width(neighbor))
                    );
                }
            }
        }

        private void rasterizeSegment(
                RoadGrid grid,
                int componentIndex,
                SnowtownPlanningArea.Cell a,
                SnowtownPlanningArea.Cell b,
                int width
        ) {
            int x0 = a.centerX();
            int z0 = a.centerZ();
            int x1 = b.centerX();
            int z1 = b.centerZ();
            int dx = Math.abs(x1 - x0);
            int dz = Math.abs(z1 - z0);
            int sx = x0 < x1 ? 1 : -1;
            int sz = z0 < z1 ? 1 : -1;
            int error = dx - dz;
            int x = x0;
            int z = z0;
            while (true) {
                compileBrush(grid, componentIndex, x, z, width);
                if (x == x1 && z == z1) {
                    break;
                }
                int doubledError = 2 * error;
                if (doubledError > -dz) {
                    error -= dz;
                    x += sx;
                }
                if (doubledError < dx) {
                    error += dx;
                    z += sz;
                }
            }
        }

        private void compileBrush(
                RoadGrid grid,
                int componentIndex,
                int centerX,
                int centerZ,
                int width
        ) {
            int radius = Math.max(0, width / 2);
            int radiusSquared = radius * radius;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (radius > 0 && dx * dx + dz * dz > radiusSquared + radius) {
                        continue;
                    }
                    addRoadBlock(grid, componentIndex, centerX + dx, centerZ + dz);
                }
            }
        }

        private void addRoadBlock(RoadGrid grid, int componentIndex, int x, int z) {
            if (!bounds.containsCoreBlock(x, z)) {
                return;
            }
            long blockKey = blockColumnKey(x, z);
            if (roadBlocks.containsKey(blockKey)) {
                return;
            }
            int cellIndex = grid.index(
                    Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                    Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE)
            );
            if (cellIndex < 0
                    || grid.cell(cellIndex) == null
                    || grid.componentAt(cellIndex) != componentIndex) {
                return;
            }
            SnowtownPlanningArea.Cell cell = grid.cell(cellIndex);
            if (grid.buildingIndex().inside(x, z, grid.componentAt(cellIndex))
                    || !terraces.pointFits(cell.centerX(), cell.centerZ(), x, z)) {
                return;
            }
            roadBlocks.put(blockKey, new RoadSurfaceBlock(x, cell.surfaceBlockY(), z));
        }

        private Map<Long, List<RoadSurfaceBlock>> blocksByChunk() {
            Map<Long, List<RoadSurfaceBlock>> result = new HashMap<>();
            for (RoadSurfaceBlock block : roadBlocks.values()) {
                long chunkKey = chunkKey(Math.floorDiv(block.x(), 16), Math.floorDiv(block.z(), 16));
                result.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(block);
            }
            Map<Long, List<RoadSurfaceBlock>> immutable = new HashMap<>();
            for (Map.Entry<Long, List<RoadSurfaceBlock>> entry : result.entrySet()) {
                entry.getValue().sort(Comparator
                        .comparingInt(RoadSurfaceBlock::z)
                        .thenComparingInt(RoadSurfaceBlock::x)
                        .thenComparingInt(RoadSurfaceBlock::y));
                immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(immutable);
        }

        // 地形裁剪留下的单 brush 孤岛不具道路语义，应从最终栅格移除。
        private void removeTinyRoadComponents() {
            Set<Long> visited = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            List<Long> possibleRemoval = new ArrayList<>(MIN_VISIBLE_ROAD_COMPONENT_BLOCKS - 1);
            List<Long> removals = new ArrayList<>();
            for (long start : roadBlocks.keySet()) {
                if (!visited.add(start)) {
                    continue;
                }
                queue.addLast(start);
                possibleRemoval.clear();
                int componentSize = 0;
                while (!queue.isEmpty()) {
                    long current = queue.removeFirst();
                    componentSize++;
                    if (componentSize < MIN_VISIBLE_ROAD_COMPONENT_BLOCKS) {
                        possibleRemoval.add(current);
                    } else if (componentSize == MIN_VISIBLE_ROAD_COMPONENT_BLOCKS) {
                        possibleRemoval.clear();
                    }
                    int x = (int) (current >> 32);
                    int z = (int) current;
                    enqueueRoadNeighbor(x + 1, z, visited, queue);
                    enqueueRoadNeighbor(x - 1, z, visited, queue);
                    enqueueRoadNeighbor(x, z + 1, visited, queue);
                    enqueueRoadNeighbor(x, z - 1, visited, queue);
                }
                if (componentSize < MIN_VISIBLE_ROAD_COMPONENT_BLOCKS) {
                    removals.addAll(possibleRemoval);
                }
            }
            for (long key : removals) {
                roadBlocks.remove(key);
            }
        }

        private void enqueueRoadNeighbor(
                int x,
                int z,
                Set<Long> visited,
                ArrayDeque<Long> queue
        ) {
            long key = blockColumnKey(x, z);
            if (roadBlocks.containsKey(key) && visited.add(key)) {
                queue.addLast(key);
            }
        }
    }

    private static final class RoadGrid {
        private final int minCellX;
        private final int minCellZ;
        private final int width;
        private final int depth;
        private final SnowtownPlanningArea.Cell[] cells;
        private final int[] componentByCell;
        private final boolean[] passable;
        private final int[] regionByCell;
        private final int[] nearBuildingPenalty;
        private final BuildingIndex buildingIndex;
        private int regionCount;

        private RoadGrid(
                SnowtownPlanningArea.Bounds bounds,
                List<SnowtownPlanningArea.Component> components,
                List<LotInput> lots
        ) {
            int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
            this.minCellX = Math.floorDiv(bounds.coreMinX(), cellSize);
            this.minCellZ = Math.floorDiv(bounds.coreMinZ(), cellSize);
            this.width = (bounds.coreMaxXExclusive() - bounds.coreMinX()) / cellSize;
            this.depth = (bounds.coreMaxZExclusive() - bounds.coreMinZ()) / cellSize;
            this.cells = new SnowtownPlanningArea.Cell[width * depth];
            this.componentByCell = new int[cells.length];
            this.passable = new boolean[cells.length];
            this.regionByCell = new int[cells.length];
            this.nearBuildingPenalty = new int[cells.length];
            Arrays.fill(componentByCell, -1);
            Arrays.fill(regionByCell, -1);
            Arrays.fill(nearBuildingPenalty, -1);
            this.buildingIndex = BuildingIndex.of(lots);

            for (SnowtownPlanningArea.Component component : components) {
                for (SnowtownPlanningArea.Cell cell : component.cells()) {
                    int index = index(cell.cellX(), cell.cellZ());
                    if (index < 0) {
                        continue;
                    }
                    cells[index] = cell;
                    componentByCell[index] = component.componentIndex();
                }
            }
            for (int index = 0; index < cells.length; index++) {
                SnowtownPlanningArea.Cell cell = cells[index];
                passable[index] = cell != null
                        && cell.terraceMask() >= SnowtownSettings.CELL_TERRACE_MASK_MIN
                        && cell.pillarMaskAtSurface() <= SnowtownSettings.CELL_PILLAR_MASK_MAX
                        && !buildingIndex.inside(cell.centerX(), cell.centerZ(), componentByCell[index]);
            }
            labelRegions();
        }

        static RoadGrid of(
                SnowtownPlanningArea.Bounds bounds,
                List<SnowtownPlanningArea.Component> components,
                List<LotInput> lots
        ) {
            return new RoadGrid(bounds, components, lots);
        }

        int cellCount() {
            return cells.length;
        }

        int width() {
            return width;
        }

        int regionCount() {
            return regionCount;
        }

        SnowtownPlanningArea.Cell cell(int index) {
            return cells[index];
        }

        int componentAt(int index) {
            return componentByCell[index];
        }

        int regionAt(int index) {
            return regionByCell[index];
        }

        boolean passable(int index) {
            return index >= 0 && passable[index];
        }

        BuildingIndex buildingIndex() {
            return buildingIndex;
        }

        int index(int cellX, int cellZ) {
            int localX = cellX - minCellX;
            int localZ = cellZ - minCellZ;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth) {
                return -1;
            }
            return localZ * width + localX;
        }

        int neighbor(int index, int dx, int dz) {
            int localX = index % width + dx;
            int localZ = index / width + dz;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth) {
                return -1;
            }
            return localZ * width + localX;
        }

        boolean isDiagonalEdge(int first, int second) {
            return first % width != second % width && first / width != second / width;
        }

        boolean canMove(int current, int next, int dx, int dz) {
            if (!passable(next) || componentByCell[current] != componentByCell[next]) {
                return false;
            }
            SnowtownPlanningArea.Cell currentCell = cells[current];
            SnowtownPlanningArea.Cell nextCell = cells[next];
            if (Math.abs(nextCell.surfaceBlockY() - currentCell.surfaceBlockY()) > 2) {
                return false;
            }
            if (dx == 0 || dz == 0) {
                return true;
            }
            int cornerA = neighbor(current, dx, 0);
            int cornerB = neighbor(current, 0, dz);
            return passable(cornerA)
                    && passable(cornerB)
                    && componentByCell[cornerA] == componentByCell[current]
                    && componentByCell[cornerB] == componentByCell[current]
                    && Math.abs(cells[cornerA].surfaceBlockY() - currentCell.surfaceBlockY()) <= 2
                    && Math.abs(cells[cornerB].surfaceBlockY() - currentCell.surfaceBlockY()) <= 2;
        }

        int moveCost(int current, int next, int dx, int dz) {
            SnowtownPlanningArea.Cell currentCell = cells[current];
            SnowtownPlanningArea.Cell nextCell = cells[next];
            int dy = Math.abs(nextCell.surfaceBlockY() - currentCell.surfaceBlockY());
            int cost = dx != 0 && dz != 0 ? 14 : 10;
            if (dy == 1) {
                cost += 18;
            } else if (dy == 2) {
                cost += 60;
            }
            cost += (int) Math.round(nextCell.pillarMaskAtSurface() * 40.0);
            cost += (int) Math.round((1.0 - nextCell.terraceMask()) * 20.0);
            cost += nearBuildingPenalty(next);
            return cost;
        }

        private int nearBuildingPenalty(int index) {
            int cached = nearBuildingPenalty[index];
            if (cached >= 0) {
                return cached;
            }
            SnowtownPlanningArea.Cell cell = cells[index];
            int computed = buildingIndex.nearPenalty(
                    cell.centerX(),
                    cell.centerZ(),
                    componentByCell[index]
            );
            nearBuildingPenalty[index] = computed;
            return computed;
        }

        private void labelRegions() {
            int[] queue = new int[cells.length];
            for (int start = 0; start < cells.length; start++) {
                if (!passable[start] || regionByCell[start] >= 0) {
                    continue;
                }
                int head = 0;
                int tail = 0;
                queue[tail++] = start;
                regionByCell[start] = regionCount;
                while (head < tail) {
                    int current = queue[head++];
                    for (int[] direction : NEIGHBORS) {
                        int next = neighbor(current, direction[0], direction[1]);
                        if (next < 0
                                || regionByCell[next] >= 0
                                || !canMove(current, next, direction[0], direction[1])) {
                            continue;
                        }
                        regionByCell[next] = regionCount;
                        queue[tail++] = next;
                    }
                }
                regionCount++;
            }
        }
    }

    private static final class RouteTree {
        private final int[] distance;
        private final int[] parent;
        private final int settledCells;

        private RouteTree(int[] distance, int[] parent, int settledCells) {
            this.distance = distance;
            this.parent = parent;
            this.settledCells = settledCells;
        }

        static RouteTree build(
                RoadGrid grid,
                int regionId,
                int root,
                List<DoorTerminal> terminals
        ) {
            int[] distance = new int[grid.cellCount()];
            int[] parent = new int[grid.cellCount()];
            boolean[] target = new boolean[grid.cellCount()];
            Arrays.fill(distance, UNREACHABLE);
            Arrays.fill(parent, -1);
            int remainingTargets = 0;
            for (DoorTerminal terminal : terminals) {
                int index = terminal.cellIndex();
                if (!target[index]) {
                    target[index] = true;
                    remainingTargets++;
                }
            }

            IntMinHeap open = new IntMinHeap(Math.max(16, remainingTargets * 4));
            distance[root] = 0;
            parent[root] = root;
            open.add(root, 0);
            int settledCells = 0;
            while (!open.isEmpty() && remainingTargets > 0) {
                int current = open.peekNode();
                int currentDistance = open.peekPriority();
                open.removeFirst();
                if (currentDistance != distance[current]) {
                    continue;
                }
                settledCells++;
                if (target[current]) {
                    target[current] = false;
                    remainingTargets--;
                }
                for (int[] direction : NEIGHBORS) {
                    int next = grid.neighbor(current, direction[0], direction[1]);
                    if (next < 0
                            || grid.regionAt(next) != regionId
                            || !grid.canMove(current, next, direction[0], direction[1])) {
                        continue;
                    }
                    int nextDistance = currentDistance
                            + grid.moveCost(current, next, direction[0], direction[1]);
                    if (nextDistance >= distance[next]) {
                        continue;
                    }
                    distance[next] = nextDistance;
                    parent[next] = current;
                    open.add(next, nextDistance);
                }
            }
            return new RouteTree(distance, parent, settledCells);
        }

        int settledCells() {
            return settledCells;
        }

        Branch branchTo(RoadNetwork network, int start) {
            if (start < 0 || distance[start] == UNREACHABLE) {
                return Branch.unreachable();
            }
            List<Integer> newCells = new ArrayList<>();
            int cost = 0;
            int cursor = start;
            while (!network.hasCell(cursor)) {
                int previous = parent[cursor];
                if (previous < 0 || previous == cursor) {
                    return Branch.unreachable();
                }
                newCells.add(cursor);
                cost += distance[cursor] - distance[previous];
                cursor = previous;
            }
            return new Branch(List.copyOf(newCells), cursor, cost, true);
        }
    }

    private static final class RoadNetwork {
        private final byte[] widths;
        private final int[] edgeMasks;
        private final int gridWidth;
        private int roadCellCount;

        private RoadNetwork(int cellCount, int gridWidth) {
            this.widths = new byte[cellCount];
            this.edgeMasks = new int[cellCount];
            this.gridWidth = gridWidth;
        }

        boolean hasCell(int index) {
            return index >= 0 && widths[index] > 0;
        }

        int width(int index) {
            return Byte.toUnsignedInt(widths[index]);
        }

        int edgeMask(int index) {
            return edgeMasks[index];
        }

        int roadCellCount() {
            return roadCellCount;
        }

        void markCell(int index, int width) {
            if (widths[index] == 0) {
                roadCellCount++;
            }
            widths[index] = (byte) Math.max(Byte.toUnsignedInt(widths[index]), width);
        }

        void addBranch(Branch branch, int width) {
            int parent = branch.joinCell();
            List<Integer> cells = branch.cellsFromTerminal();
            for (int index = cells.size() - 1; index >= 0; index--) {
                int cell = cells.get(index);
                markCell(cell, width);
                markCell(parent, width);
                addEdge(cell, parent);
                parent = cell;
            }
        }

        private void addEdge(int a, int b) {
            int ax = a % gridWidth;
            int az = a / gridWidth;
            int bx = b % gridWidth;
            int bz = b / gridWidth;
            int direction = directionIndex(Integer.compare(bx, ax), Integer.compare(bz, az));
            int opposite = directionIndex(Integer.compare(ax, bx), Integer.compare(az, bz));
            edgeMasks[a] |= 1 << direction;
            edgeMasks[b] |= 1 << opposite;
        }
    }

    private static final class BuildingIndex {
        private static final BuildingIndex EMPTY = new BuildingIndex(Map.of());
        private final Map<Long, List<BuildingFootprint>> boundsByTile;

        private BuildingIndex(Map<Long, List<BuildingFootprint>> boundsByTile) {
            this.boundsByTile = boundsByTile;
        }

        static BuildingIndex of(List<LotInput> lots) {
            if (lots.isEmpty()) {
                return EMPTY;
            }
            Map<Long, List<BuildingFootprint>> mutable = new HashMap<>();
            for (LotInput lot : lots) {
                BoundingBox bounds = lot.bounds();
                BuildingFootprint footprint = new BuildingFootprint(lot.componentIndex(), bounds);
                int minTileX = Math.floorDiv(bounds.minX(), BUILDING_TILE_SIZE);
                int maxTileX = Math.floorDiv(bounds.maxX(), BUILDING_TILE_SIZE);
                int minTileZ = Math.floorDiv(bounds.minZ(), BUILDING_TILE_SIZE);
                int maxTileZ = Math.floorDiv(bounds.maxZ(), BUILDING_TILE_SIZE);
                for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                    for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                        mutable.computeIfAbsent(tileKey(tileX, tileZ), ignored -> new ArrayList<>())
                                .add(footprint);
                    }
                }
            }
            Map<Long, List<BuildingFootprint>> immutable = new HashMap<>();
            for (Map.Entry<Long, List<BuildingFootprint>> entry : mutable.entrySet()) {
                immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new BuildingIndex(Map.copyOf(immutable));
        }

        boolean inside(int x, int z, int componentIndex) {
            List<BuildingFootprint> candidates = boundsByTile.get(tileKey(
                    Math.floorDiv(x, BUILDING_TILE_SIZE),
                    Math.floorDiv(z, BUILDING_TILE_SIZE)
            ));
            if (candidates == null) {
                return false;
            }
            for (BuildingFootprint footprint : candidates) {
                BoundingBox bounds = footprint.bounds();
                if (footprint.componentIndex() == componentIndex
                        && x >= bounds.minX() && x <= bounds.maxX()
                        && z >= bounds.minZ() && z <= bounds.maxZ()) {
                    return true;
                }
            }
            return false;
        }

        int nearPenalty(int x, int z, int componentIndex) {
            int radius = NEAR_BUILDING_RADIUS_BLOCKS;
            int minTileX = Math.floorDiv(x - radius, BUILDING_TILE_SIZE);
            int maxTileX = Math.floorDiv(x + radius, BUILDING_TILE_SIZE);
            int minTileZ = Math.floorDiv(z - radius, BUILDING_TILE_SIZE);
            int maxTileZ = Math.floorDiv(z + radius, BUILDING_TILE_SIZE);
            int best = Integer.MAX_VALUE;
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    List<BuildingFootprint> candidates = boundsByTile.get(tileKey(tileX, tileZ));
                    if (candidates == null) {
                        continue;
                    }
                    for (BuildingFootprint footprint : candidates) {
                        if (footprint.componentIndex() != componentIndex) {
                            continue;
                        }
                        BoundingBox bounds = footprint.bounds();
                        int dx = x < bounds.minX()
                                ? bounds.minX() - x
                                : Math.max(0, x - bounds.maxX());
                        int dz = z < bounds.minZ()
                                ? bounds.minZ() - z
                                : Math.max(0, z - bounds.maxZ());
                        best = Math.min(best, dx + dz);
                    }
                }
            }
            if (best == Integer.MAX_VALUE || best > radius) {
                return 0;
            }
            return (radius + 1 - best) * 5;
        }
    }

    private static final class IntMinHeap {
        private int[] nodes;
        private int[] priorities;
        private int size;

        private IntMinHeap(int initialCapacity) {
            nodes = new int[initialCapacity];
            priorities = new int[initialCapacity];
        }

        boolean isEmpty() {
            return size == 0;
        }

        int peekNode() {
            return nodes[0];
        }

        int peekPriority() {
            return priorities[0];
        }

        void add(int node, int priority) {
            ensureCapacity();
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (!less(priority, node, priorities[parent], nodes[parent])) {
                    break;
                }
                priorities[index] = priorities[parent];
                nodes[index] = nodes[parent];
                index = parent;
            }
            priorities[index] = priority;
            nodes[index] = node;
        }

        void removeFirst() {
            int lastIndex = --size;
            if (lastIndex == 0) {
                return;
            }
            int node = nodes[lastIndex];
            int priority = priorities[lastIndex];
            int index = 0;
            int half = size >>> 1;
            while (index < half) {
                int child = index * 2 + 1;
                int right = child + 1;
                if (right < size && less(priorities[right], nodes[right], priorities[child], nodes[child])) {
                    child = right;
                }
                if (!less(priorities[child], nodes[child], priority, node)) {
                    break;
                }
                priorities[index] = priorities[child];
                nodes[index] = nodes[child];
                index = child;
            }
            priorities[index] = priority;
            nodes[index] = node;
        }

        private void ensureCapacity() {
            if (size < nodes.length) {
                return;
            }
            int nextCapacity = nodes.length + (nodes.length >>> 1) + 1;
            nodes = Arrays.copyOf(nodes, nextCapacity);
            priorities = Arrays.copyOf(priorities, nextCapacity);
        }

        private static boolean less(int priorityA, int nodeA, int priorityB, int nodeB) {
            return priorityA < priorityB || priorityA == priorityB && nodeA < nodeB;
        }
    }

    private static final class MutableStats {
        private int components;
        private int terminals;
        private int snappedTerminals;
        private int navigableRegions;
        private int backboneCandidates;
        private int routeTrees;
        private int settledCells;
        private int landmarkTerminals;
        private int snappedLandmarks;
        private int connectedLandmarks;
        private int regularTerminals;
        private int snappedRegulars;
        private int connectedRegulars;
        private int infillTerminals;
        private int snappedInfills;
        private int connectedInfills;
        private int connectorBranches;
        private int maxRegularConnectorCells;
        private int maxInfillConnectorCells;
        private int roadCells;
        private int roadBlocks;
        private long gridNanos;
        private long selectionNanos;
        private long routeNanos;
        private long compileNanos;

        private RoadStats freeze() {
            return new RoadStats(
                    components,
                    terminals,
                    snappedTerminals,
                    navigableRegions,
                    backboneCandidates,
                    routeTrees,
                    settledCells,
                    landmarkTerminals,
                    snappedLandmarks,
                    connectedLandmarks,
                    regularTerminals,
                    snappedRegulars,
                    connectedRegulars,
                    infillTerminals,
                    snappedInfills,
                    connectedInfills,
                    connectorBranches,
                    maxRegularConnectorCells,
                    maxInfillConnectorCells,
                    roadCells,
                    roadBlocks,
                    gridNanos,
                    selectionNanos,
                    routeNanos,
                    compileNanos
            );
        }
    }

    private enum LotTier {
        LANDMARK(0),
        REGULAR(1),
        INFILL(2);

        private final int priority;

        LotTier(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }

        static LotTier forArea(int area) {
            if (area >= SnowtownSettings.LOT_PACKING_LANDMARK_MIN_AREA) {
                return LANDMARK;
            }
            if (area <= SnowtownSettings.LOT_PACKING_INFILL_MAX_AREA) {
                return INFILL;
            }
            return REGULAR;
        }
    }

    private record DoorTerminal(
            int id,
            int doorX,
            int doorZ,
            int cellIndex,
            int regionId,
            LotTier tier,
            int area,
            int cellX,
            int cellZ
    ) {}

    private record Branch(
            List<Integer> cellsFromTerminal,
            int joinCell,
            int cost,
            boolean reachable
    ) {
        static Branch unreachable() {
            return new Branch(List.of(), -1, 0, false);
        }

        int newCells() {
            return cellsFromTerminal.size();
        }
    }

    private record BuildingFootprint(int componentIndex, BoundingBox bounds) {}

    private static int directionIndex(int dx, int dz) {
        for (int index = 0; index < NEIGHBORS.length; index++) {
            if (NEIGHBORS[index][0] == dx && NEIGHBORS[index][1] == dz) {
                return index;
            }
        }
        throw new IllegalArgumentException("Cells are not neighbors: dx=" + dx + ", dz=" + dz);
    }

    private static long blockColumnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static long tileKey(int tileX, int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
    }

    private static int horizontalDistanceSquared(int ax, int az, int bx, int bz) {
        int dx = ax - bx;
        int dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
