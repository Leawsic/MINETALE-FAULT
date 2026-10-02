package cn.jehorstudio.minetale.content.block.common;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class CommonBlockTags {
    public static final TagKey<Block> SNOWTOWN_SPRUCE_PLANTABLE_ON = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowtown_spruce_plantable_on")
    );

    private CommonBlockTags() {}
}
