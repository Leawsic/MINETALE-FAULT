package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc;

import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.Entrance;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.NavigationCell;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.TownKey;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownResidentNavigationSource.TownSnapshot;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

// 从 Snowtown 规划中提取非道路独占的共享行人连通图。
final class SnowtownPedestrianMap {
    private static final int[][] NEIGHBORS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };
    private static final double ROAD_COST_MULTIPLIER = 0.65D;
    private static final double HEIGHT_STEP_COST = 0.25D;

    private final TownKey key;
    private final int[] cellX;
    private final int[] cellZ;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private final boolean[] roadPreferred;
    private final int[] regionByCell;
    private final int[][] cellsByRegion;
    private final boolean[] residentRegion;
    private final int minCellX;
    private final int minCellZ;
    private final int cellGridWidth;
    private final int cellGridHeight;
    private final int[] cellByGridPosition;
    private final int[] anchorCell;
    private final int[] anchorRegion;
    private final double minX;
    private final double maxX;
    private final double minY;
    private final double maxY;
    private final double minZ;
    private final double maxZ;

    SnowtownPedestrianMap(TownSnapshot snapshot) {
        this.key = snapshot.key();
        int cellCount = snapshot.cells().size();
        this.cellX = new int[cellCount];
        this.cellZ = new int[cellCount];
        this.x = new double[cellCount];
        this.y = new double[cellCount];
        this.z = new double[cellCount];
        this.roadPreferred = new boolean[cellCount];

        for (int index = 0; index < cellCount; index++) {
            NavigationCell cell = snapshot.cells().get(index);
            this.cellX[index] = cell.cellX();
            this.cellZ[index] = cell.cellZ();
            this.x[index] = cell.x();
            this.y[index] = cell.y();
            this.z[index] = cell.z();
            this.roadPreferred[index] = cell.roadPreferred();
        }

        this.minCellX = Arrays.stream(this.cellX).min().orElse(0);
        this.minCellZ = Arrays.stream(this.cellZ).min().orElse(0);
        int maxCellX = Arrays.stream(this.cellX).max().orElse(-1);
        int maxCellZ = Arrays.stream(this.cellZ).max().orElse(-1);
        this.cellGridWidth = cellCount == 0 ? 0 : maxCellX - this.minCellX + 1;
        this.cellGridHeight = cellCount == 0 ? 0 : maxCellZ - this.minCellZ + 1;
        this.cellByGridPosition = new int[this.cellGridWidth * this.cellGridHeight];
        Arrays.fill(this.cellByGridPosition, -1);
        for (int cell = 0; cell < cellCount; cell++) {
            this.cellByGridPosition[gridIndex(this.cellX[cell], this.cellZ[cell])] = cell;
        }

        this.minX = Arrays.stream(this.x).min().orElse(0.0D);
        this.maxX = Arrays.stream(this.x).max().orElse(0.0D);
        this.minY = Arrays.stream(this.y).min().orElse(0.0D);
        this.maxY = Arrays.stream(this.y).max().orElse(0.0D);
        this.minZ = Arrays.stream(this.z).min().orElse(0.0D);
        this.maxZ = Arrays.stream(this.z).max().orElse(0.0D);

        this.regionByCell = buildRegions();
        int regionCount = Arrays.stream(this.regionByCell).max().orElse(-1) + 1;
        int[] cellsPerRegion = new int[regionCount];
        for (int region : this.regionByCell) {
            cellsPerRegion[region]++;
        }
        this.cellsByRegion = new int[regionCount][];
        for (int region = 0; region < regionCount; region++) {
            this.cellsByRegion[region] = new int[cellsPerRegion[region]];
        }
        Arrays.fill(cellsPerRegion, 0);
        for (int cell = 0; cell < this.regionByCell.length; cell++) {
            int region = this.regionByCell[cell];
            this.cellsByRegion[region][cellsPerRegion[region]++] = cell;
        }

        Set<Integer> validAnchorCells = new LinkedHashSet<>();
        for (Entrance entrance : snapshot.entrances()) {
            int cell = cellIndex(entrance.cellX(), entrance.cellZ());
            if (cell >= 0) {
                validAnchorCells.add(cell);
            }
        }

        int[] anchorsPerRegion = new int[regionCount];
        for (int cell : validAnchorCells) {
            anchorsPerRegion[this.regionByCell[cell]]++;
        }
        this.anchorCell = validAnchorCells.stream()
                .filter(cell -> anchorsPerRegion[this.regionByCell[cell]] >= 2)
                .mapToInt(Integer::intValue)
                .toArray();
        this.anchorRegion = new int[this.anchorCell.length];
        for (int anchor = 0; anchor < this.anchorCell.length; anchor++) {
            this.anchorRegion[anchor] = this.regionByCell[this.anchorCell[anchor]];
        }
        this.residentRegion = new boolean[regionCount];
        for (int region : this.anchorRegion) {
            this.residentRegion[region] = true;
        }
    }

    TownKey key() {
        return this.key;
    }

    int anchorCount() {
        return this.anchorCell.length;
    }

    int anchorRegion(int anchor) {
        return this.anchorRegion[anchor];
    }

    int anchorCell(int anchor) {
        return this.anchorCell[anchor];
    }

    int cellCount() {
        return this.cellX.length;
    }

    int region(int cell) {
        return this.regionByCell[cell];
    }

    int regionCellCount(int region) {
        return this.cellsByRegion[region].length;
    }

    int regionCell(int region, int ordinal) {
        int[] cells = this.cellsByRegion[region];
        return cells[Math.floorMod(ordinal, cells.length)];
    }

    boolean isResidentRegion(int region) {
        return region >= 0
                && region < this.residentRegion.length
                && this.residentRegion[region];
    }

    int neighborCell(int cell, int deltaCellX, int deltaCellZ) {
        return cellIndex(
                this.cellX[cell] + deltaCellX,
                this.cellZ[cell] + deltaCellZ
        );
    }

    int cellAt(double positionX, double positionZ) {
        int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
        int worldX = (int)Math.floor(positionX);
        int worldZ = (int)Math.floor(positionZ);
        return cellIndex(
                Math.floorDiv(worldX, cellSize),
                Math.floorDiv(worldZ, cellSize)
        );
    }

    int[] nearestCells(
            double positionX,
            double positionZ,
            int requestedCount,
            double maximumDistanceSquared
    ) {
        int capacity = Math.min(Math.max(requestedCount, 0), this.cellX.length);
        if (capacity == 0) {
            return new int[0];
        }
        int[] nearest = new int[capacity];
        double[] distances = new double[capacity];
        int count = 0;
        for (int cell = 0; cell < this.cellX.length; cell++) {
            double deltaX = this.x[cell] - positionX;
            double deltaZ = this.z[cell] - positionZ;
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared > maximumDistanceSquared) {
                continue;
            }
            int insertion = count;
            while (insertion > 0 && distanceSquared < distances[insertion - 1]) {
                if (insertion < capacity) {
                    nearest[insertion] = nearest[insertion - 1];
                    distances[insertion] = distances[insertion - 1];
                }
                insertion--;
            }
            if (insertion >= capacity) {
                continue;
            }
            nearest[insertion] = cell;
            distances[insertion] = distanceSquared;
            if (count < capacity) {
                count++;
            }
        }
        return count == capacity ? nearest : Arrays.copyOf(nearest, count);
    }

    double anchorX(int anchor) {
        return x(this.anchorCell[anchor]);
    }

    double anchorY(int anchor) {
        return y(this.anchorCell[anchor]);
    }

    double anchorZ(int anchor) {
        return z(this.anchorCell[anchor]);
    }

    int nearestAnchor(double positionX, double positionZ) {
        int nearest = 0;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        for (int anchor = 0; anchor < this.anchorCell.length; anchor++) {
            double deltaX = anchorX(anchor) - positionX;
            double deltaZ = anchorZ(anchor) - positionZ;
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared < nearestDistanceSquared) {
                nearest = anchor;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    int nearestCell(double positionX, double positionZ) {
        int containing = cellAt(positionX, positionZ);
        if (containing >= 0) {
            return containing;
        }
        int nearest = 0;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        for (int cell = 0; cell < this.cellX.length; cell++) {
            double deltaX = this.x[cell] - positionX;
            double deltaZ = this.z[cell] - positionZ;
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared < nearestDistanceSquared) {
                nearest = cell;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return nearest;
    }

    double x(int cell) {
        return this.x[cell];
    }

    double y(int cell) {
        return this.y[cell];
    }

    double z(int cell) {
        return this.z[cell];
    }

    boolean roadPreferred(int cell) {
        return this.roadPreferred[cell];
    }

    double distanceToBoundsSquared(double positionX, double positionZ) {
        double deltaX = positionX < this.minX
                ? this.minX - positionX
                : Math.max(0.0D, positionX - this.maxX);
        double deltaZ = positionZ < this.minZ
                ? this.minZ - positionZ
                : Math.max(0.0D, positionZ - this.maxZ);
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    // 高度只用于在 XZ 重叠的规划分量间选择玩家所在层。
    double distanceToBoundsSquared(double positionX, double positionY, double positionZ) {
        double horizontalDistanceSquared = distanceToBoundsSquared(positionX, positionZ);
        double deltaY = positionY < this.minY
                ? this.minY - positionY
                : Math.max(0.0D, positionY - this.maxY);
        return horizontalDistanceSquared + deltaY * deltaY;
    }

    double centerX() {
        return (this.minX + this.maxX) * 0.5D;
    }

    double centerZ() {
        return (this.minZ + this.maxZ) * 0.5D;
    }

    int minimumBlockX() {
        return (int)Math.floor(this.minX);
    }

    int maximumBlockX() {
        return (int)Math.ceil(this.maxX);
    }

    int minimumBlockZ() {
        return (int)Math.floor(this.minZ);
    }

    int maximumBlockZ() {
        return (int)Math.ceil(this.maxZ);
    }

    Optional<Route> route(int startAnchor, int destinationAnchor) {
        if (startAnchor < 0 || startAnchor >= this.anchorCell.length
                || destinationAnchor < 0 || destinationAnchor >= this.anchorCell.length
                || this.anchorRegion[startAnchor] != this.anchorRegion[destinationAnchor]) {
            return Optional.empty();
        }

        return routeCells(this.anchorCell[startAnchor], this.anchorCell[destinationAnchor]);
    }

    Optional<Route> routeCells(int start, int destination) {
        if (start < 0 || start >= this.cellX.length
                || destination < 0 || destination >= this.cellX.length
                || this.regionByCell[start] != this.regionByCell[destination]) {
            return Optional.empty();
        }
        if (start == destination) {
            return Optional.of(new Route(new int[]{start}, 0.0D));
        }

        double[] cost = new double[this.cellX.length];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        int[] previous = new int[this.cellX.length];
        Arrays.fill(previous, -1);
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator
                .comparingDouble(SearchNode::estimatedTotalCost)
                .thenComparingInt(SearchNode::cell));
        cost[start] = 0.0D;
        open.add(new SearchNode(start, 0.0D, heuristic(start, destination)));

        while (!open.isEmpty()) {
            SearchNode current = open.remove();
            if (current.costFromStart() > cost[current.cell()]) {
                continue;
            }
            if (current.cell() == destination) {
                return Optional.of(reconstruct(previous, destination, cost[destination]));
            }

            for (int[] offset : NEIGHBORS) {
                int neighbor = cellIndex(
                        this.cellX[current.cell()] + offset[0],
                        this.cellZ[current.cell()] + offset[1]
                );
                if (neighbor < 0
                        || this.regionByCell[neighbor] != this.regionByCell[start]
                        || !isTraversableEdge(current.cell(), neighbor)) {
                    continue;
                }
                double nextCost = cost[current.cell()] + stepCost(current.cell(), neighbor);
                if (nextCost >= cost[neighbor]) {
                    continue;
                }
                cost[neighbor] = nextCost;
                previous[neighbor] = current.cell();
                open.add(new SearchNode(
                        neighbor,
                        nextCost,
                        nextCost + heuristic(neighbor, destination)
                ));
            }
        }
        return Optional.empty();
    }

    private int[] buildRegions() {
        int[] regions = new int[this.cellX.length];
        Arrays.fill(regions, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int nextRegion = 0;
        for (int start = 0; start < this.cellX.length; start++) {
            if (regions[start] >= 0) {
                continue;
            }
            regions[start] = nextRegion;
            queue.add(start);
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                for (int[] offset : NEIGHBORS) {
                    int neighbor = cellIndex(
                            this.cellX[current] + offset[0],
                            this.cellZ[current] + offset[1]
                    );
                    if (neighbor < 0 || regions[neighbor] >= 0) {
                        continue;
                    }
                    if (!isTraversableEdge(current, neighbor)) {
                        continue;
                    }
                    regions[neighbor] = nextRegion;
                    queue.addLast(neighbor);
                }
            }
            nextRegion++;
        }
        return regions;
    }

    private boolean isTraversableEdge(int from, int to) {
        return Math.abs(this.y[from] - this.y[to]) <= 1.0D;
    }

    private double stepCost(int from, int to) {
        double base = SnowtownSettings.PLANNING_AREA_CELL_SIZE
                * (this.roadPreferred[to] ? ROAD_COST_MULTIPLIER : 1.0D);
        return base + Math.abs(this.y[to] - this.y[from]) * HEIGHT_STEP_COST;
    }

    private double heuristic(int from, int to) {
        int manhattan = Math.abs(this.cellX[from] - this.cellX[to])
                + Math.abs(this.cellZ[from] - this.cellZ[to]);
        return manhattan * SnowtownSettings.PLANNING_AREA_CELL_SIZE * ROAD_COST_MULTIPLIER;
    }

    private Route reconstruct(int[] previous, int destination, double cost) {
        int length = 1;
        for (int cursor = destination; previous[cursor] >= 0; cursor = previous[cursor]) {
            length++;
        }
        int[] cells = new int[length];
        int cursor = destination;
        for (int index = length - 1; index >= 0; index--) {
            cells[index] = cursor;
            cursor = previous[cursor];
        }
        return new Route(cells, cost);
    }

    private int cellIndex(int cellX, int cellZ) {
        if (cellX < this.minCellX
                || cellZ < this.minCellZ
                || cellX >= this.minCellX + this.cellGridWidth
                || cellZ >= this.minCellZ + this.cellGridHeight) {
            return -1;
        }
        return this.cellByGridPosition[gridIndex(cellX, cellZ)];
    }

    private int gridIndex(int cellX, int cellZ) {
        return (cellZ - this.minCellZ) * this.cellGridWidth + cellX - this.minCellX;
    }

    record Route(int[] cells, double cost) {
        Route {
            cells = Arrays.copyOf(cells, cells.length);
        }

        @Override
        public int[] cells() {
            return Arrays.copyOf(this.cells, this.cells.length);
        }

        int[] sharedCells() {
            return this.cells;
        }
    }

    private record SearchNode(int cell, double costFromStart, double estimatedTotalCost) {}
}
