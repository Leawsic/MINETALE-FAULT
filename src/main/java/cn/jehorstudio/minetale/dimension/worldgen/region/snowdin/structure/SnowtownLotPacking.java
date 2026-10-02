package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetCatalog;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetFootprint;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.StructureAssetMarkerQueries;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// 拥有 lot 候选、三阶段装箱规则与 reservation 空间索引。
final class SnowtownLotPacking {
    private static final int CHANNEL_SALT = 8241;
    private static final int STAGE_SALT_STRIDE = 101;

    private SnowtownLotPacking() {}

    static List<Pass> passes(
            WorldgenSamplingContext context,
            SnowtownPlanningArea.Analysis analysis
    ) {
        List<LotAsset> assets = lotAssets();
        if (assets.isEmpty() || analysis.components().isEmpty()) {
            return List.of();
        }

        long scatterSeed = context.channelSeed(CHANNEL_SALT);
        List<Pass> passes = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            List<LotAsset> stageAssets = assets.stream()
                    .filter(asset -> asset.stage() == stage)
                    .toList();
            if (stageAssets.isEmpty()) {
                continue;
            }
            for (SnowtownPlanningArea.Component component : analysis.components()) {
                List<SnowtownPlanningArea.Cell> candidates = rankedCandidates(
                        analysis.bounds(),
                        component,
                        stage,
                        scatterSeed
                );
                if (!candidates.isEmpty()) {
                    passes.add(new Pass(
                            component,
                            stage,
                            candidates,
                            stageAssets,
                            scatterSeed
                    ));
                }
            }
        }
        return List.copyOf(passes);
    }

    private static List<LotAsset> lotAssets() {
        return StructureAssetCatalog.list().stream()
                .filter(SnowtownLotPacking::isLotAsset)
                .map(asset -> {
                    String id = asset.id().toString();
                    return new LotAsset(
                            asset,
                            Stage.forFootprint(asset.footprint()),
                            id.hashCode(),
                            id
                    );
                })
                .sorted(Comparator.comparing(LotAsset::id))
                .toList();
    }

    private static boolean isLotAsset(StructureAssetDefinition asset) {
        if (asset.scanResult() == null || StructureAssetMarkerQueries.anchors(asset.scanResult()).isEmpty()) {
            return false;
        }
        return "building".equals(asset.role())
                || isBuildingId(asset.category())
                || asset.tags().stream().anyMatch(SnowtownLotPacking::isBuildingId);
    }

    private static boolean isBuildingId(ResourceLocation id) {
        return "building".equals(id.getPath());
    }

    private static List<SnowtownPlanningArea.Cell> rankedCandidates(
            SnowtownPlanningArea.Bounds bounds,
            SnowtownPlanningArea.Component component,
            Stage stage,
            long scatterSeed
    ) {
        List<SnowtownPlanningArea.Cell> coreCells = component.cells().stream()
                .filter(bounds::containsCoreCell)
                .toList();
        if (coreCells.isEmpty()) {
            return List.of();
        }
        double centerX = coreCells.stream().mapToDouble(SnowtownPlanningArea.Cell::centerX).average().orElse(0.0);
        double centerZ = coreCells.stream().mapToDouble(SnowtownPlanningArea.Cell::centerZ).average().orElse(0.0);
        int stageSalt = CHANNEL_SALT + stage.ordinal() * STAGE_SALT_STRIDE;
        return coreCells.stream()
                .sorted(Comparator
                        .comparingDouble((SnowtownPlanningArea.Cell cell) -> distanceSquared(cell, centerX, centerZ))
                        .thenComparingInt(cell -> WorldgenMath.hash(
                                cell.cellX(),
                                stageSalt,
                                cell.cellZ(),
                                scatterSeed
                        ))
                        .thenComparingInt(SnowtownPlanningArea.Cell::cellZ)
                        .thenComparingInt(SnowtownPlanningArea.Cell::cellX))
                .limit(stage.candidateLimit())
                .toList();
    }

    private static double distanceSquared(SnowtownPlanningArea.Cell cell, double centerX, double centerZ) {
        double dx = cell.centerX() - centerX;
        double dz = cell.centerZ() - centerZ;
        return dx * dx + dz * dz;
    }

    // 阶段顺序固定为 landmark、regular、infill，后阶段只能填补剩余空间
    enum Stage {
        LANDMARK,
        REGULAR,
        INFILL;

        static Stage forFootprint(StructureAssetFootprint footprint) {
            int area = footprint.width() * footprint.depth();
            if (area >= SnowtownSettings.LOT_PACKING_LANDMARK_MIN_AREA) {
                return LANDMARK;
            }
            if (area <= SnowtownSettings.LOT_PACKING_INFILL_MAX_AREA) {
                return INFILL;
            }
            return REGULAR;
        }

        int candidateLimit() {
            return switch (this) {
                case LANDMARK -> SnowtownSettings.LOT_PACKING_LANDMARK_CANDIDATE_LIMIT;
                case REGULAR -> SnowtownSettings.LOT_PACKING_REGULAR_CANDIDATE_LIMIT;
                case INFILL -> SnowtownSettings.LOT_PACKING_INFILL_CANDIDATE_LIMIT;
            };
        }

        int assetAttempts() {
            return switch (this) {
                case LANDMARK -> SnowtownSettings.LOT_PACKING_LANDMARK_ASSET_ATTEMPTS;
                case REGULAR -> SnowtownSettings.LOT_PACKING_REGULAR_ASSET_ATTEMPTS;
                case INFILL -> SnowtownSettings.LOT_PACKING_INFILL_ASSET_ATTEMPTS;
            };
        }

        BoundingBox reserve(BoundingBox transformedBounds, Direction facing) {
            int margin = margin();
            int north = margin;
            int east = margin;
            int south = margin;
            int west = margin;
            int frontage = Math.max(margin, frontage());
            switch (facing) {
                case NORTH -> north = frontage;
                case EAST -> east = frontage;
                case SOUTH -> south = frontage;
                case WEST -> west = frontage;
                default -> {
                }
            }
            return new BoundingBox(
                    transformedBounds.minX() - west,
                    transformedBounds.minY(),
                    transformedBounds.minZ() - north,
                    transformedBounds.maxX() + east,
                    transformedBounds.maxY(),
                    transformedBounds.maxZ() + south
            );
        }

        private int margin() {
            return switch (this) {
                case LANDMARK -> SnowtownSettings.LOT_PACKING_LANDMARK_MARGIN;
                case REGULAR -> SnowtownSettings.LOT_PACKING_REGULAR_MARGIN;
                case INFILL -> SnowtownSettings.LOT_PACKING_INFILL_MARGIN;
            };
        }

        private int frontage() {
            return switch (this) {
                case LANDMARK -> SnowtownSettings.LOT_PACKING_LANDMARK_FRONTAGE;
                case REGULAR -> SnowtownSettings.LOT_PACKING_REGULAR_FRONTAGE;
                case INFILL -> SnowtownSettings.LOT_PACKING_INFILL_FRONTAGE;
            };
        }
    }

    record Sample(
            int componentIndex,
            StructureAssetDefinition asset,
            SnowtownPlanningArea.Cell cell,
            int x,
            int surfaceY,
            int z,
            Stage stage
    ) {}

    record Pass(
            SnowtownPlanningArea.Component component,
            Stage stage,
            List<SnowtownPlanningArea.Cell> candidates,
            List<LotAsset> assets,
            long scatterSeed
    ) {
        Pass {
            candidates = List.copyOf(candidates);
            assets = List.copyOf(assets);
        }

        List<StructureAssetDefinition> assetAttempts(SnowtownPlanningArea.Cell cell) {
            int stageSalt = CHANNEL_SALT + stage.ordinal() * STAGE_SALT_STRIDE;
            int limit = Math.min(stage.assetAttempts(), assets.size());
            LotAsset[] selected = new LotAsset[limit];
            double[] selectedRanks = new double[limit];
            int selectedSize = 0;

            for (LotAsset asset : assets) {
                double rank = weightedRank(cell, asset, stageSalt, scatterSeed);
                int insertion = 0;
                while (insertion < selectedSize
                        && compare(rank, asset.id(), selectedRanks[insertion], selected[insertion].id()) >= 0) {
                    insertion++;
                }
                if (insertion >= limit) {
                    continue;
                }

                int moved = Math.min(selectedSize, limit - 1) - insertion;
                if (moved > 0) {
                    System.arraycopy(selected, insertion, selected, insertion + 1, moved);
                    System.arraycopy(selectedRanks, insertion, selectedRanks, insertion + 1, moved);
                }
                selected[insertion] = asset;
                selectedRanks[insertion] = rank;
                if (selectedSize < limit) {
                    selectedSize++;
                }
            }

            List<StructureAssetDefinition> result = new ArrayList<>(selectedSize);
            for (int index = 0; index < selectedSize; index++) {
                result.add(selected[index].asset());
            }
            return List.copyOf(result);
        }

        private static int compare(double leftRank, String leftId, double rightRank, String rightId) {
            int rankOrder = Double.compare(leftRank, rightRank);
            return rankOrder != 0 ? rankOrder : leftId.compareTo(rightId);
        }

        private static double weightedRank(
                SnowtownPlanningArea.Cell cell,
                LotAsset asset,
                int stageSalt,
                long scatterSeed
        ) {
            double randomRank = WorldgenMath.hashToUnit(
                    cell.cellX(),
                    asset.salt() + stageSalt,
                    cell.cellZ(),
                    scatterSeed
            );
            return randomRank / Math.max(1, asset.asset().weight());
        }
    }

    record LotAsset(
            StructureAssetDefinition asset,
            Stage stage,
            int salt,
            String id
    ) {}

    // reservation 边界可以接触，内部重叠则视为装箱冲突。
    static final class BoundsIndex {
        private static final int TILE_SIZE = 32;

        private final Map<Long, List<BoundingBox>> boundsByTile = new HashMap<>();

        void add(BoundingBox bounds) {
            forEachTile(bounds, tileKey -> boundsByTile
                    .computeIfAbsent(tileKey, ignored -> new ArrayList<>())
                    .add(bounds));
        }

        boolean collides(BoundingBox bounds) {
            int minTileX = Math.floorDiv(bounds.minX(), TILE_SIZE);
            int maxTileX = Math.floorDiv(bounds.maxX(), TILE_SIZE);
            int minTileZ = Math.floorDiv(bounds.minZ(), TILE_SIZE);
            int maxTileZ = Math.floorDiv(bounds.maxZ(), TILE_SIZE);
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    List<BoundingBox> candidates = boundsByTile.get(tileKey(tileX, tileZ));
                    if (candidates == null) {
                        continue;
                    }
                    for (BoundingBox candidate : candidates) {
                        if (conflicts(bounds, candidate)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        static boolean conflicts(BoundingBox left, BoundingBox right) {
            return left.maxX() > right.minX()
                    && left.minX() < right.maxX()
                    && left.maxZ() > right.minZ()
                    && left.minZ() < right.maxZ()
                    && left.maxY() >= right.minY()
                    && left.minY() <= right.maxY();
        }

        private void forEachTile(BoundingBox bounds, TileConsumer consumer) {
            int minTileX = Math.floorDiv(bounds.minX(), TILE_SIZE);
            int maxTileX = Math.floorDiv(bounds.maxX(), TILE_SIZE);
            int minTileZ = Math.floorDiv(bounds.minZ(), TILE_SIZE);
            int maxTileZ = Math.floorDiv(bounds.maxZ(), TILE_SIZE);
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    consumer.accept(tileKey(tileX, tileZ));
                }
            }
        }

        private static long tileKey(int tileX, int tileZ) {
            return (((long) tileX) << 32) ^ (tileZ & 0xFFFFFFFFL);
        }

        @FunctionalInterface
        private interface TileConsumer {
            void accept(long tileKey);
        }
    }

    // 按地表高度分层的二维前缀和只用于 footprint 粗筛。
    static final class TerrainIndex {
        private static final int MISSING_SURFACE_Y = Integer.MIN_VALUE;

        private final int minCellX;
        private final int minCellZ;
        private final int width;
        private final int depth;
        private final int prefixWidth;
        private final int[] surfaceY;
        private final Map<Long, int[]> compatiblePrefixes = new HashMap<>();

        TerrainIndex(
                SnowtownPlanningArea.Bounds bounds,
                Iterable<SnowtownPlanningArea.Cell> cells
        ) {
            this.minCellX = bounds.minCellX();
            this.minCellZ = bounds.minCellZ();
            this.width = bounds.cellWidth();
            this.depth = bounds.cellDepth();
            this.prefixWidth = width + 1;
            this.surfaceY = new int[width * depth];
            Arrays.fill(surfaceY, MISSING_SURFACE_Y);
            for (SnowtownPlanningArea.Cell cell : cells) {
                int localX = cell.cellX() - minCellX;
                int localZ = cell.cellZ() - minCellZ;
                if (localX >= 0 && localX < width && localZ >= 0 && localZ < depth) {
                    surfaceY[localZ * width + localX] = cell.surfaceBlockY();
                }
            }
        }

        boolean footprintFits(BoundingBox bounds, int targetSurfaceY, StructureAssetFootprint footprint) {
            int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
            int minX = Math.floorDiv(bounds.minX(), cellSize);
            int maxX = Math.floorDiv(bounds.maxX(), cellSize);
            int minZ = Math.floorDiv(bounds.minZ(), cellSize);
            int maxZ = Math.floorDiv(bounds.maxZ(), cellSize);
            int total = (maxX - minX + 1) * (maxZ - minZ + 1);
            if (total <= 0) {
                return false;
            }

            int supported = supportedCount(minX, maxX, minZ, maxZ, targetSurfaceY, footprint.maxSlope());
            return footprint.requireSurface()
                    ? supported / (double) total >= SnowtownSettings.LOT_REQUIRED_SURFACE_COVERAGE
                    : supported == total;
        }

        private int supportedCount(
                int minX,
                int maxX,
                int minZ,
                int maxZ,
                int targetSurfaceY,
                int maxSlope
        ) {
            int localMinX = Math.max(0, minX - minCellX);
            int localMaxXExclusive = Math.min(width, maxX - minCellX + 1);
            int localMinZ = Math.max(0, minZ - minCellZ);
            int localMaxZExclusive = Math.min(depth, maxZ - minCellZ + 1);
            if (localMinX >= localMaxXExclusive || localMinZ >= localMaxZExclusive) {
                return 0;
            }

            int[] prefix = compatiblePrefixes.computeIfAbsent(
                    key(targetSurfaceY, maxSlope),
                    ignored -> buildCompatiblePrefix(targetSurfaceY, maxSlope)
            );
            int topLeft = prefix[localMinZ * prefixWidth + localMinX];
            int topRight = prefix[localMinZ * prefixWidth + localMaxXExclusive];
            int bottomLeft = prefix[localMaxZExclusive * prefixWidth + localMinX];
            int bottomRight = prefix[localMaxZExclusive * prefixWidth + localMaxXExclusive];
            return bottomRight - topRight - bottomLeft + topLeft;
        }

        private int[] buildCompatiblePrefix(int targetSurfaceY, int maxSlope) {
            int[] prefix = new int[(width + 1) * (depth + 1)];
            for (int localZ = 0; localZ < depth; localZ++) {
                int rowCount = 0;
                int sourceRow = localZ * width;
                int targetRow = (localZ + 1) * prefixWidth;
                int previousRow = localZ * prefixWidth;
                for (int localX = 0; localX < width; localX++) {
                    int value = surfaceY[sourceRow + localX];
                    if (value != MISSING_SURFACE_Y && Math.abs(value - targetSurfaceY) <= maxSlope) {
                        rowCount++;
                    }
                    prefix[targetRow + localX + 1] = prefix[previousRow + localX + 1] + rowCount;
                }
            }
            return prefix;
        }

        private static long key(int targetSurfaceY, int maxSlope) {
            return ((long) targetSurfaceY << 32) ^ (maxSlope & 0xFFFFFFFFL);
        }
    }
}
