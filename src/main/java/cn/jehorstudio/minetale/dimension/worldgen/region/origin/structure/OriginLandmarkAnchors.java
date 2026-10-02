package cn.jehorstudio.minetale.dimension.worldgen.region.origin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.data.ColumnCache;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;
import net.minecraft.core.BlockPos;

// Stage4 计算稳定地标锚点
public final class OriginLandmarkAnchors {
    private OriginLandmarkAnchors() {
    }

    public static BlockPos goldenFlowerLanding() {
        return new BlockPos(
                OriginSettings.centerX(),
                OriginSettings.FLOOR_Y + 1,
                OriginSettings.centerZ()
        );
    }

    public static void reserve(OriginContext context) {
        BlockPos anchor = goldenFlowerLanding();
        if (context.x() == anchor.getX() && context.z() == anchor.getZ()) {
            context.columnCache().addFlags(context.index(), ColumnCache.HAS_RESERVED);
        }
    }
}
