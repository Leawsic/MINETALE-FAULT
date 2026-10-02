package cn.jehorstudio.minetale.dimension.ebott.mountain;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

public final class EbottMountainTags {
    public static final TagKey<Biome> FALLBACK_VEGETATION_BIOMES = TagKey.create(
            Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "ebott_fallback_vegetation_biomes")
    );

    private EbottMountainTags() {}
}
