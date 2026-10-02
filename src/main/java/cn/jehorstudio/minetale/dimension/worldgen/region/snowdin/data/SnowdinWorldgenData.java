package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.data;

import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.SpruceFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;

// 生成 Snowdin worldgen 所需的动态注册表条目。
public final class SnowdinWorldgenData {
    public static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
            .add(Registries.CONFIGURED_FEATURE, context -> context.register(
                    ModWorldgenKeys.SNOWTOWN_SPRUCE,
                    new ConfiguredFeature<>(
                            Feature.TREE,
                            new TreeConfiguration.TreeConfigurationBuilder(
                                    BlockStateProvider.simple(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LOG.get()),
                                    new StraightTrunkPlacer(8, 3, 2),
                                    BlockStateProvider.simple(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LEAVES.get()),
                                    new SpruceFoliagePlacer(
                                            UniformInt.of(2, 3),
                                            UniformInt.of(0, 2),
                                            UniformInt.of(2, 3)
                                    ),
                                    new TwoLayersFeatureSize(2, 0, 2)
                            )
                                    .dirt(BlockStateProvider.simple(Blocks.SNOW_BLOCK))
                                    .ignoreVines()
                                    .build()
                    )
            ));

    private SnowdinWorldgenData() {}
}
