package cn.jehorstudio.minetale.dimension.worldgen.registry;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.biome.UndergroundNetworkBiomeSource;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBiomeSources {
    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MineTale.MODID);

    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<UndergroundNetworkBiomeSource>> UNDERGROUND_NETWORK =
            BIOME_SOURCES.register("underground_network", () -> UndergroundNetworkBiomeSource.CODEC);

    public static void register(IEventBus modEventBus) {
        BIOME_SOURCES.register(modEventBus);
    }

    private ModBiomeSources() {}
}
