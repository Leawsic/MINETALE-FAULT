package cn.jehorstudio.minetale.dimension.worldgen.region.origin.feature;

import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginTerrainMaterial;

// Stage5 只恢复必须晚于其他生成内容写入的保护几何。
public final class OriginFeature {
    private OriginFeature() {
    }

    public static void finish(OriginContext context) {
        OriginTerrainMaterial.restoreSharedShaft(context, context.facts());
    }
}
