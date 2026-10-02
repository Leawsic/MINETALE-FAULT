package cn.jehorstudio.minetale.dimension.worldgen.biome;

import cn.jehorstudio.minetale.dimension.worldgen.registry.ModBiomeSources;
import cn.jehorstudio.minetale.dimension.worldgen.RainbowCakeModel;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSeedBridge;
import cn.jehorstudio.minetale.dimension.worldgen.biome.settings.UndergroundBiomeSettings;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginColumnFacts;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.terrain.OriginTerrainFactsKernel;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.terrain.SnowdinSurfaceResolver;
import cn.jehorstudio.minetale.dimension.worldgen.settings.UndergroundWorldgenDefaults;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.Optional;
import java.util.stream.Stream;

public class UndergroundNetworkBiomeSource extends BiomeSource {
    public static final MapCodec<UndergroundNetworkBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Biome.CODEC.fieldOf("ruins").forGetter(source -> source.ruins),
                    Biome.CODEC.fieldOf("snowdin").forGetter(source -> source.snowdin),
                    Biome.CODEC.fieldOf("waterfall").forGetter(source -> source.waterfall),
                    Biome.CODEC.fieldOf("hot_land").forGetter(source -> source.hotLand),
                    Biome.CODEC.fieldOf("deep_tunnel").forGetter(source -> source.deepTunnel),
                    Biome.CODEC.fieldOf("deep_caves").forGetter(source -> source.deepCaves),
                    Biome.CODEC.optionalFieldOf("origin").forGetter(source -> source.origin),

                    Codec.INT.optionalFieldOf(
                            "cell_size",
                            UndergroundWorldgenDefaults.CELL_SIZE
                    ).forGetter(UndergroundNetworkBiomeSource::cellSize),
                    Codec.DOUBLE.optionalFieldOf(
                            "room_base_radius",
                            UndergroundWorldgenDefaults.BIOME_ROOM_BASE_RADIUS
                    ).forGetter(UndergroundNetworkBiomeSource::biomeRoomBaseRadius),
                    Codec.DOUBLE.optionalFieldOf(
                            "tunnel_base_radius",
                            UndergroundWorldgenDefaults.TUNNEL_BASE_RADIUS
                    ).forGetter(UndergroundNetworkBiomeSource::tunnelBaseRadius)
            ).apply(instance, UndergroundNetworkBiomeSource::new)
    );

    private final Holder<Biome> ruins;
    private final Holder<Biome> snowdin;
    private final Holder<Biome> waterfall;
    private final Holder<Biome> hotLand;
    private final Holder<Biome> deepTunnel;
    private final Holder<Biome> deepCaves;
    private final Optional<Holder<Biome>> origin;

    private final UndergroundSamplingSettings samplingSettings;
    private final ThreadLocal<SamplingState> samplingState = ThreadLocal.withInitial(SamplingState::new);

    public UndergroundNetworkBiomeSource(
            Holder<Biome> ruins,
            Holder<Biome> snowdin,
            Holder<Biome> waterfall,
            Holder<Biome> hotLand,
            Holder<Biome> deepTunnel,
            Holder<Biome> deepCaves,
            Optional<Holder<Biome>> origin,
            int cellSize,
            double biomeRoomBaseRadius,
            double tunnelBaseRadius
    ) {
        this.ruins = ruins;
        this.snowdin = snowdin;
        this.waterfall = waterfall;
        this.hotLand = hotLand;
        this.deepTunnel = deepTunnel;
        this.deepCaves = deepCaves;
        this.origin = origin;
        this.samplingSettings = new UndergroundSamplingSettings(cellSize, biomeRoomBaseRadius, tunnelBaseRadius);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return ModBiomeSources.UNDERGROUND_NETWORK.get();
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(
                Stream.of(
                        ruins,
                        snowdin,
                        waterfall,
                        hotLand,
                        deepTunnel,
                        deepCaves
                ),
                origin.stream()
        );
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        double blockX = quartX * UndergroundBiomeSettings.QUART_TO_BLOCK_SCALE;
        double blockY = quartY * UndergroundBiomeSettings.QUART_TO_BLOCK_SCALE;
        double blockZ = quartZ * UndergroundBiomeSettings.QUART_TO_BLOCK_SCALE;
        long seed = WorldgenSeedBridge.currentSeedOrDefault();
        SamplingState state = samplingState.get().bind(seed, samplingSettings);

        RainbowCakeModel.CakeSample sample = state.model.sample(blockX, blockZ);

        if (sample.region() == RainbowCakeModel.CakeRegion.SNOWDIN) {
            return snowdinBiomeAt(state.context, sample, blockX, blockY, blockZ);
        }

        if (sample.region() == RainbowCakeModel.CakeRegion.ORIGIN && origin.isPresent()) {
            return originBiomeAt(seed, blockX, blockY, blockZ);
        }

        return biomeForRegion(sample.region());
    }

    private Holder<Biome> biomeForRegion(RainbowCakeModel.CakeRegion region) {
        return switch (region) {
            case RUINS -> ruins;
            case SNOWDIN -> snowdin;
            case WATERFALL -> waterfall;
            case HOT_LAND -> hotLand;
            case EDGE_REGION, ORIGIN, TRANSITION_TUNNEL -> deepTunnel;
        };
    }

    // ORIGIN 仅在洞穴垂直范围内生效，范围外统一回退到 deepCaves。
    private Holder<Biome> originBiomeAt(long seed, double blockX, double blockY, double blockZ) {
        OriginColumnFacts facts = OriginTerrainFactsKernel.sample(
                seed,
                (int) Math.floor(blockX),
                (int) Math.floor(blockZ)
        );
        double minY = facts.floorY() - OriginSettings.INSIDE_FLOOR_MARGIN;
        double maxY = facts.ceilingY() + OriginSettings.INSIDE_CEILING_MARGIN;
        if (blockY < minY || blockY > maxY) {
            return deepCaves;
        }

        return origin.orElseThrow();
    }

    private Holder<Biome> snowdinBiomeAt(
            WorldgenSamplingContext context,
            RainbowCakeModel.CakeSample sample,
            double blockX,
            double blockY,
            double blockZ
    ) {
        SnowdinSurfaceResolver.NoiseSurface surface = SnowdinSurfaceResolver.computeNoiseSurface(
                context,
                (int) Math.floor(blockX),
                (int) Math.floor(blockZ),
                sample
        );
        if (surface == null || !SnowdinSurfaceResolver.isInsideBiomeHeight(surface, blockY)) {
            return deepCaves;
        }

        return snowdin;
    }

    private int cellSize() {
        return samplingSettings.cellSize();
    }

    private double biomeRoomBaseRadius() {
        return samplingSettings.regionBaseRadius();
    }

    private double tunnelBaseRadius() {
        return samplingSettings.tunnelBaseRadius();
    }

    public UndergroundSamplingSettings samplingSettings() {
        return samplingSettings;
    }

    // 每个线程按世界种子缓存不可变采样上下文，避免跨线程共享可变采样器。
    private static final class SamplingState {
        private long seed;
        private RainbowCakeModel model;
        private WorldgenSamplingContext context;

        private SamplingState bind(long seed, UndergroundSamplingSettings samplingSettings) {
            if (context == null || this.seed != seed) {
                this.seed = seed;
                this.model = RainbowCakeModel.create(seed);
                this.context = new WorldgenSamplingContext(seed, samplingSettings);
            }
            return this;
        }
    }
}
