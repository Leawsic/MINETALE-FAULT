package cn.jehorstudio.minetale.dimension.worldgen.network.settings;

import cn.jehorstudio.minetale.dimension.worldgen.generator.UndergroundNoiseGenerator;
import net.minecraft.core.Holder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

public final class UndergroundSamplingSettingsResolver {
    private UndergroundSamplingSettingsResolver() {}

    public static UndergroundSamplingSettings fromGenerator(ChunkGenerator generator) {
        if (generator == null) {
            return UndergroundSamplingSettings.defaults();
        }

        if (generator instanceof UndergroundNoiseGenerator ug) {
            return ug.samplingSettings();
        }

        return UndergroundSamplingSettings.defaults();
    }

    public static UndergroundSamplingSettings fromNoiseSettings(Holder<NoiseGeneratorSettings> settings) {
        if (settings == null) {
            return UndergroundSamplingSettings.defaults();
        }

        return fromNoiseSettings(settings.value());
    }

    public static UndergroundSamplingSettings fromNoiseSettings(NoiseGeneratorSettings settings) {
        if (settings == null) {
            return UndergroundSamplingSettings.defaults();
        }

        return UndergroundSamplingSettings.defaults();
    }
}
