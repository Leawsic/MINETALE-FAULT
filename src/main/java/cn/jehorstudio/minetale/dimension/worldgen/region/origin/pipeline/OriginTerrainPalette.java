package cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// 集中拥有 Stage3 的地形角色到 BlockState 映射。
public record OriginTerrainPalette(
        BlockState cavernFloor,
        BlockState landingFloor,
        BlockState cavernCeiling
) {
    private static final OriginTerrainPalette DEFAULTS = new OriginTerrainPalette(
            Blocks.TUFF.defaultBlockState(),
            Blocks.MOSS_BLOCK.defaultBlockState(),
            Blocks.TUFF.defaultBlockState()
    );

    public static OriginTerrainPalette defaults() {
        return DEFAULTS;
    }
}
