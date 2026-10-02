package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.asset.AssetPlacer;
import cn.jehorstudio.minetale.dimension.worldgen.asset.AssetPlacer.AssetPlacementPlan;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetCatalog;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.StructureAssetTransform;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinColumnFacts;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinNaturalTerrainFactsKernel;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// 在 accepted lots 与最终道路冻结后统一规划灯具
final class SnowtownDecorationPlanner {
    private static final ResourceLocation FOUR_WAY_LIGHT_ID = lightId("four_way_street_lamp");

    // 这个hanging指的是落地支架上有悬挂灯笼，不要误以为是用来挂在建筑上的灯
    private static final ResourceLocation DIRECTIONAL_LIGHT_ID = lightId("hanging_street_lamp");

    private static final ResourceLocation STONE_POST_LIGHT_ID = lightId("stone_post_lamp");
    private static final ResourceLocation TUFF_POST_LIGHT_ID = lightId("tuff_post_lamp");
    private static final Vec3i FOUR_WAY_TEMPLATE_SIZE = new Vec3i(5, 7, 5);
    private static final int FOUR_WAY_PRIORITY_ROAD = 0;
    private static final int FOUR_WAY_PRIORITY_BUILDING_GAP = 1;
    private static final int POST_PRIORITY_BUILDING_FRONT = 0;
    private static final int POST_PRIORITY_ROADSIDE = 1;

    private static final Direction[] CARDINAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };
    private static final int[][] JUNCTION_DIRECTIONS = {
            {0, -1},
            {1, -1},
            {1, 0},
            {1, 1},
            {0, 1},
            {-1, 1},
            {-1, 0},
            {-1, -1}
    };

    private SnowtownDecorationPlanner() {}

    static DecorationPlan plan(
            WorldgenSamplingContext context,
            int minY,
            int maxY,
            SnowtownPlanningArea.Bounds bounds,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            List<BuildingInput> buildings,
            SnowtownRoadPlanner.RoadPlan roadPlan
    ) {
        RainbowCakeModel model = RainbowCakeModel.create(context.worldgenSeed());
        return plan(
                context,
                bounds,
                cellsByKey,
                buildings,
                roadPlan,
                LightAssets.fromCatalog(),
                (x, z) -> naturalSurfaceY(context, model, x, z, minY, maxY)
        );
    }

    static DecorationPlan plan(
            WorldgenSamplingContext context,
            SnowtownPlanningArea.Bounds bounds,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            List<BuildingInput> buildings,
            SnowtownRoadPlanner.RoadPlan roadPlan,
            LightAssets assets,
            SurfaceSampler surfaceSampler
    ) {
        long startedAt = System.nanoTime();
        RoadIndex roads = RoadIndex.of(roadPlan);
        if (!assets.hasAny()) {
            return DecorationPlan.empty(System.nanoTime() - startedAt);
        }

        HorizontalBoundsIndex buildingIndex = new HorizontalBoundsIndex();
        HorizontalBoundsIndex postBuildingIndex = new HorizontalBoundsIndex();
        for (BuildingInput building : buildings) {
            buildingIndex.add(building.bounds(), 0);
            postBuildingIndex.add(building.bounds(), SnowtownSettings.DECORATION_POST_BUILDING_CLEARANCE);
        }

        long fourWaySeed = context.channelSeed(8244);
        List<FourWayPlacement> fourWayLights = selectFourWayLights(
                bounds,
                cellsByKey,
                buildings,
                roads,
                buildingIndex,
                assets.fourWay(),
                surfaceSampler,
                fourWaySeed
        );
        List<DirectionalPlacement> directionalLights = selectDirectionalLights(
                bounds,
                cellsByKey,
                buildings,
                roads,
                postBuildingIndex,
                fourWayLights,
                assets.directional(),
                surfaceSampler,
                context.channelSeed(8245)
        );
        List<AssetPlacementPlan> postLights = selectPostLights(
                bounds,
                cellsByKey,
                buildings,
                roads,
                postBuildingIndex,
                fourWayLights,
                directionalLights,
                assets,
                surfaceSampler,
                context.channelSeed(8246),
                context.channelSeed(8247)
        );

        List<AssetPlacementPlan> placements = new ArrayList<>(
                fourWayLights.size() + directionalLights.size() + postLights.size()
        );
        fourWayLights.forEach(light -> placements.add(light.placement()));
        directionalLights.forEach(light -> placements.add(light.placement()));
        placements.addAll(postLights);
        return DecorationPlan.of(
                placements,
                fourWayLights.size(),
                directionalLights.size(),
                postLights.size(),
                System.nanoTime() - startedAt
        );
    }

    private static OptionalInt naturalSurfaceY(
            WorldgenSamplingContext context,
            RainbowCakeModel model,
            int x,
            int z,
            int minY,
            int maxY
    ) {
        var maybeFacts = SnowdinNaturalTerrainFactsKernel.sample(context, model, x, z, minY, maxY);
        if (maybeFacts.isEmpty()) {
            return OptionalInt.empty();
        }
        SnowdinColumnFacts facts = maybeFacts.get();
        if (facts.surfaceBlockY().isEmpty()
                || facts.terraceMask() < SnowtownSettings.CELL_TERRACE_MASK_MIN
                || !Double.isFinite(facts.pillarMaskAtSurface())
                || facts.pillarMaskAtSurface() > SnowtownSettings.CELL_PILLAR_MASK_MAX) {
            return OptionalInt.empty();
        }
        return facts.surfaceBlockY();
    }

    private static List<FourWayPlacement> selectFourWayLights(
            SnowtownPlanningArea.Bounds bounds,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            List<BuildingInput> buildings,
            RoadIndex roads,
            HorizontalBoundsIndex buildingIndex,
            StructureAssetDefinition asset,
            SurfaceSampler surfaceSampler,
            long seed
    ) {
        if (asset == null) {
            return List.of();
        }

        List<FourWayCandidate> candidates = new ArrayList<>();
        for (SnowtownRoadPlanner.RoadSurfaceBlock road : roads.blocks()) {
            int[] strengths = directionStrengths(roads, road);
            int connectedDirections = connectedDirectionRuns(strengths);
            if (connectedDirections < 3) {
                continue;
            }
            int roadDensity = roadDensity(roads, road.x(), road.y(), road.z(), 2);
            if (roadDensity < SnowtownSettings.DECORATION_JUNCTION_MIN_ROAD_BLOCKS) {
                continue;
            }

            int balancePenalty = 0;
            for (int index = 0; index < strengths.length / 2; index++) {
                balancePenalty += Math.abs(strengths[index] - strengths[index + strengths.length / 2]);
            }
            int score = connectedDirections * 1_000 + roadDensity * 10 - balancePenalty;
            double rank = WorldgenMath.hashToUnit(road.x(), road.y(), road.z(), seed);
            candidates.add(new FourWayCandidate(
                    road.x(),
                    road.y() + 1,
                    road.z(),
                    FOUR_WAY_PRIORITY_ROAD,
                    score,
                    rank,
                    false
            ));
        }
        addBuildingGapCandidates(bounds, cellsByKey, buildings, buildingIndex, asset, seed, candidates);

        candidates.sort(Comparator
                .comparingInt(FourWayCandidate::priority)
                .thenComparing(Comparator.comparingInt(FourWayCandidate::score).reversed())
                .thenComparingDouble(FourWayCandidate::rank)
                .thenComparingInt(FourWayCandidate::z)
                .thenComparingInt(FourWayCandidate::x));
        PointSpacingIndex spacing = new PointSpacingIndex(SnowtownSettings.DECORATION_JUNCTION_MIN_SPACING);
        List<FourWayPlacement> selected = new ArrayList<>();
        for (FourWayCandidate candidate : candidates) {
            if (!spacing.canAdd(candidate.x(), candidate.z())) {
                continue;
            }
            int placementY = candidate.y();
            if (candidate.requiresSurfaceSupport()) {
                OptionalInt surfaceY = supportedThreeByThreeSurface(
                        surfaceSampler,
                        candidate.x(),
                        candidate.z(),
                        candidate.y() - 1
                );
                if (surfaceY.isEmpty()) {
                    continue;
                }
                placementY = surfaceY.getAsInt() + 1;
            }
            AssetPlacementPlan placement = centeredPlacement(asset, candidate.x(), placementY, candidate.z());
            BoundingBox footprint = centeredFootprint(asset, candidate.x(), placementY, candidate.z());
            if (!insideCore(bounds, placement.bounds()) || buildingIndex.intersects(footprint)) {
                continue;
            }
            spacing.add(candidate.x(), candidate.z());
            selected.add(new FourWayPlacement(
                    new BlockPos(candidate.x(), placementY, candidate.z()),
                    placement
            ));
        }
        return List.copyOf(selected);
    }

    private static void addBuildingGapCandidates(
            SnowtownPlanningArea.Bounds bounds,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            List<BuildingInput> buildings,
            HorizontalBoundsIndex buildingIndex,
            StructureAssetDefinition asset,
            long seed,
            List<FourWayCandidate> candidates
    ) {
        int maximumGap = SnowtownSettings.DECORATION_JUNCTION_MIN_SPACING * 2;
        long maximumGapSquared = (long) maximumGap * maximumGap;
        Map<Long, FourWayCandidate> uniqueCandidates = new HashMap<>();
        for (int firstIndex = 0; firstIndex < buildings.size(); firstIndex++) {
            BuildingInput first = buildings.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < buildings.size(); secondIndex++) {
                BuildingInput second = buildings.get(secondIndex);
                int firstGroundY = first.anchor().getY() - 1;
                int secondGroundY = second.anchor().getY() - 1;
                if (Math.abs(firstGroundY - secondGroundY) > 1) {
                    continue;
                }
                int gapX = emptyColumnsBetween(
                        first.bounds().minX(), first.bounds().maxX(),
                        second.bounds().minX(), second.bounds().maxX()
                );
                int gapZ = emptyColumnsBetween(
                        first.bounds().minZ(), first.bounds().maxZ(),
                        second.bounds().minZ(), second.bounds().maxZ()
                );
                long gapSquared = (long) gapX * gapX + (long) gapZ * gapZ;
                if ((gapX == 0 && gapZ == 0) || gapSquared > maximumGapSquared) {
                    continue;
                }
                int x = betweenAxis(
                        first.bounds().minX(), first.bounds().maxX(),
                        second.bounds().minX(), second.bounds().maxX()
                );
                int z = betweenAxis(
                        first.bounds().minZ(), first.bounds().maxZ(),
                        second.bounds().minZ(), second.bounds().maxZ()
                );
                int expectedGroundY = Math.floorDiv(firstGroundY + secondGroundY, 2);
                SnowtownPlanningArea.Cell cell = cellsByKey.get(SnowtownPlanningArea.cellKey(
                        Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                        Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE)
                ));
                BoundingBox footprint = centeredFootprint(asset, x, expectedGroundY + 1, z);
                if (!bounds.containsCoreBlock(x, z)
                        || cell == null
                        || Math.abs(cell.surfaceBlockY() - expectedGroundY) > 1
                        || buildingIndex.intersects(footprint)) {
                    continue;
                }
                int score = horizontalArea(first.bounds()) + horizontalArea(second.bounds()) - (int) gapSquared;
                double rank = WorldgenMath.hashToUnit(x, expectedGroundY, z, seed);
                FourWayCandidate candidate = new FourWayCandidate(
                        x,
                        expectedGroundY + 1,
                        z,
                        FOUR_WAY_PRIORITY_BUILDING_GAP,
                        score,
                        rank,
                        true
                );
                long key = blockColumnKey(x, z);
                FourWayCandidate previous = uniqueCandidates.get(key);
                if (previous == null
                        || candidate.score() > previous.score()
                        || (candidate.score() == previous.score() && candidate.rank() < previous.rank())) {
                    uniqueCandidates.put(key, candidate);
                }
            }
        }
        candidates.addAll(uniqueCandidates.values());
    }

    private static int emptyColumnsBetween(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) {
            return secondMin - firstMax - 1;
        }
        if (secondMax < firstMin) {
            return firstMin - secondMax - 1;
        }
        return 0;
    }

    private static int betweenAxis(int firstMin, int firstMax, int secondMin, int secondMax) {
        if (firstMax < secondMin) {
            return Math.floorDiv(firstMax + secondMin, 2);
        }
        if (secondMax < firstMin) {
            return Math.floorDiv(secondMax + firstMin, 2);
        }
        return Math.floorDiv(Math.max(firstMin, secondMin) + Math.min(firstMax, secondMax), 2);
    }

    private static OptionalInt supportedThreeByThreeSurface(
            SurfaceSampler surfaceSampler,
            int centerX,
            int centerZ,
            int expectedGroundY
    ) {
        OptionalInt center = surfaceSampler.surfaceBlockY(centerX, centerZ);
        if (center.isEmpty() || Math.abs(center.getAsInt() - expectedGroundY) > 1) {
            return OptionalInt.empty();
        }
        for (int z = centerZ - 1; z <= centerZ + 1; z++) {
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                if (x == centerX && z == centerZ) {
                    continue;
                }
                OptionalInt surfaceY = surfaceSampler.surfaceBlockY(x, z);
                if (surfaceY.isEmpty() || Math.abs(surfaceY.getAsInt() - center.getAsInt()) > 1) {
                    return OptionalInt.empty();
                }
            }
        }
        return center;
    }

    private static int[] directionStrengths(
            RoadIndex roads,
            SnowtownRoadPlanner.RoadSurfaceBlock center
    ) {
        int probe = SnowtownSettings.DECORATION_JUNCTION_PROBE_DISTANCE;
        int[] result = new int[JUNCTION_DIRECTIONS.length];
        for (int index = 0; index < JUNCTION_DIRECTIONS.length; index++) {
            int directionX = JUNCTION_DIRECTIONS[index][0];
            int directionZ = JUNCTION_DIRECTIONS[index][1];
            int probeX = center.x() + directionX * probe;
            int probeZ = center.z() + directionZ * probe;
            int perpendicularX = -directionZ;
            int perpendicularZ = directionX;
            for (int lateral = -1; lateral <= 1; lateral++) {
                SnowtownRoadPlanner.RoadSurfaceBlock road = roads.get(
                        probeX + perpendicularX * lateral,
                        probeZ + perpendicularZ * lateral
                );
                if (road != null && road.y() == center.y()) {
                    result[index]++;
                }
            }
        }
        return result;
    }

    private static int connectedDirectionRuns(int[] strengths) {
        // 三格宽道路会在相邻扇区重复命中，同方向命中必须先合并再判断路口。
        boolean allConnected = true;
        int runs = 0;
        for (int index = 0; index < strengths.length; index++) {
            boolean connected = strengths[index] > 0;
            boolean previousConnected = strengths[(index + strengths.length - 1) % strengths.length] > 0;
            allConnected &= connected;
            if (connected && !previousConnected) {
                runs++;
            }
        }
        return allConnected ? 4 : runs;
    }

    private static int roadDensity(RoadIndex roads, int centerX, int y, int centerZ, int radius) {
        int result = 0;
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                SnowtownRoadPlanner.RoadSurfaceBlock road = roads.get(x, z);
                result += road != null && road.y() == y ? 1 : 0;
            }
        }
        return result;
    }

    private static List<DirectionalPlacement> selectDirectionalLights(
            SnowtownPlanningArea.Bounds bounds,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            List<BuildingInput> buildings,
            RoadIndex roads,
            HorizontalBoundsIndex buildingIndex,
            List<FourWayPlacement> fourWayLights,
            StructureAssetDefinition asset,
            SurfaceSampler surfaceSampler,
            long seed
    ) {
        if (asset == null) {
            return List.of();
        }
        ScannedTemplateMarker anchor = AssetPlacer.firstAnchor(asset).orElse(null);
        if (anchor == null) {
            return List.of();
        }

        List<DirectionalCandidate> candidates = new ArrayList<>();
        int minimumOffset = SnowtownSettings.DECORATION_POST_BUILDING_CLEARANCE + 1;
        int maximumOffset = minimumOffset
                + Math.max(asset.footprint().width(), asset.footprint().depth());
        for (BuildingInput building : buildings) {
            double rank = WorldgenMath.hashToUnit(
                    building.anchor().getX(),
                    building.anchor().getY(),
                    building.anchor().getZ(),
                    seed
            );
            if (rank >= directionalChance(building.bounds())) {
                continue;
            }

            BoundingBox buildingBounds = building.bounds();
            int facadeX = Math.max(
                    buildingBounds.minX(),
                    Math.min(building.anchor().getX(), buildingBounds.maxX())
            );
            int facadeZ = Math.max(
                    buildingBounds.minZ(),
                    Math.min(building.anchor().getZ(), buildingBounds.maxZ())
            );
            switch (building.facing()) {
                case NORTH -> facadeZ = buildingBounds.minZ();
                case EAST -> facadeX = buildingBounds.maxX();
                case SOUTH -> facadeZ = buildingBounds.maxZ();
                case WEST -> facadeX = buildingBounds.minX();
                default -> {
                    continue;
                }
            }
            int expectedGroundY = building.anchor().getY() - 1;
            for (int offset = minimumOffset; offset <= maximumOffset; offset++) {
                int x = facadeX + building.facing().getStepX() * offset;
                int z = facadeZ + building.facing().getStepZ() * offset;
                if (!bounds.containsCoreBlock(x, z) || nearFourWayLight(x, z, fourWayLights)) {
                    continue;
                }
                SnowtownPlanningArea.Cell cell = cellsByKey.get(SnowtownPlanningArea.cellKey(
                        Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                        Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE)
                ));
                if (cell == null || Math.abs(cell.surfaceBlockY() - expectedGroundY) > 1) {
                    continue;
                }
                OptionalInt surfaceY = surfaceSampler.surfaceBlockY(x, z);
                if (surfaceY.isEmpty() || Math.abs(surfaceY.getAsInt() - expectedGroundY) > 1) {
                    continue;
                }
                BlockPos desiredAnchor = new BlockPos(x, surfaceY.getAsInt() + 1, z);
                AssetPlacementPlan placement = AssetPlacer.anchorAt(
                        asset,
                        anchor,
                        desiredAnchor,
                        building.facing()
                ).orElse(null);
                if (placement == null
                        || !insideCore(bounds, placement.bounds())
                        || buildingIndex.intersects(placement.bounds())
                        || intersectsRoad(roads, placement.bounds())
                        || !supportedGroundFootprint(
                                surfaceSampler,
                                placement.bounds(),
                                surfaceY.getAsInt(),
                                expectedGroundY
                        )) {
                    continue;
                }
                candidates.add(new DirectionalCandidate(desiredAnchor, rank, placement));
                break;
            }
        }

        candidates.sort(Comparator
                .comparingDouble(DirectionalCandidate::rank)
                .thenComparingInt(candidate -> candidate.anchor().getZ())
                .thenComparingInt(candidate -> candidate.anchor().getX()));
        PointSpacingIndex spacing = new PointSpacingIndex(SnowtownSettings.DECORATION_POST_MIN_SPACING);
        List<DirectionalPlacement> result = new ArrayList<>();
        for (DirectionalCandidate candidate : candidates) {
            if (spacing.tryAdd(candidate.anchor().getX(), candidate.anchor().getZ())) {
                result.add(new DirectionalPlacement(candidate.anchor(), candidate.placement()));
            }
        }
        return List.copyOf(result);
    }

    private static double directionalChance(BoundingBox bounds) {
        int area = horizontalArea(bounds);
        double chance = SnowtownSettings.DECORATION_DIRECTIONAL_BASE_CHANCE;
        if (area >= SnowtownSettings.LOT_PACKING_LANDMARK_MIN_AREA) {
            chance *= 1.75;
        } else if (area <= SnowtownSettings.LOT_PACKING_INFILL_MAX_AREA) {
            chance *= 0.55;
        }
        return Math.min(1.0, chance);
    }

    private static boolean supportedGroundFootprint(
            SurfaceSampler surfaceSampler,
            BoundingBox footprint,
            int placementGroundY,
            int expectedGroundY
    ) {
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                OptionalInt surfaceY = surfaceSampler.surfaceBlockY(x, z);
                if (surfaceY.isEmpty()
                        || surfaceY.getAsInt() != placementGroundY
                        || Math.abs(surfaceY.getAsInt() - expectedGroundY) > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean intersectsRoad(RoadIndex roads, BoundingBox footprint) {
        for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                if (roads.contains(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<AssetPlacementPlan> selectPostLights(
            SnowtownPlanningArea.Bounds bounds,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            List<BuildingInput> buildings,
            RoadIndex roads,
            HorizontalBoundsIndex buildingIndex,
            List<FourWayPlacement> fourWayLights,
            List<DirectionalPlacement> directionalLights,
            LightAssets assets,
            SurfaceSampler surfaceSampler,
            long scatterSeed,
            long paletteSeed
    ) {
        if (assets.stonePost() == null && assets.tuffPost() == null) {
            return List.of();
        }

        Map<Long, PostCandidate> uniqueCandidates = new HashMap<>();
        // 建筑灯具先占用全局间距预算，道路灯填补剩余空白。
        addBuildingFrontCandidates(
                uniqueCandidates,
                bounds,
                cellsByKey,
                buildings,
                roads,
                buildingIndex,
                fourWayLights,
                surfaceSampler,
                scatterSeed
        );
        for (SnowtownRoadPlanner.RoadSurfaceBlock road : roads.blocks()) {
            for (Direction direction : CARDINAL_DIRECTIONS) {
                int x = road.x() + direction.getStepX();
                int z = road.z() + direction.getStepZ();
                if (roads.contains(x, z) || !bounds.containsCoreBlock(x, z)) {
                    continue;
                }
                SnowtownPlanningArea.Cell cell = cellsByKey.get(SnowtownPlanningArea.cellKey(
                        Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                        Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE)
                ));
                if (cell == null || cell.surfaceBlockY() != road.y() || buildingIndex.contains(x, z)) {
                    continue;
                }
                if (nearFourWayLight(x, z, fourWayLights)) {
                    continue;
                }
                double rank = WorldgenMath.hashToUnit(x, cell.surfaceBlockY(), z, scatterSeed);
                uniqueCandidates.putIfAbsent(
                        blockColumnKey(x, z),
                        new PostCandidate(x, cell.surfaceBlockY() + 1, z, POST_PRIORITY_ROADSIDE, rank)
                );
            }
        }

        List<PostCandidate> candidates = new ArrayList<>(uniqueCandidates.values());
        candidates.sort(Comparator
                .comparingInt(PostCandidate::priority)
                .thenComparingDouble(PostCandidate::rank)
                .thenComparingInt(PostCandidate::z)
                .thenComparingInt(PostCandidate::x));
        PointSpacingIndex spacing = new PointSpacingIndex(SnowtownSettings.DECORATION_POST_MIN_SPACING);
        directionalLights.forEach(light -> spacing.add(
                light.spacingPoint().getX(),
                light.spacingPoint().getZ()
        ));
        List<AssetPlacementPlan> result = new ArrayList<>();
        for (PostCandidate candidate : candidates) {
            if (!spacing.tryAdd(candidate.x(), candidate.z())) {
                continue;
            }
            StructureAssetDefinition asset = postAssetAt(candidate.x(), candidate.z(), assets, paletteSeed);
            if (asset != null) {
                result.add(directPlacement(asset, new BlockPos(candidate.x(), candidate.y(), candidate.z())));
            }
        }
        return List.copyOf(result);
    }

    private static void addBuildingFrontCandidates(
            Map<Long, PostCandidate> candidates,
            SnowtownPlanningArea.Bounds bounds,
            Map<Long, SnowtownPlanningArea.Cell> cellsByKey,
            List<BuildingInput> buildings,
            RoadIndex roads,
            HorizontalBoundsIndex buildingIndex,
            List<FourWayPlacement> fourWayLights,
            SurfaceSampler surfaceSampler,
            long seed
    ) {
        int offset = SnowtownSettings.DECORATION_POST_BUILDING_CLEARANCE + 1;
        for (BuildingInput building : buildings) {
            BoundingBox buildingBounds = building.bounds();
            int x = Math.max(buildingBounds.minX(), Math.min(building.anchor().getX(), buildingBounds.maxX()));
            int z = Math.max(buildingBounds.minZ(), Math.min(building.anchor().getZ(), buildingBounds.maxZ()));
            switch (building.facing()) {
                case NORTH -> z = buildingBounds.minZ() - offset;
                case EAST -> x = buildingBounds.maxX() + offset;
                case SOUTH -> z = buildingBounds.maxZ() + offset;
                case WEST -> x = buildingBounds.minX() - offset;
                default -> {
                    continue;
                }
            }
            if (!bounds.containsCoreBlock(x, z)
                    || roads.contains(x, z)
                    || buildingIndex.contains(x, z)
                    || nearFourWayLight(x, z, fourWayLights)) {
                continue;
            }
            SnowtownPlanningArea.Cell cell = cellsByKey.get(SnowtownPlanningArea.cellKey(
                    Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE),
                    Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE)
            ));
            int buildingGroundY = building.anchor().getY() - 1;
            if (cell == null || Math.abs(cell.surfaceBlockY() - buildingGroundY) > 1) {
                continue;
            }
            OptionalInt surfaceY = supportedBuildingFrontSurface(
                    surfaceSampler,
                    x,
                    z,
                    buildingGroundY
            );
            if (surfaceY.isEmpty()) {
                continue;
            }
            double rank = WorldgenMath.hashToUnit(x, surfaceY.getAsInt(), z, seed);
            candidates.putIfAbsent(
                    blockColumnKey(x, z),
                    new PostCandidate(x, surfaceY.getAsInt() + 1, z, POST_PRIORITY_BUILDING_FRONT, rank)
            );
        }
    }

    private static OptionalInt supportedBuildingFrontSurface(
            SurfaceSampler surfaceSampler,
            int x,
            int z,
            int buildingGroundY
    ) {
        OptionalInt center = surfaceSampler.surfaceBlockY(x, z);
        if (center.isEmpty() || Math.abs(center.getAsInt() - buildingGroundY) > 1) {
            return OptionalInt.empty();
        }
        for (Direction direction : CARDINAL_DIRECTIONS) {
            OptionalInt neighbor = surfaceSampler.surfaceBlockY(
                    x + direction.getStepX(),
                    z + direction.getStepZ()
            );
            if (neighbor.isEmpty() || Math.abs(neighbor.getAsInt() - center.getAsInt()) > 1) {
                return OptionalInt.empty();
            }
        }
        return center;
    }

    private static boolean nearFourWayLight(int x, int z, List<FourWayPlacement> fourWayLights) {
        int clearance = SnowtownSettings.DECORATION_SMALL_LIGHT_FOUR_WAY_CLEARANCE;
        long clearanceSquared = (long) clearance * clearance;
        for (FourWayPlacement light : fourWayLights) {
            long dx = (long) x - light.center().getX();
            long dz = (long) z - light.center().getZ();
            if (dx * dx + dz * dz < clearanceSquared) {
                return true;
            }
        }
        return false;
    }

    private static StructureAssetDefinition postAssetAt(
            int x,
            int z,
            LightAssets assets,
            long seed
    ) {
        if (assets.stonePost() == null) {
            return assets.tuffPost();
        }
        if (assets.tuffPost() == null) {
            return assets.stonePost();
        }
        int blockSize = SnowtownSettings.DECORATION_POST_PALETTE_BLOCK_SIZE;
        int districtX = Math.floorDiv(x, blockSize);
        int districtZ = Math.floorDiv(z, blockSize);
        return WorldgenMath.hashToUnit(districtX, 0, districtZ, seed) < 0.5
                ? assets.tuffPost()
                : assets.stonePost();
    }

    private static AssetPlacementPlan centeredPlacement(
            StructureAssetDefinition asset,
            int centerX,
            int y,
            int centerZ
    ) {
        return directPlacement(asset, new BlockPos(
                centerX - FOUR_WAY_TEMPLATE_SIZE.getX() / 2,
                y,
                centerZ - FOUR_WAY_TEMPLATE_SIZE.getZ() / 2
        ), FOUR_WAY_TEMPLATE_SIZE);
    }

    private static BoundingBox centeredFootprint(
            StructureAssetDefinition asset,
            int centerX,
            int y,
            int centerZ
    ) {
        int minX = centerX - asset.footprint().width() / 2;
        int minZ = centerZ - asset.footprint().depth() / 2;
        return new BoundingBox(
                minX,
                y,
                minZ,
                minX + asset.footprint().width() - 1,
                y,
                minZ + asset.footprint().depth() - 1
        );
    }

    private static AssetPlacementPlan directPlacement(StructureAssetDefinition asset, BlockPos origin) {
        return directPlacement(asset, origin, new Vec3i(
                asset.footprint().width(),
                asset.footprint().height(),
                asset.footprint().depth()
        ));
    }

    private static AssetPlacementPlan directPlacement(
            StructureAssetDefinition asset,
            BlockPos origin,
            Vec3i size
    ) {
        Rotation rotation = Rotation.NONE;
        Mirror mirror = Mirror.NONE;
        return new AssetPlacementPlan(
                asset,
                origin,
                rotation,
                mirror,
                StructureAssetTransform.computeBounds(size, origin, rotation, mirror)
        );
    }

    private static boolean insideCore(SnowtownPlanningArea.Bounds bounds, BoundingBox box) {
        return bounds.containsCoreBlock(box.minX(), box.minZ())
                && bounds.containsCoreBlock(box.maxX(), box.maxZ());
    }

    private static int horizontalArea(BoundingBox bounds) {
        return (bounds.maxX() - bounds.minX() + 1) * (bounds.maxZ() - bounds.minZ() + 1);
    }

    private static ResourceLocation lightId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowtown/lights/" + path);
    }

    private static long blockColumnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    record BuildingInput(BlockPos anchor, Direction facing, BoundingBox bounds) {}

    @FunctionalInterface
    interface SurfaceSampler {
        OptionalInt surfaceBlockY(int x, int z);
    }

    record LightAssets(
            StructureAssetDefinition fourWay,
            StructureAssetDefinition directional,
            StructureAssetDefinition stonePost,
            StructureAssetDefinition tuffPost
    ) {
        static LightAssets fromCatalog() {
            return new LightAssets(
                    directCatalogAsset(FOUR_WAY_LIGHT_ID, 3, 3),
                    directCatalogAsset(DIRECTIONAL_LIGHT_ID, 1, 2),
                    directCatalogAsset(STONE_POST_LIGHT_ID, 1, 1),
                    directCatalogAsset(TUFF_POST_LIGHT_ID, 1, 1)
            );
        }

        boolean hasAny() {
            return fourWay != null || directional != null || stonePost != null || tuffPost != null;
        }

        private static StructureAssetDefinition directCatalogAsset(
                ResourceLocation id,
                int expectedWidth,
                int expectedDepth
        ) {
            StructureAssetDefinition asset = catalogAsset(id);
            return asset != null
                    && asset.footprint().width() == expectedWidth
                    && asset.footprint().depth() == expectedDepth
                    ? asset
                    : null;
        }

        private static StructureAssetDefinition catalogAsset(ResourceLocation id) {
            StructureAssetDefinition asset = StructureAssetCatalog.get(id).orElse(null);
            return asset != null && "decoration".equals(asset.role()) && asset.isValid() ? asset : null;
        }
    }

    record DecorationPlan(
            List<AssetPlacementPlan> placements,
            Map<Long, List<AssetPlacementPlan>> placementsByChunk,
            int fourWayLights,
            int directionalLights,
            int postLights,
            long planningNanos
    ) {
        static DecorationPlan empty() {
            return empty(0L);
        }

        static DecorationPlan empty(long planningNanos) {
            return new DecorationPlan(List.of(), Map.of(), 0, 0, 0, planningNanos);
        }

        static DecorationPlan of(
                List<AssetPlacementPlan> placements,
                int fourWayLights,
                int directionalLights,
                int postLights,
                long planningNanos
        ) {
            List<AssetPlacementPlan> sorted = placements.stream()
                    .sorted(Comparator
                            .comparingInt((AssetPlacementPlan placement) -> placement.bounds().minZ())
                            .thenComparingInt(placement -> placement.bounds().minX())
                            .thenComparingInt(placement -> placement.bounds().minY())
                            .thenComparing(placement -> placement.asset().id().toString()))
                    .toList();
            return new DecorationPlan(
                    sorted,
                    bucketByChunk(sorted),
                    fourWayLights,
                    directionalLights,
                    postLights,
                    planningNanos
            );
        }

        DecorationPlan {
            placements = List.copyOf(placements);
            placementsByChunk = Map.copyOf(placementsByChunk);
        }

        List<AssetPlacementPlan> placementsForChunk(ChunkAccess chunk) {
            int chunkX = Math.floorDiv(chunk.getPos().getMinBlockX(), 16);
            int chunkZ = Math.floorDiv(chunk.getPos().getMinBlockZ(), 16);
            return placementsByChunk.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
        }

        String summary() {
            return "lights[fourWay=" + fourWayLights
                    + ", directional=" + directionalLights
                    + ", post=" + postLights
                    + "], planningMs=" + String.format(
                            java.util.Locale.ROOT,
                            "%.3f",
                            planningNanos / 1_000_000.0
                    );
        }

        private static Map<Long, List<AssetPlacementPlan>> bucketByChunk(
                List<AssetPlacementPlan> placements
        ) {
            if (placements.isEmpty()) {
                return Map.of();
            }
            Map<Long, List<AssetPlacementPlan>> mutable = new HashMap<>();
            for (AssetPlacementPlan placement : placements) {
                BoundingBox bounds = placement.bounds();
                int minChunkX = Math.floorDiv(bounds.minX(), 16);
                int maxChunkX = Math.floorDiv(bounds.maxX(), 16);
                int minChunkZ = Math.floorDiv(bounds.minZ(), 16);
                int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16);
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                        mutable.computeIfAbsent(chunkKey(chunkX, chunkZ), ignored -> new ArrayList<>())
                                .add(placement);
                    }
                }
            }
            Map<Long, List<AssetPlacementPlan>> immutable = new HashMap<>();
            for (Map.Entry<Long, List<AssetPlacementPlan>> entry : mutable.entrySet()) {
                immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(immutable);
        }
    }

    private static final class RoadIndex {
        private final Map<Long, SnowtownRoadPlanner.RoadSurfaceBlock> roadsByColumn;

        private RoadIndex(Map<Long, SnowtownRoadPlanner.RoadSurfaceBlock> roadsByColumn) {
            this.roadsByColumn = roadsByColumn;
        }

        static RoadIndex of(SnowtownRoadPlanner.RoadPlan plan) {
            Map<Long, SnowtownRoadPlanner.RoadSurfaceBlock> roads = new HashMap<>(plan.roadBlockCount());
            for (List<SnowtownRoadPlanner.RoadSurfaceBlock> chunkRoads : plan.blocksByChunk().values()) {
                for (SnowtownRoadPlanner.RoadSurfaceBlock road : chunkRoads) {
                    roads.put(blockColumnKey(road.x(), road.z()), road);
                }
            }
            return new RoadIndex(roads);
        }

        boolean contains(int x, int z) {
            return roadsByColumn.containsKey(blockColumnKey(x, z));
        }

        SnowtownRoadPlanner.RoadSurfaceBlock get(int x, int z) {
            return roadsByColumn.get(blockColumnKey(x, z));
        }

        Iterable<SnowtownRoadPlanner.RoadSurfaceBlock> blocks() {
            return roadsByColumn.values();
        }
    }

    private static final class HorizontalBoundsIndex {
        private static final int TILE_SIZE = 32;

        private final Map<Long, List<HorizontalBounds>> boundsByTile = new HashMap<>();

        void add(BoundingBox bounds, int expansion) {
            HorizontalBounds horizontal = new HorizontalBounds(
                    bounds.minX() - expansion,
                    bounds.minZ() - expansion,
                    bounds.maxX() + expansion,
                    bounds.maxZ() + expansion
            );
            forEachTile(horizontal, key -> boundsByTile
                    .computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(horizontal));
        }

        boolean contains(int x, int z) {
            List<HorizontalBounds> candidates = boundsByTile.get(tileKey(
                    Math.floorDiv(x, TILE_SIZE),
                    Math.floorDiv(z, TILE_SIZE)
            ));
            if (candidates == null) {
                return false;
            }
            for (HorizontalBounds bounds : candidates) {
                if (bounds.contains(x, z)) {
                    return true;
                }
            }
            return false;
        }

        boolean intersects(BoundingBox bounds) {
            HorizontalBounds horizontal = new HorizontalBounds(
                    bounds.minX(),
                    bounds.minZ(),
                    bounds.maxX(),
                    bounds.maxZ()
            );
            int minTileX = Math.floorDiv(horizontal.minX(), TILE_SIZE);
            int maxTileX = Math.floorDiv(horizontal.maxX(), TILE_SIZE);
            int minTileZ = Math.floorDiv(horizontal.minZ(), TILE_SIZE);
            int maxTileZ = Math.floorDiv(horizontal.maxZ(), TILE_SIZE);
            for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                    List<HorizontalBounds> candidates = boundsByTile.get(tileKey(tileX, tileZ));
                    if (candidates == null) {
                        continue;
                    }
                    for (HorizontalBounds candidate : candidates) {
                        if (candidate.intersects(horizontal)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private void forEachTile(HorizontalBounds bounds, TileConsumer consumer) {
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
    }

    private static final class PointSpacingIndex {
        private final int spacing;
        private final long spacingSquared;
        private final Map<Long, List<BlockPos>> pointsByCell = new HashMap<>();

        private PointSpacingIndex(int spacing) {
            this.spacing = spacing;
            this.spacingSquared = (long) spacing * spacing;
        }

        boolean tryAdd(int x, int z) {
            if (!canAdd(x, z)) {
                return false;
            }
            add(x, z);
            return true;
        }

        boolean canAdd(int x, int z) {
            int cellX = Math.floorDiv(x, spacing);
            int cellZ = Math.floorDiv(z, spacing);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    List<BlockPos> points = pointsByCell.get(tileKey(cellX + dx, cellZ + dz));
                    if (points == null) {
                        continue;
                    }
                    for (BlockPos point : points) {
                        long pointDx = (long) x - point.getX();
                        long pointDz = (long) z - point.getZ();
                        if (pointDx * pointDx + pointDz * pointDz < spacingSquared) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        void add(int x, int z) {
            int cellX = Math.floorDiv(x, spacing);
            int cellZ = Math.floorDiv(z, spacing);
            pointsByCell.computeIfAbsent(tileKey(cellX, cellZ), ignored -> new ArrayList<>())
                    .add(new BlockPos(x, 0, z));
        }
    }

    private static long tileKey(int tileX, int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
    }

    private record HorizontalBounds(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean intersects(HorizontalBounds other) {
            return minX <= other.maxX
                    && maxX >= other.minX
                    && minZ <= other.maxZ
                    && maxZ >= other.minZ;
        }
    }

    private record FourWayCandidate(
            int x,
            int y,
            int z,
            int priority,
            int score,
            double rank,
            boolean requiresSurfaceSupport
    ) {}

    private record FourWayPlacement(BlockPos center, AssetPlacementPlan placement) {}

    private record DirectionalCandidate(BlockPos anchor, double rank, AssetPlacementPlan placement) {}

    private record DirectionalPlacement(BlockPos spacingPoint, AssetPlacementPlan placement) {}

    private record PostCandidate(int x, int y, int z, int priority, double rank) {}

    @FunctionalInterface
    private interface TileConsumer {
        void accept(long key);
    }
}
