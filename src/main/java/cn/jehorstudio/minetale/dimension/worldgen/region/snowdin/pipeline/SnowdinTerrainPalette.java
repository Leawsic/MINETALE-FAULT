package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline;

import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record SnowdinTerrainPalette(
        BlockState snowRock,
        BlockState geoRock,
        BlockState deepslate,
        BlockState tuff,
        BlockState dripstoneBlock,
        BlockState snowBlock
) {
    public static SnowdinTerrainPalette defaults() {
        return Defaults.INSTANCE;
    }

    private static final class Defaults {
        private static final SnowdinTerrainPalette INSTANCE = new SnowdinTerrainPalette(
                CommonBlocksRegistry.SNOW_ROCK.get().defaultBlockState(),
                CommonBlocksRegistry.GEO_ROCK.get().defaultBlockState(),
                Blocks.DEEPSLATE.defaultBlockState(),
                Blocks.TUFF.defaultBlockState(),
                Blocks.DRIPSTONE_BLOCK.defaultBlockState(),
                Blocks.SNOW_BLOCK.defaultBlockState()
        );

        private Defaults() {}
    }
}
