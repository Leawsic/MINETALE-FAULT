package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.asset.AssetPlacer;
import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// Stage4 只选择可能影响当前 Chunk 的 PlanningArea，并裁切写入其冻结计划。
public final class SnowtownGenerator {
    private SnowtownGenerator() {}

    public static void apply(SnowdinContext context) {
        if (context.world().level().isEmpty()) {
            return;
        }

        WorldGenLevel level = context.world().level().get();
        ChunkAccess chunk = context.world().chunk();
        SnowtownPlanningArea.Id currentId = SnowtownPlanningArea.Id.fromBlock(
                chunk.getPos().getMinBlockX(),
                chunk.getPos().getMinBlockZ()
        );
        List<AreaPlan> areaPlans = new ArrayList<>();
        for (int areaZ = currentId.areaZ() - 1; areaZ <= currentId.areaZ() + 1; areaZ++) {
            for (int areaX = currentId.areaX() - 1; areaX <= currentId.areaX() + 1; areaX++) {
                SnowtownPlanningArea.Id id = new SnowtownPlanningArea.Id(areaX, areaZ);
                if (planningAreaMayReachChunk(id, chunk)) {
                    areaPlans.add(new AreaPlan(id, SnowtownLotPlanner.plan(context, id)));
                }
            }
        }
        for (AreaPlan areaPlan : areaPlans) {
            placeAreaLots(context, level, areaPlan.plan());
        }
        for (AreaPlan areaPlan : areaPlans) {
            writeAreaRoads(context, level, areaPlan.id(), areaPlan.plan());
        }
        for (AreaPlan areaPlan : areaPlans) {
            placeAreaDecorations(context, level, areaPlan.plan());
        }
    }

    private static void placeAreaLots(
            SnowdinContext context,
            WorldGenLevel level,
            SnowtownLotPlanner.Plan plan
    ) {
        ChunkAccess chunk = context.world().chunk();
        for (SnowtownLotPlanner.PlannedLot lot : plan.lotsForChunk(chunk)) {
            if (!AssetPlacer.intersectsChunk(lot.bounds(), chunk)) {
                continue;
            }
            markOccupiedColumns(context, lot.bounds());
            placeLot(level, chunk, lot);
        }
    }

    private static void writeAreaRoads(
            SnowdinContext context,
            WorldGenLevel level,
            SnowtownPlanningArea.Id id,
            SnowtownLotPlanner.Plan plan
    ) {
        ChunkAccess chunk = context.world().chunk();
        SnowtownPlanningArea.Bounds bounds = SnowtownPlanningArea.Bounds.of(id);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (SnowtownRoadPlanner.RoadSurfaceBlock roadBlock : plan.roadPlan().roadBlocksForChunk(chunk)) {
            if (!bounds.containsCoreBlock(roadBlock.x(), roadBlock.z())) {
                continue;
            }
            cursor.set(roadBlock.x(), roadBlock.y(), roadBlock.z());
            if (!AssetPlacer.blockInChunk(cursor, chunk)) {
                continue;
            }
            int localX = roadBlock.x() - chunk.getPos().getMinBlockX();
            int localZ = roadBlock.z() - chunk.getPos().getMinBlockZ();
            int columnIndex = (localZ << 4) | localX;
            if (context.isStage4StructureOccupied(columnIndex)) {
                continue;
            }
            level.setBlock(cursor, SnowtownSettings.ROAD_BLOCK, 2);
            context.putStage4StructureOccupied(columnIndex);
        }
    }

    private static void placeLot(
            WorldGenLevel level,
            ChunkAccess chunk,
            SnowtownLotPlanner.PlannedLot lot
    ) {
        AssetPlacer.placeInChunk(
                level,
                lot.placement(),
                chunk,
                WorldgenMath.hash(
                        lot.sample().x(),
                        lot.sample().componentIndex(),
                        lot.sample().z(),
                        level.getSeed()
                )
        );
    }

    private static void placeAreaDecorations(
            SnowdinContext context,
            WorldGenLevel level,
            SnowtownLotPlanner.Plan plan
    ) {
        ChunkAccess chunk = context.world().chunk();
        for (AssetPlacer.AssetPlacementPlan decoration : plan.decorationPlan().placementsForChunk(chunk)) {
            if (!AssetPlacer.intersectsChunk(decoration.bounds(), chunk)) {
                continue;
            }
            markOccupiedColumns(context, decoration.bounds());
            AssetPlacer.placeInChunkIgnoringAir(
                    level,
                    decoration,
                    chunk,
                    WorldgenMath.hash(
                            decoration.origin().getX(),
                            decoration.asset().id().hashCode(),
                            decoration.origin().getZ(),
                            level.getSeed()
                    )
            );
        }
    }

    private static void markOccupiedColumns(SnowdinContext context, BoundingBox bounds) {
        int chunkMinX = context.world().chunk().getPos().getMinBlockX();
        int chunkMinZ = context.world().chunk().getPos().getMinBlockZ();
        int minX = Math.max(bounds.minX(), chunkMinX);
        int maxX = Math.min(bounds.maxX(), chunkMinX + 15);
        int minZ = Math.max(bounds.minZ(), chunkMinZ);
        int maxZ = Math.min(bounds.maxZ(), chunkMinZ + 15);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int localX = x - chunkMinX;
                int localZ = z - chunkMinZ;
                context.putStage4StructureOccupied((localZ << 4) | localX);
            }
        }
    }

    private static boolean planningAreaMayReachChunk(SnowtownPlanningArea.Id id, ChunkAccess chunk) {
        SnowtownPlanningArea.Bounds bounds = SnowtownPlanningArea.Bounds.of(id);
        int reach = SnowtownSettings.MAX_LOT_REACH_BLOCKS;
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        int chunkMaxXExclusive = chunkMinX + 16;
        int chunkMaxZExclusive = chunkMinZ + 16;
        return chunkMinX < bounds.coreMaxXExclusive() + reach
                && chunkMaxXExclusive > bounds.coreMinX() - reach
                && chunkMinZ < bounds.coreMaxZExclusive() + reach
                && chunkMaxZExclusive > bounds.coreMinZ() - reach;
    }

    private record AreaPlan(
            SnowtownPlanningArea.Id id,
            SnowtownLotPlanner.Plan plan
    ) {}
}
