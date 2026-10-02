package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinColumnFacts;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinNaturalTerrainFactsKernel;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// PlanningArea 拥有 core/halo 坐标、地形扫描、连通域结果及其有界缓存。
final class SnowtownPlanningArea {
    private static final ConcurrentMap<CacheKey, Analysis> ANALYSES = new ConcurrentHashMap<>();
    private static final boolean ENABLE_WORLDGEN_PROFILING = false;
    private static final int[][] FOUR_NEIGHBORS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };
    private static final int[][] EIGHT_NEIGHBORS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1},
            {1, 1},
            {1, -1},
            {-1, 1},
            {-1, -1}
    };

    private SnowtownPlanningArea() {}

    static Analysis get(WorldgenSamplingContext context, Id id, int minY, int maxY) {
        CacheKey key = new CacheKey(
                context.worldgenSeed(),
                SnowtownSettings.PLANNING_SETTINGS_VERSION,
                id.areaX(),
                id.areaZ()
        );
        trimCacheIfNeeded();
        return ANALYSES.computeIfAbsent(key, ignored -> scanCold(context, id, minY, maxY));
    }

    static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private static void trimCacheIfNeeded() {
        if (ANALYSES.size() > SnowtownSettings.MAX_CACHED_PLANNING_AREAS) {
            ANALYSES.clear();
        }
    }

    private static Analysis scanCold(WorldgenSamplingContext context, Id id, int minY, int maxY) {
        long startedAt = System.nanoTime();
        Analysis analysis = scan(context, id, minY, maxY);
        double elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000.0;
        if (ENABLE_WORLDGEN_PROFILING) {
            MineTale.LOGGER.info(
                    "Snowtown PlanningArea cold scan ({}, {}) took {} ms, components={}",
                    id.areaX(),
                    id.areaZ(),
                    elapsedMillis,
                    analysis.components().size()
            );
        } else if (elapsedMillis >= 100.0) {
            MineTale.LOGGER.warn(
                    "Snowtown PlanningArea cold scan ({}, {}) was slow: {} ms, components={}",
                    id.areaX(),
                    id.areaZ(),
                    elapsedMillis,
                    analysis.components().size()
            );
        }
        return analysis;
    }

    private static Analysis scan(WorldgenSamplingContext context, Id id, int minY, int maxY) {
        SnowtownSettings.validate();
        Bounds bounds = Bounds.of(id);
        Cell[] cells = sampleCells(context, bounds, minY, maxY);
        List<ComponentDraft> drafts = floodFill(bounds, cells);
        drafts.sort(Comparator
                .comparingInt(ComponentDraft::minCoreCellZ)
                .thenComparingInt(ComponentDraft::minCoreCellX)
                .thenComparing(Comparator.comparingInt(ComponentDraft::coreCellCount).reversed()));

        List<Component> components = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            ComponentDraft draft = drafts.get(index);
            components.add(new Component(
                    index,
                    draft.cells(),
                    draft.coreCellCount(),
                    draft.minCoreCellX(),
                    draft.minCoreCellZ()
            ));
        }
        return new Analysis(id, bounds, components);
    }

    private static Cell[] sampleCells(
            WorldgenSamplingContext context,
            Bounds bounds,
            int minY,
            int maxY
    ) {
        int width = bounds.cellWidth();
        int depth = bounds.cellDepth();
        Cell[] cells = new Cell[width * depth];
        RainbowCakeModel model = RainbowCakeModel.create(context.worldgenSeed());

        for (int localZ = 0; localZ < depth; localZ++) {
            int cellZ = bounds.minCellZ() + localZ;
            int centerZ = centerBlock(cellZ);
            for (int localX = 0; localX < width; localX++) {
                int cellX = bounds.minCellX() + localX;
                int centerX = centerBlock(cellX);
                Optional<SnowdinColumnFacts> maybeFacts = SnowdinNaturalTerrainFactsKernel.sample(
                        context,
                        model,
                        centerX,
                        centerZ,
                        minY,
                        maxY
                );
                if (maybeFacts.isEmpty()) {
                    continue;
                }
                SnowdinColumnFacts facts = maybeFacts.get();
                if (facts.surfaceBlockY().isEmpty()
                        || facts.terraceMask() < SnowtownSettings.CELL_TERRACE_MASK_MIN
                        || facts.pillarMaskAtSurface() > SnowtownSettings.CELL_PILLAR_MASK_MAX) {
                    continue;
                }
                cells[index(localX, localZ, width)] = new Cell(
                        cellX,
                        cellZ,
                        centerX,
                        centerZ,
                        facts.surfaceBlockY().getAsInt(),
                        facts.ceilingY(),
                        facts.terraceMask(),
                        facts.pillarMaskAtSurface()
                );
            }
        }
        return cells;
    }

    private static List<ComponentDraft> floodFill(Bounds bounds, Cell[] cells) {
        int width = bounds.cellWidth();
        int depth = bounds.cellDepth();
        boolean[] visited = new boolean[cells.length];
        List<ComponentDraft> components = new ArrayList<>();

        for (int localZ = 0; localZ < depth; localZ++) {
            for (int localX = 0; localX < width; localX++) {
                int startIndex = index(localX, localZ, width);
                if (visited[startIndex] || cells[startIndex] == null) {
                    continue;
                }
                ComponentDraft component = collectComponent(bounds, cells, visited, localX, localZ);
                if (component.coreCellCount() >= SnowtownSettings.MIN_COMPONENT_CORE_CELLS) {
                    components.add(component);
                }
            }
        }
        return components;
    }

    private static ComponentDraft collectComponent(
            Bounds bounds,
            Cell[] cells,
            boolean[] visited,
            int startX,
            int startZ
    ) {
        int width = bounds.cellWidth();
        int depth = bounds.cellDepth();
        ArrayDeque<CellCursor> queue = new ArrayDeque<>();
        List<Cell> componentCells = new ArrayList<>();
        int coreCellCount = 0;
        int minCoreCellX = Integer.MAX_VALUE;
        int minCoreCellZ = Integer.MAX_VALUE;

        visited[index(startX, startZ, width)] = true;
        queue.add(new CellCursor(startX, startZ));

        while (!queue.isEmpty()) {
            CellCursor cursor = queue.removeFirst();
            Cell cell = cells[index(cursor.localX(), cursor.localZ(), width)];
            componentCells.add(cell);
            if (bounds.containsCoreCell(cell)) {
                coreCellCount++;
                minCoreCellX = Math.min(minCoreCellX, cell.cellX());
                minCoreCellZ = Math.min(minCoreCellZ, cell.cellZ());
            }

            visitNeighbors(bounds, cells, visited, queue, cursor, cell);
        }

        return new ComponentDraft(componentCells, coreCellCount, minCoreCellX, minCoreCellZ);
    }

    private static void visitNeighbors(
            Bounds bounds,
            Cell[] cells,
            boolean[] visited,
            ArrayDeque<CellCursor> queue,
            CellCursor cursor,
            Cell cell
    ) {
        int[][] directions = SnowtownSettings.FLOOD_FILL_NEIGHBOR_COUNT == 8
                ? EIGHT_NEIGHBORS
                : FOUR_NEIGHBORS;
        int width = bounds.cellWidth();
        int depth = bounds.cellDepth();

        for (int[] direction : directions) {
            int nextX = cursor.localX() + direction[0];
            int nextZ = cursor.localZ() + direction[1];
            if (nextX < 0 || nextX >= width || nextZ < 0 || nextZ >= depth) {
                continue;
            }
            int nextIndex = index(nextX, nextZ, width);
            Cell next = cells[nextIndex];
            if (visited[nextIndex] || next == null) {
                continue;
            }
            if (Math.abs(cell.surfaceBlockY() - next.surfaceBlockY()) > 1) {
                continue;
            }
            visited[nextIndex] = true;
            queue.addLast(new CellCursor(nextX, nextZ));
        }
    }

    private static int centerBlock(int cell) {
        return cell * SnowtownSettings.PLANNING_AREA_CELL_SIZE
                + SnowtownSettings.PLANNING_AREA_CELL_CENTER_OFFSET;
    }

    private static int index(int localX, int localZ, int width) {
        return localZ * width + localX;
    }

    record Id(int areaX, int areaZ) {
        static Id fromBlock(int blockX, int blockZ) {
            int coreSize = SnowtownSettings.PLANNING_AREA_CORE_SIZE;
            return new Id(Math.floorDiv(blockX, coreSize), Math.floorDiv(blockZ, coreSize));
        }
    }

    record Bounds(
            int coreMinX,
            int coreMinZ,
            int coreMaxXExclusive,
            int coreMaxZExclusive,
            int analysisMinX,
            int analysisMinZ,
            int analysisMaxXExclusive,
            int analysisMaxZExclusive,
            int minCellX,
            int minCellZ,
            int maxCellXExclusive,
            int maxCellZExclusive
    ) {
        static Bounds of(Id id) {
            int coreSize = SnowtownSettings.PLANNING_AREA_CORE_SIZE;
            int haloSize = SnowtownSettings.PLANNING_AREA_HALO_SIZE;
            int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;

            int coreMinX = id.areaX() * coreSize;
            int coreMinZ = id.areaZ() * coreSize;
            int coreMaxXExclusive = coreMinX + coreSize;
            int coreMaxZExclusive = coreMinZ + coreSize;
            int analysisMinX = coreMinX - haloSize;
            int analysisMinZ = coreMinZ - haloSize;
            int analysisMaxXExclusive = coreMaxXExclusive + haloSize;
            int analysisMaxZExclusive = coreMaxZExclusive + haloSize;

            return new Bounds(
                    coreMinX,
                    coreMinZ,
                    coreMaxXExclusive,
                    coreMaxZExclusive,
                    analysisMinX,
                    analysisMinZ,
                    analysisMaxXExclusive,
                    analysisMaxZExclusive,
                    minCell(analysisMinX, cellSize),
                    minCell(analysisMinZ, cellSize),
                    maxCellExclusive(analysisMaxXExclusive, cellSize),
                    maxCellExclusive(analysisMaxZExclusive, cellSize)
            );
        }

        boolean containsCoreBlock(int x, int z) {
            return x >= coreMinX
                    && x < coreMaxXExclusive
                    && z >= coreMinZ
                    && z < coreMaxZExclusive;
        }

        boolean containsCoreCell(Cell cell) {
            return containsCoreBlock(cell.centerX(), cell.centerZ());
        }

        int cellWidth() {
            return maxCellXExclusive - minCellX;
        }

        int cellDepth() {
            return maxCellZExclusive - minCellZ;
        }

        private static int minCell(int block, int cellSize) {
            return Math.floorDiv(block, cellSize);
        }

        private static int maxCellExclusive(int blockMaxExclusive, int cellSize) {
            return Math.floorDiv(blockMaxExclusive - 1, cellSize) + 1;
        }
    }

    // cell 中心样本代表整个粗方格，后续规划不得改用另一套地形判定。
    record Cell(
            int cellX,
            int cellZ,
            int centerX,
            int centerZ,
            int surfaceBlockY,
            double ceilingY,
            double terraceMask,
            double pillarMaskAtSurface
    ) {}

    record Component(
            int componentIndex,
            List<Cell> cells,
            int coreCellCount,
            int minCoreCellX,
            int minCoreCellZ
    ) {
        Component {
            cells = List.copyOf(cells);
        }
    }

    record Analysis(Id id, Bounds bounds, List<Component> components) {
        Analysis {
            components = List.copyOf(components);
        }

        Map<Long, Component> componentLookup() {
            Map<Long, Component> lookup = new HashMap<>();
            for (Component component : components) {
                for (Cell cell : component.cells()) {
                    lookup.put(cellKey(cell.cellX(), cell.cellZ()), component);
                }
            }
            return lookup;
        }

        Map<Long, Cell> cellLookup() {
            Map<Long, Cell> lookup = new HashMap<>();
            for (Component component : components) {
                for (Cell cell : component.cells()) {
                    lookup.put(cellKey(cell.cellX(), cell.cellZ()), cell);
                }
            }
            return lookup;
        }
    }

    private record CacheKey(long worldSeed, int version, int areaX, int areaZ) {}

    private record CellCursor(int localX, int localZ) {}

    private record ComponentDraft(
            List<Cell> cells,
            int coreCellCount,
            int minCoreCellX,
            int minCoreCellZ
    ) {}
}
