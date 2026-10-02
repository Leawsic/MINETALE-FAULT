package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.asset.AssetPlacer;
import cn.jehorstudio.minetale.dimension.worldgen.asset.AssetPlacer.AssetPlacementPlan;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetCatalog;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class SnowtownLotPlanner {
    private static final ConcurrentMap<PlanKey, Plan> PLANS = new ConcurrentHashMap<>();
    private static final boolean ENABLE_WORLDGEN_PROFILING = false;

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    private SnowtownLotPlanner() {}

    static Plan plan(SnowdinContext context, SnowtownPlanningArea.Id id) {
        return plan(
                context.world().samplingContext(),
                context.world().minY(),
                context.world().maxY(),
                id
        );
    }

    static Plan plan(
            WorldgenSamplingContext context,
            int minY,
            int maxY,
            SnowtownPlanningArea.Id id
    ) {
        PlanKey key = new PlanKey(
                context.worldgenSeed(),
                SnowtownSettings.PLANNING_SETTINGS_VERSION,
                StructureAssetCatalog.revision(),
                id.areaX(),
                id.areaZ()
        );
        trimPlanCacheIfNeeded();
        return PLANS.computeIfAbsent(key, ignored -> buildPlanProfiled(context, minY, maxY, id));
    }

    private static void trimPlanCacheIfNeeded() {
        if (PLANS.size() > SnowtownSettings.MAX_CACHED_LOT_PLANS) {
            PLANS.clear();
        }
    }

    private static Plan buildPlanProfiled(
            WorldgenSamplingContext context,
            int minY,
            int maxY,
            SnowtownPlanningArea.Id id
    ) {
        if (!ENABLE_WORLDGEN_PROFILING) {
            return buildPlan(context, minY, maxY, id);
        }
        long startedAt = System.nanoTime();
        Plan plan = buildPlan(context, minY, maxY, id);
        MineTale.LOGGER.info(
                "Snowtown plan cold build ({}, {}) took {} ms, lots={}, roadBlocks={}, roadStats={}",
                id.areaX(),
                id.areaZ(),
                (System.nanoTime() - startedAt) / 1_000_000.0,
                plan.lots().size(),
                plan.roadPlan().roadBlockCount(),
                plan.timingSummary()
        );
        return plan;
    }

    private static Plan buildPlan(
            WorldgenSamplingContext context,
            int minY,
            int maxY,
            SnowtownPlanningArea.Id id
    ) {
        long startedAt = System.nanoTime();
        SnowtownPlanningArea.Analysis analysis = SnowtownPlanningArea.get(
                context,
                id,
                minY,
                maxY
        );
        if (analysis.components().isEmpty()) {
            return Plan.of(
                    List.of(),
                    SnowtownRoadPlanner.RoadPlan.empty(),
                    SnowtownDecorationPlanner.DecorationPlan.empty(),
                    PackingStats.empty(),
                    0L,
                    0L
            );
        }

        List<PlannedLot> plannedLots = new ArrayList<>();
        Map<Long, SnowtownPlanningArea.Cell> cellsByKey = analysis.cellLookup();
        SnowtownLotPacking.TerrainIndex cellTerrain = new SnowtownLotPacking.TerrainIndex(
                analysis.bounds(),
                cellsByKey.values()
        );
        Map<Integer, Set<Long>> componentCellKeys = componentCellKeys(analysis.components());
        Map<Integer, ComponentCenter> componentCenters = componentCenters(analysis.components());
        SnowtownTerrainGrid terraces = new SnowtownTerrainGrid(
                context,
                analysis.bounds()
        );
        long facingSeed = context.channelSeed(8242);
        long scatterStartedAt = System.nanoTime();
        List<SnowtownLotPacking.Pass> packingPasses = SnowtownLotPacking.passes(context, analysis);
        long scatterNanos = System.nanoTime() - scatterStartedAt;
        long lotValidationStartedAt = System.nanoTime();
        SnowtownLotPacking.BoundsIndex acceptedBounds = new SnowtownLotPacking.BoundsIndex();
        Map<Long, List<Direction>> facingOrderByCell = new HashMap<>();
        Map<Integer, PackingProgress> progressByComponent = new HashMap<>();
        PackingStatsAccumulator packingStats = new PackingStatsAccumulator();
        for (SnowtownLotPacking.Pass pass : packingPasses) {
            PackingProgress progress = progressByComponent.computeIfAbsent(
                    pass.component().componentIndex(),
                    ignored -> new PackingProgress(pass.component())
            );
            Set<Long> sameComponentCellKeys = componentCellKeys.getOrDefault(
                    pass.component().componentIndex(),
                    Set.of()
            );
            ComponentCenter center = componentCenters.getOrDefault(
                    pass.component().componentIndex(),
                    ComponentCenter.of(pass.component())
            );
            for (SnowtownPlanningArea.Cell cell : pass.candidates()) {
                if (progress.stageComplete(pass.stage())) {
                    break;
                }
                packingStats.candidateCells++;
                for (StructureAssetDefinition asset : pass.assetAttempts(cell)) {
                    if (progress.stageComplete(pass.stage())) {
                        break;
                    }
                    packingStats.assetAttempts++;
                    SnowtownLotPacking.Sample sample = new SnowtownLotPacking.Sample(
                            pass.component().componentIndex(),
                            asset,
                            cell,
                            cell.centerX(),
                            cell.surfaceBlockY(),
                            cell.centerZ(),
                            pass.stage()
                    );
                    PlannedLotVariants variantResult = buildPlannedLots(
                            sample,
                            cellsByKey,
                            cellTerrain,
                            sameComponentCellKeys,
                            center,
                            facingSeed,
                            facingOrderByCell,
                            analysis.bounds(),
                            acceptedBounds
                    );
                    packingStats.coarseTerrainRejected += variantResult.coarseRejected();
                    List<PlannedLot> variants = variantResult.lots();
                    if (variants.isEmpty()) {
                        if (!variantResult.hadBoundsCandidate()) {
                            packingStats.boundsRejected++;
                        }
                        continue;
                    }
                    boolean placed = false;
                    for (PlannedLot lot : variants) {
                        packingStats.terraceValidationChecks++;
                        if (!terraces.footprintFits(
                                lot.bounds(),
                                lot.sample().x(),
                                lot.sample().z()
                        )) {
                            packingStats.terraceValidationRejected++;
                            continue;
                        }
                        plannedLots.add(lot);
                        acceptedBounds.add(lot.packingBounds());
                        progress.accept(lot);
                        packingStats.acceptedLots++;
                        placed = true;
                        break;
                    }
                    if (placed) {
                        break;
                    }
                }
            }
        }
        long lotValidationNanos = System.nanoTime() - lotValidationStartedAt;
        List<PlannedLot> acceptedLots = List.copyOf(plannedLots);
        PackingStats frozenPackingStats = packingStats.freeze(progressByComponent);
        SnowtownRoadPlanner.RoadPlan roadPlan = SnowtownRoadPlanner.plan(
                analysis,
                roadPlannerLots(acceptedLots),
                terraces
        );
        SnowtownDecorationPlanner.DecorationPlan decorationPlan = SnowtownDecorationPlanner.plan(
                context,
                minY,
                maxY,
                analysis.bounds(),
                cellsByKey,
                decorationPlannerBuildings(acceptedLots),
                roadPlan
        );
        double elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000.0;
        if (!ENABLE_WORLDGEN_PROFILING && elapsedMillis >= 200.0) {
            MineTale.LOGGER.warn(
                    "Snowtown cold plan ({}, {}) took {} ms, lots={}, roadBlocks={}, roadStats={}",
                    id.areaX(),
                    id.areaZ(),
                    elapsedMillis,
                    acceptedLots.size(),
                    roadPlan.roadBlockCount(),
                    Plan.of(
                            acceptedLots,
                            roadPlan,
                            decorationPlan,
                            frozenPackingStats,
                            scatterNanos,
                            lotValidationNanos
                    ).timingSummary()
                            + ", terraceFacts=" + terraces.summary()
            );
        }
        return Plan.of(
                acceptedLots,
                roadPlan,
                decorationPlan,
                frozenPackingStats,
                scatterNanos,
                lotValidationNanos
        );
    }

    private static List<SnowtownDecorationPlanner.BuildingInput> decorationPlannerBuildings(
            List<PlannedLot> lots
    ) {
        List<SnowtownDecorationPlanner.BuildingInput> result = new ArrayList<>(lots.size());
        for (PlannedLot lot : lots) {
            result.add(new SnowtownDecorationPlanner.BuildingInput(
                    lot.anchorPos(),
                    lot.facing(),
                    lot.bounds()
            ));
        }
        return List.copyOf(result);
    }

    private static List<SnowtownRoadPlanner.LotInput> roadPlannerLots(List<PlannedLot> lots) {
        List<SnowtownRoadPlanner.LotInput> result = new ArrayList<>(lots.size());
        for (PlannedLot lot : lots) {
            result.add(new SnowtownRoadPlanner.LotInput(
                    lot.sample().componentIndex(),
                    lot.anchorPos(),
                    lot.facing(),
                    lot.bounds()
            ));
        }
        return List.copyOf(result);
    }

    private static Map<Integer, Set<Long>> componentCellKeys(List<SnowtownPlanningArea.Component> components) {
        Map<Integer, Set<Long>> result = new HashMap<>();
        for (SnowtownPlanningArea.Component component : components) {
            Set<Long> keys = new HashSet<>();
            for (SnowtownPlanningArea.Cell cell : component.cells()) {
                keys.add(SnowtownPlanningArea.cellKey(cell.cellX(), cell.cellZ()));
            }
            result.put(component.componentIndex(), Set.copyOf(keys));
        }
        return Map.copyOf(result);
    }

    private static Map<Integer, ComponentCenter> componentCenters(List<SnowtownPlanningArea.Component> components) {
        Map<Integer, ComponentCenter> result = new HashMap<>();
        for (SnowtownPlanningArea.Component component : components) {
            result.put(component.componentIndex(), ComponentCenter.of(component));
        }
        return Map.copyOf(result);
    }

    private static PlannedLotVariants buildPlannedLots(
            SnowtownLotPacking.Sample sample,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            SnowtownLotPacking.TerrainIndex cellTerrain,
            Set<Long> sameComponentCellKeys,
            ComponentCenter center,
            long facingSeed,
            Map<Long, List<Direction>> facingOrderByCell,
            SnowtownPlanningArea.Bounds planningBounds,
            SnowtownLotPacking.BoundsIndex acceptedBounds
    ) {
        StructureAssetDefinition asset = sample.asset();
        ScannedTemplateMarker anchor = AssetPlacer.firstAnchor(asset).orElse(null);
        if (anchor == null) {
            return PlannedLotVariants.empty(false, 0);
        }
        long cellKey = SnowtownPlanningArea.cellKey(sample.cell().cellX(), sample.cell().cellZ());
        BlockPos desiredAnchor = new BlockPos(sample.x(), sample.surfaceY() + 1, sample.z());
        Map<Direction, PlannedLot> available = new HashMap<>();
        Direction[] candidateFacings = asset.placement().canRotate()
                ? HORIZONTAL_DIRECTIONS
                : new Direction[]{horizontal(anchor.facing())};
        for (Direction facing : candidateFacings) {
            AssetPlacer.anchorAt(asset, anchor, desiredAnchor, facing).ifPresent(placement -> {
                BoundingBox packingBounds = sample.stage().reserve(placement.bounds(), facing);
                if (boundsInsideCore(planningBounds, placement.bounds())
                        && !acceptedBounds.collides(packingBounds)) {
                    available.put(facing, new PlannedLot(
                            sample,
                            desiredAnchor,
                            facing,
                            placement,
                            packingBounds
                    ));
                }
            });
        }
        if (available.isEmpty()) {
            return PlannedLotVariants.empty(false, 0);
        }
        int coarseRejected = 0;
        List<Direction> rejectedDirections = new ArrayList<>();
        for (Map.Entry<Direction, PlannedLot> entry : available.entrySet()) {
            if (!coarseFootprintFitsTerrain(entry.getValue(), cellTerrain)) {
                rejectedDirections.add(entry.getKey());
                coarseRejected++;
            }
        }
        for (Direction rejectedDirection : rejectedDirections) {
            available.remove(rejectedDirection);
        }
        if (available.isEmpty()) {
            return PlannedLotVariants.empty(true, coarseRejected);
        }
        List<Direction> facingOrder = facingOrderByCell.computeIfAbsent(
                cellKey,
                ignored -> rankedFacings(sample, cellsByKey, sameComponentCellKeys, center, facingSeed)
        );
        List<PlannedLot> result = new ArrayList<>(available.size());
        for (Direction facing : facingOrder) {
            PlannedLot lot = available.get(facing);
            if (lot != null) {
                result.add(lot);
            }
        }
        return new PlannedLotVariants(List.copyOf(result), true, coarseRejected);
    }

    private static boolean boundsInsideCore(SnowtownPlanningArea.Bounds planningBounds, BoundingBox bounds) {
        return planningBounds.containsCoreBlock(bounds.minX(), bounds.minZ())
                && planningBounds.containsCoreBlock(bounds.maxX(), bounds.maxZ());
    }

    private static boolean coarseFootprintFitsTerrain(
            PlannedLot lot,
            SnowtownLotPacking.TerrainIndex cellTerrain
    ) {
        return cellTerrain.footprintFits(
                lot.bounds(),
                lot.sample().surfaceY(),
                lot.sample().asset().footprint()
        );
    }

    private static List<Direction> rankedFacings(
            SnowtownLotPacking.Sample sample,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            Set<Long> sameComponentCellKeys,
            ComponentCenter center,
            long facingSeed
    ) {
        List<DirectionScore> scores = new ArrayList<>(HORIZONTAL_DIRECTIONS.length);
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            double score = frontageScore(sample, cellsByKey, sameComponentCellKeys, direction)
                    + opennessScore(sample, cellsByKey, direction)
                    * SnowtownSettings.LOT_OPENNESS_FACING_WEIGHT
                    + componentCenterFacingScore(sample, center, direction);
            scores.add(new DirectionScore(direction, score));
        }
        return scores.stream()
                .sorted(Comparator
                        .comparingDouble(DirectionScore::score).reversed()
                        .thenComparingInt(score -> WorldgenMath.hash(
                                sample.x(),
                                sample.componentIndex() * 31 + score.direction().ordinal(),
                                sample.z(),
                                facingSeed
                        )))
                .map(DirectionScore::direction)
                .toList();
    }

    private static double frontageScore(
            SnowtownLotPacking.Sample sample,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            Set<Long> sameComponentCellKeys,
            Direction direction
    ) {
        return nearFrontageScore(sample, cellsByKey, direction)
                + cellFrontageScore(sample, cellsByKey, sameComponentCellKeys, direction);
    }

    private static double nearFrontageScore(
            SnowtownLotPacking.Sample sample,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            Direction direction
    ) {
        double score = 0.0;
        Direction right = rightOf(direction);
        for (int forward = 1; forward <= SnowtownSettings.LOT_FRONTAGE_NEAR_SAMPLE_DISTANCE; forward++) {
            for (int lateral = -SnowtownSettings.LOT_FRONTAGE_NEAR_HALF_WIDTH;
                 lateral <= SnowtownSettings.LOT_FRONTAGE_NEAR_HALF_WIDTH;
                 lateral++) {
                int x = sample.x() + direction.getStepX() * forward + right.getStepX() * lateral;
                int z = sample.z() + direction.getStepZ() * forward + right.getStepZ() * lateral;
                score += nearFrontageColumnScore(sample.surfaceY(), cellAt(cellsByKey, x, z));
            }
        }
        return score;
    }

    private static double nearFrontageColumnScore(int floorY, SnowtownPlanningArea.Cell facts) {
        if (facts == null) {
            return -160.0;
        }
        int surfaceY = facts.surfaceBlockY();
        int delta = surfaceY - floorY;
        if (delta <= -SnowtownSettings.LOT_FRONTAGE_CLIFF_DROP_BLOCKS) {
            return -800.0;
        }
        if (Math.abs(delta) > SnowtownSettings.LOT_FRONTAGE_MAX_SURFACE_DELTA) {
            return -180.0;
        }
        return 40.0 - Math.abs(delta) * 8.0;
    }

    private static double cellFrontageScore(
            SnowtownLotPacking.Sample sample,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            Set<Long> sameComponentCellKeys,
            Direction direction
    ) {
        double score = 0.0;
        Direction right = rightOf(direction);
        int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
        for (int forward = cellSize; forward <= SnowtownSettings.LOT_FRONTAGE_CELL_SAMPLE_DISTANCE; forward += cellSize) {
            for (int lateralCell = -SnowtownSettings.LOT_FRONTAGE_CELL_HALF_WIDTH;
                 lateralCell <= SnowtownSettings.LOT_FRONTAGE_CELL_HALF_WIDTH;
                 lateralCell++) {
                int lateral = lateralCell * cellSize;
                int x = sample.x() + direction.getStepX() * forward + right.getStepX() * lateral;
                int z = sample.z() + direction.getStepZ() * forward + right.getStepZ() * lateral;
                long key = SnowtownPlanningArea.cellKey(
                        Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                        Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE)
                );
                SnowtownPlanningArea.Cell cell = cellsByKey.get(key);
                if (cell == null) {
                    score -= 40.0;
                    continue;
                }
                if (!sameComponentCellKeys.contains(key)) {
                    score -= 90.0;
                    continue;
                }
                int delta = cell.surfaceBlockY() - sample.surfaceY();
                if (delta <= -SnowtownSettings.LOT_FRONTAGE_CLIFF_DROP_BLOCKS) {
                    score -= 180.0;
                } else if (Math.abs(delta) > SnowtownSettings.LOT_FRONTAGE_MAX_SURFACE_DELTA) {
                    score -= 60.0;
                } else {
                    score += 24.0 - Math.abs(delta) * 4.0;
                }
            }
        }
        return score;
    }

    private static double componentCenterFacingScore(
            SnowtownLotPacking.Sample sample,
            ComponentCenter center,
            Direction direction
    ) {
        double dx = center.x() - sample.x();
        double dz = center.z() - sample.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 1.0E-6) {
            return 0.0;
        }
        double dot = (direction.getStepX() * dx + direction.getStepZ() * dz) / length;
        return dot * SnowtownSettings.LOT_COMPONENT_CENTER_FACING_WEIGHT;
    }

    private static double opennessScore(
            SnowtownLotPacking.Sample sample,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            Direction direction
    ) {
        double score = 0.0;
        for (int step = 1; step <= SnowtownSettings.LOT_FACING_SAMPLE_DISTANCE; step++) {
            int x = sample.x() + direction.getStepX() * step;
            int z = sample.z() + direction.getStepZ() * step;
            SnowtownPlanningArea.Cell cell = cellAt(cellsByKey, x, z);
            if (cell == null) {
                score -= 64.0;
                continue;
            }
            score += Math.max(0.0, cell.ceilingY() - cell.surfaceBlockY());
            score -= cell.pillarMaskAtSurface() * 8.0;
        }
        return score;
    }

    private static SnowtownPlanningArea.Cell cellAt(Map<Long, SnowtownPlanningArea.Cell> cellsByKey, int x, int z) {
        return cellsByKey.get(SnowtownPlanningArea.cellKey(
                Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE)
        ));
    }

    private static Direction rightOf(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private static Direction horizontal(Direction direction) {
        return direction.getAxis() == Direction.Axis.Y ? Direction.SOUTH : direction;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static final class PackingProgress {
        private final long buildableArea;
        private final int acceptedLimit;
        private int acceptedLots;
        private int acceptedLandmarks;
        private long coveredArea;

        private PackingProgress(SnowtownPlanningArea.Component component) {
            int cellSize = SnowtownSettings.PLANNING_AREA_CELL_SIZE;
            this.buildableArea = (long) component.coreCellCount() * cellSize * cellSize;
            int areaLimit = Math.max(
                    1,
                    Math.ceilDiv(
                            component.coreCellCount(),
                            SnowtownSettings.LOT_PACKING_CORE_CELLS_PER_ACCEPTED_LIMIT
                    )
            );
            this.acceptedLimit = Math.min(SnowtownSettings.LOT_PACKING_MAX_ACCEPTED_PER_COMPONENT, areaLimit);
        }

        private boolean stageComplete(SnowtownLotPacking.Stage stage) {
            if (acceptedLots >= acceptedLimit) {
                return true;
            }
            return switch (stage) {
                case LANDMARK -> acceptedLandmarks >= SnowtownSettings.LOT_PACKING_MAX_LANDMARKS_PER_COMPONENT;
                case REGULAR -> coverage() >= SnowtownSettings.LOT_PACKING_REGULAR_TARGET_COVERAGE;
                case INFILL -> coverage() >= SnowtownSettings.LOT_PACKING_FINAL_TARGET_COVERAGE;
            };
        }

        private void accept(PlannedLot lot) {
            acceptedLots++;
            if (lot.sample().stage() == SnowtownLotPacking.Stage.LANDMARK) {
                acceptedLandmarks++;
            }
            BoundingBox bounds = lot.bounds();
            coveredArea += (long) (bounds.maxX() - bounds.minX() + 1)
                    * (bounds.maxZ() - bounds.minZ() + 1);
        }

        private double coverage() {
            return buildableArea == 0L ? 0.0 : coveredArea / (double) buildableArea;
        }
    }

    private static final class PackingStatsAccumulator {
        private long candidateCells;
        private long assetAttempts;
        private long boundsRejected;
        private long coarseTerrainRejected;
        private long terraceValidationChecks;
        private long terraceValidationRejected;
        private long acceptedLots;

        private PackingStats freeze(Map<Integer, PackingProgress> progressByComponent) {
            long buildableArea = 0L;
            long coveredArea = 0L;
            for (PackingProgress progress : progressByComponent.values()) {
                buildableArea += progress.buildableArea;
                coveredArea += progress.coveredArea;
            }
            return new PackingStats(
                    progressByComponent.size(),
                    candidateCells,
                    assetAttempts,
                    boundsRejected,
                    coarseTerrainRejected,
                    terraceValidationChecks,
                    terraceValidationRejected,
                    acceptedLots,
                    buildableArea,
                    coveredArea
            );
        }
    }

    private record PackingStats(
            int componentCount,
            long candidateCells,
            long assetAttempts,
            long boundsRejected,
            long coarseTerrainRejected,
            long terraceValidationChecks,
            long terraceValidationRejected,
            long acceptedLots,
            long buildableArea,
            long coveredArea
    ) {
        static PackingStats empty() {
            return new PackingStats(0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        String summary() {
            double coverage = buildableArea == 0L ? 0.0 : coveredArea / (double) buildableArea;
            return "packingComponents=" + componentCount
                    + ", candidateCells=" + candidateCells
                    + ", assetAttempts=" + assetAttempts
                    + ", boundsRejected=" + boundsRejected
                    + ", coarseTerrainRejected=" + coarseTerrainRejected
                    + ", terraceValidationChecks=" + terraceValidationChecks
                    + ", terraceValidationRejected=" + terraceValidationRejected
                    + ", acceptedLots=" + acceptedLots
                    + ", coverage=" + String.format(java.util.Locale.ROOT, "%.3f", coverage);
        }
    }

    record PlannedLot(
            SnowtownLotPacking.Sample sample,
            BlockPos anchorPos,
            Direction facing,
            AssetPlacementPlan placement,
            BoundingBox packingBounds
    ) {
        BoundingBox bounds() {
            return this.placement.bounds();
        }
    }

    private record PlannedLotVariants(
            List<PlannedLot> lots,
            boolean hadBoundsCandidate,
            int coarseRejected
    ) {
        static PlannedLotVariants empty(boolean hadBoundsCandidate, int coarseRejected) {
            return new PlannedLotVariants(List.of(), hadBoundsCandidate, coarseRejected);
        }
    }

    private record DirectionScore(Direction direction, double score) {}

    private record ComponentCenter(double x, double z) {
        static ComponentCenter of(SnowtownPlanningArea.Component component) {
            double x = component.cells().stream()
                    .mapToDouble(SnowtownPlanningArea.Cell::centerX)
                    .average()
                    .orElse(0.0);
            double z = component.cells().stream()
                    .mapToDouble(SnowtownPlanningArea.Cell::centerZ)
                    .average()
                    .orElse(0.0);
            return new ComponentCenter(x, z);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private record PlanKey(
            long worldSeed,
            int planningSettingsVersion,
            long assetCatalogRevision,
            int areaX,
            int areaZ
    ) {}

    record Plan(
            List<PlannedLot> lots,
            SnowtownRoadPlanner.RoadPlan roadPlan,
            SnowtownDecorationPlanner.DecorationPlan decorationPlan,
            Map<Long, List<PlannedLot>> lotsByChunk,
            PackingStats packingStats,
            long scatterNanos,
            long lotValidationNanos
    ) {
        static Plan of(
                List<PlannedLot> lots,
                SnowtownRoadPlanner.RoadPlan roadPlan,
                SnowtownDecorationPlanner.DecorationPlan decorationPlan,
                PackingStats packingStats,
                long scatterNanos,
                long lotValidationNanos
        ) {
            return new Plan(
                    lots,
                    roadPlan,
                    decorationPlan,
                    bucketLotsByChunk(lots),
                    packingStats,
                    scatterNanos,
                    lotValidationNanos
            );
        }

        List<PlannedLot> lotsForChunk(ChunkAccess chunk) {
            int chunkX = Math.floorDiv(chunk.getPos().getMinBlockX(), 16);
            int chunkZ = Math.floorDiv(chunk.getPos().getMinBlockZ(), 16);
            return lotsByChunk.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
        }

        String timingSummary() {
            return "scatterMs=" + nanosToMillis(scatterNanos)
                    + ", lotValidationMs=" + nanosToMillis(lotValidationNanos)
                    + ", " + packingStats.summary()
                    + ", " + roadPlan.stats().summary()
                    + ", decoration=" + decorationPlan.summary();
        }

        private static Map<Long, List<PlannedLot>> bucketLotsByChunk(List<PlannedLot> lots) {
            if (lots.isEmpty()) {
                return Map.of();
            }
            Map<Long, List<PlannedLot>> mutable = new HashMap<>();
            for (PlannedLot lot : lots) {
                int minChunkX = Math.floorDiv(lot.bounds().minX(), 16);
                int maxChunkX = Math.floorDiv(lot.bounds().maxX(), 16);
                int minChunkZ = Math.floorDiv(lot.bounds().minZ(), 16);
                int maxChunkZ = Math.floorDiv(lot.bounds().maxZ(), 16);
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                        mutable.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>()).add(lot);
                    }
                }
            }
            Map<Long, List<PlannedLot>> immutable = new HashMap<>();
            for (Map.Entry<Long, List<PlannedLot>> entry : mutable.entrySet()) {
                immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(immutable);
        }
    }

}
