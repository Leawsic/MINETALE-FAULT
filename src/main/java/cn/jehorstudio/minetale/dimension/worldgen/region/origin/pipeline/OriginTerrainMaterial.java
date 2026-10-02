package cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline;

import cn.jehorstudio.minetale.dimension.ebott.EbottDestination;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

// Stage3 根据冻结列事实决定并写入地面、洞顶与受保护几何的材质。
public final class OriginTerrainMaterial {
    private OriginTerrainMaterial() {
    }

    public static void applyColumn(OriginContext context, OriginColumnFacts facts) {
        ChunkAccess chunk = context.world().chunk();
        OriginTerrainPalette palette = OriginTerrainPalette.defaults();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        if (facts.openColumn()) {
            BlockState floor = facts.flowerLandingProtected()
                    ? palette.landingFloor()
                    : palette.cavernFloor();
            chunk.setBlockState(cursor.set(context.x(), facts.floorY(), context.z()), floor);
            if (!facts.shaftColumn() && facts.ceilingY() < context.world().maxY()) {
                chunk.setBlockState(
                        cursor.set(context.x(), facts.ceilingY() + 1, context.z()),
                        palette.cavernCeiling()
                );
            }
        }

        restoreSharedShaft(context, facts);
    }

    public static void restoreSharedShaft(OriginContext context, OriginColumnFacts facts) {
        if (EbottDestination.ENABLE_SNOWDIN_TARGET) {
            return;
        }
        ShaftGenerator.restoreTargetColumn(
                context.world().chunk(),
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
