package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.CakeConfig;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// 从 accepted lots 派生最近建筑，避免维护与规划结果平行的寻址索引。
final class SnowtownLocator {
    private SnowtownLocator() {}

    static Optional<Result> findNearest(
            WorldgenSamplingContext context,
            int minY,
            int maxY,
            BlockPos origin,
            int maxAreaRadius
    ) {
        if (maxAreaRadius < 0) {
            throw new IllegalArgumentException("maxAreaRadius must be non-negative");
        }

        Result nearest = null;
        for (AreaCandidate candidate : candidates(origin, maxAreaRadius)) {
            if (nearest != null && candidate.minimumDistanceSqr() > nearest.distanceSqr()) {
                break;
            }

            SnowtownLotPlanner.Plan plan = SnowtownLotPlanner.plan(
                    context,
                    minY,
                    maxY,
                    candidate.id()
            );
            for (SnowtownLotPlanner.PlannedLot lot : plan.lots()) {
                BlockPos target = locatePosition(lot.bounds(), maxY);
                double distanceSqr = horizontalDistanceSqr(origin, target);
                if (nearest == null
                        || distanceSqr < nearest.distanceSqr()
                        || distanceSqr == nearest.distanceSqr()
                        && comparePosition(target, nearest.position()) < 0) {
                    nearest = new Result(target, lot, distanceSqr);
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    private static List<AreaCandidate> candidates(BlockPos origin, int maxAreaRadius) {
        SnowtownPlanningArea.Id originArea = SnowtownPlanningArea.Id.fromBlock(origin.getX(), origin.getZ());
        int minAreaX = originArea.areaX() - maxAreaRadius;
        int maxAreaX = originArea.areaX() + maxAreaRadius;
        int minAreaZ = Math.max(originArea.areaZ() - maxAreaRadius, firstSnowdinAreaZ());
        int maxAreaZ = Math.min(originArea.areaZ() + maxAreaRadius, lastSnowdinAreaZ());
        if (minAreaZ > maxAreaZ) {
            return List.of();
        }

        List<AreaCandidate> result = new ArrayList<>((maxAreaX - minAreaX + 1) * (maxAreaZ - minAreaZ + 1));
        for (int areaZ = minAreaZ; areaZ <= maxAreaZ; areaZ++) {
            for (int areaX = minAreaX; areaX <= maxAreaX; areaX++) {
                SnowtownPlanningArea.Id id = new SnowtownPlanningArea.Id(areaX, areaZ);
                result.add(new AreaCandidate(id, minimumDistanceSqr(origin, SnowtownPlanningArea.Bounds.of(id))));
            }
        }
        result.sort(Comparator
                .comparingDouble(AreaCandidate::minimumDistanceSqr)
                .thenComparingInt(candidate -> candidate.id().areaZ())
                .thenComparingInt(candidate -> candidate.id().areaX()));
        return result;
    }

    static boolean containsSnowdinAreaZ(int areaZ) {
        return areaZ >= firstSnowdinAreaZ() && areaZ <= lastSnowdinAreaZ();
    }

    private static int firstSnowdinAreaZ() {
        CakeConfig config = CakeConfig.defaults();
        double start = config.transitionTunnelLength()
                + config.ruinsLength()
                + config.transitionTunnelLength();
        int firstBlockZ = (int) Math.ceil(start);
        return Math.floorDiv(firstBlockZ, SnowtownSettings.PLANNING_AREA_CORE_SIZE);
    }

    private static int lastSnowdinAreaZ() {
        CakeConfig config = CakeConfig.defaults();
        double endExclusive = config.transitionTunnelLength()
                + config.ruinsLength()
                + config.transitionTunnelLength()
                + config.snowdinLength();
        int lastBlockZ = (int) Math.ceil(endExclusive) - 1;
        return Math.floorDiv(lastBlockZ, SnowtownSettings.PLANNING_AREA_CORE_SIZE);
    }

    private static double minimumDistanceSqr(BlockPos origin, SnowtownPlanningArea.Bounds bounds) {
        long dx = axisDistance(origin.getX(), bounds.coreMinX(), bounds.coreMaxXExclusive());
        long dz = axisDistance(origin.getZ(), bounds.coreMinZ(), bounds.coreMaxZExclusive());
        return (double) dx * dx + (double) dz * dz;
    }

    private static long axisDistance(int coordinate, int minInclusive, int maxExclusive) {
        if (coordinate < minInclusive) {
            return (long) minInclusive - coordinate;
        }
        if (coordinate >= maxExclusive) {
            return (long) coordinate - (maxExclusive - 1L);
        }
        return 0L;
    }

    private static BlockPos locatePosition(BoundingBox bounds, int maxY) {
        int x = bounds.minX() + (bounds.maxX() - bounds.minX()) / 2;
        int y = Math.min(bounds.maxY() + 1, maxY);
        int z = bounds.minZ() + (bounds.maxZ() - bounds.minZ()) / 2;
        return new BlockPos(x, y, z);
    }

    private static double horizontalDistanceSqr(BlockPos origin, BlockPos target) {
        long dx = (long) target.getX() - origin.getX();
        long dz = (long) target.getZ() - origin.getZ();
        return (double) dx * dx + (double) dz * dz;
    }

    private static int comparePosition(BlockPos left, BlockPos right) {
        int byZ = Integer.compare(left.getZ(), right.getZ());
        if (byZ != 0) {
            return byZ;
        }
        int byX = Integer.compare(left.getX(), right.getX());
        return byX != 0 ? byX : Integer.compare(left.getY(), right.getY());
    }

    record Result(
            BlockPos position,
            SnowtownLotPlanner.PlannedLot lot,
            double distanceSqr
    ) {}

    private record AreaCandidate(SnowtownPlanningArea.Id id, double minimumDistanceSqr) {}
}
