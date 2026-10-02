package cn.jehorstudio.minetale.dimension.worldgen.registry;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

public final class ModWorldgenKeys {
    public static final ResourceKey<Level> UNDERGROUND_LEVEL =
            ResourceKey.create(Registries.DIMENSION, id("underground_world"));

    public static final ResourceKey<ConfiguredFeature<?, ?>> SNOWTOWN_SPRUCE =
            configuredFeature("snowtown_spruce");

    private static ResourceKey<ConfiguredFeature<?, ?>> configuredFeature(String name) {
        return ResourceKey.create(Registries.CONFIGURED_FEATURE, id(name));
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, name);
    }

    private ModWorldgenKeys() {}
}
