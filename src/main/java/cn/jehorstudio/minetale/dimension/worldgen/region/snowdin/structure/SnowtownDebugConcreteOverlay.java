package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.math.WorldgenMath;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinNaturalTerrainFactsKernel;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinColumnFacts;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.settings.SnowtownSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Map;
import java.util.Optional;

/**
 * 测试连通域算法时留下的Debug模块
 * */

@Deprecated
final class SnowtownDebugConcreteOverlay {
    // 调试着色按连通域稳定选色
    private static final BlockState[] CONCRETE_STATES = {
            Blocks.WHITE_CONCRETE.defaultBlockState(),
            Blocks.ORANGE_CONCRETE.defaultBlockState(),
            Blocks.MAGENTA_CONCRETE.defaultBlockState(),
            Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState(),
            Blocks.YELLOW_CONCRETE.defaultBlockState(),
            Blocks.LIME_CONCRETE.defaultBlockState(),
            Blocks.PINK_CONCRETE.defaultBlockState(),
            Blocks.GRAY_CONCRETE.defaultBlockState(),
            Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
            Blocks.CYAN_CONCRETE.defaultBlockState(),
            Blocks.PURPLE_CONCRETE.defaultBlockState(),
            Blocks.BLUE_CONCRETE.defaultBlockState(),
            Blocks.BROWN_CONCRETE.defaultBlockState(),
            Blocks.GREEN_CONCRETE.defaultBlockState(),
            Blocks.RED_CONCRETE.defaultBlockState(),
            Blocks.BLACK_CONCRETE.defaultBlockState()
    };

    private SnowtownDebugConcreteOverlay() {}

    public static void apply(SnowdinContext context) {
        ChunkAccess chunk = context.world().chunk();
        SnowtownPlanningArea.Id id = SnowtownPlanningArea.Id.fromBlock(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
        SnowtownPlanningArea.Analysis analysis = SnowtownPlanningArea.get(
                context.world().samplingContext(),
                id,
                context.world().minY(),
                context.world().maxY()
        );
        if (analysis.components().isEmpty()) {
            return;
        }

        Map<Long, SnowtownPlanningArea.Component> componentByCell = analysis.componentLookup();
        Map<Long, SnowtownPlanningArea.Cell> factsByCell = analysis.cellLookup();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minBlockX = chunk.getPos().getMinBlockX();
        int minBlockZ = chunk.getPos().getMinBlockZ();

        for (int localZ = 0; localZ < 16; localZ++) {
            int z = minBlockZ + localZ;
            for (int localX = 0; localX < 16; localX++) {
                int x = minBlockX + localX;
                if (!analysis.bounds().containsCoreBlock(x, z)) {
                    continue;
                }

                int cellX = Math.floorDiv(x, SnowtownSettings.PLANNING_AREA_CELL_SIZE);
                int cellZ = Math.floorDiv(z, SnowtownSettings.PLANNING_AREA_CELL_SIZE);
                long cellKey = SnowtownPlanningArea.cellKey(cellX, cellZ);
                SnowtownPlanningArea.Component component = componentByCell.get(cellKey);
                SnowtownPlanningArea.Cell planningCell = factsByCell.get(cellKey);
                if (component == null) {
                    continue;
                }

                Optional<SnowdinColumnFacts> facts = SnowdinNaturalTerrainFactsKernel.sample(
                        context.world().samplingContext(),
                        x,
                        z,
                        context.world().minY(),
                        context.world().maxY()
                );
                if (facts.isEmpty()
                        || facts.get().surfaceBlockY().isEmpty()
                        || facts.get().terraceMask() < SnowtownSettings.CELL_TERRACE_MASK_MIN
                        || facts.get().pillarMaskAtSurface() > SnowtownSettings.CELL_PILLAR_MASK_MAX) {
                    continue;
                }

                int y = facts.get().surfaceBlockY().getAsInt();
                if (planningCell == null || Math.abs(y - planningCell.surfaceBlockY()) > 1) {
                    continue;
                }
                if (y < context.world().minY() || y > context.world().maxY()) {
                    continue;
                }

                cursor.set(x, y, z);
                chunk.setBlockState(cursor, concreteFor(id, component));
            }
        }
    }

    private static BlockState concreteFor(SnowtownPlanningArea.Id id, SnowtownPlanningArea.Component component) {
        int hash = WorldgenMath.hash(id.areaX(), component.componentIndex(), id.areaZ());
        return CONCRETE_STATES[Math.floorMod(hash, CONCRETE_STATES.length)];
    }
}
