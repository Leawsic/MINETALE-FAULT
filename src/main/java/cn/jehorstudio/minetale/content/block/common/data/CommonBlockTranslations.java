package cn.jehorstudio.minetale.content.block.common.data;

import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class CommonBlockTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.addBlock(CommonBlocksRegistry.RUIN_BRICK, "Ruin Brick");
        provider.addBlock(CommonBlocksRegistry.GEO_ROCK, "Geo Rock");
        provider.addBlock(CommonBlocksRegistry.SNOW_ROCK, "Snow Rock");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LOG, "Snowtown Spruce Log");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_WOOD, "Snowtown Spruce Wood");
        provider.addBlock(CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_LOG, "Stripped Snowtown Spruce Log");
        provider.addBlock(CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_WOOD, "Stripped Snowtown Spruce Wood");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LEAVES, "Snowtown Spruce Leaves");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_SAPLING, "Snowtown Spruce Sapling");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.addBlock(CommonBlocksRegistry.RUIN_BRICK, "遗迹砖块");
        provider.addBlock(CommonBlocksRegistry.GEO_ROCK, "地壳岩");
        provider.addBlock(CommonBlocksRegistry.SNOW_ROCK, "雪岩");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LOG, "雪町特产云杉原木");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_WOOD, "雪町特产云杉木");
        provider.addBlock(CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_LOG, "去皮雪町特产云杉原木");
        provider.addBlock(CommonBlocksRegistry.STRIPPED_SNOWTOWN_SPRUCE_WOOD, "去皮雪町特产云杉木");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_LEAVES, "雪町特产云杉树叶");
        provider.addBlock(CommonBlocksRegistry.SNOWTOWN_SPRUCE_SAPLING, "雪町特产云杉树苗");
    }

    private CommonBlockTranslations() {}
}
