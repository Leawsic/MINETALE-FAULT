package cn.jehorstudio.minetale.dimension.worldgen.registry;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.generator.UndergroundNoiseGenerator;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModChunkGenerators {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MineTale.MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<UndergroundNoiseGenerator>> UNDERGROUND_NOISE =
            CHUNK_GENERATORS.register("underground_noise", () -> UndergroundNoiseGenerator.CODEC);

    public static void register(IEventBus modEventBus) {
        CHUNK_GENERATORS.register(modEventBus);
    }

    private ModChunkGenerators() {}
}
