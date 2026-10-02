package cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain;

import cn.jehorstudio.minetale.dimension.ebott.EbottDestination;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginColumnFacts;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

// Stage1 将纯地形事实落实为 Chunk 中的基础洞室与出口。
public final class OriginTerrainGenerator {
    private OriginTerrainGenerator() {
    }

    public static void carveColumn(OriginContext context, OriginColumnFacts facts) {
        ChunkAccess chunk = context.world().chunk();
        if (facts.openColumn()) {
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int minY = Math.max(context.world().minY(), facts.floorY() + 1);
            int maxY = facts.shaftColumn()
                    ? context.world().maxY()
                    : Math.min(context.world().maxY(), facts.ceilingY());
            for (int y = minY; y <= maxY; y++) {
                chunk.setBlockState(cursor.set(context.x(), y, context.z()), Blocks.AIR.defaultBlockState());
            }
        }

        if (!EbottDestination.ENABLE_SNOWDIN_TARGET) {
            ShaftGenerator.carveTargetColumn(
                    chunk,
                    context.shaft(),
                    OriginSettings.centerX(),
                    OriginSettings.centerZ(),
                    context.x(),
                    context.z(),
                    Math.max(context.world().minY(), facts.floorY() + 1),
                    context.world().maxY()
            );
        }
    }
}
