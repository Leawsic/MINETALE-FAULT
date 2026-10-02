package cn.jehorstudio.minetale.dimension.worldgen.generator;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSeedBridge;
import cn.jehorstudio.minetale.dimension.worldgen.data.ColumnCache;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettingsResolver;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenContext;
import cn.jehorstudio.minetale.dimension.worldgen.region.RegionGeneration;
import cn.jehorstudio.minetale.dimension.worldgen.region.edge.EdgeRegionGenerationPipeline;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.pipeline.OriginGenerationPipeline;
import cn.jehorstudio.minetale.dimension.worldgen.region.placeholder.PlaceholderRegionGenerationPipeline;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.pipeline.SnowdinGenerationPipeline;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class UndergroundNoiseGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<UndergroundNoiseGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(UndergroundNoiseGenerator::getBiomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(UndergroundNoiseGenerator::generatorSettings)
    ).apply(instance, instance.stable(UndergroundNoiseGenerator::new)));

    private volatile WorldgenSamplingContext samplingContext;
    private final UndergroundSamplingSettings samplingSettings;
    private final RegionGeneration regionGeneration;
    private final ConcurrentMap<Long, ColumnCache> chunkColumnCaches = new ConcurrentHashMap<>();
    private static final int CHUNK_CONTEXT_WARN_THRESHOLD = 1024;
    private static final boolean ENABLE_WORLDGEN_PROFILING = false;
    private static final int PROFILE_LOG_CHUNKS = 128;
    private final AtomicLong profiledChunks = new AtomicLong();
    private final LongAdder stage1Nanos = new LongAdder();
    private final LongAdder stage2Nanos = new LongAdder();
    private final LongAdder stage3Nanos = new LongAdder();
    private final LongAdder stage4Nanos = new LongAdder();
    private final LongAdder stage5Nanos = new LongAdder();
    public UndergroundNoiseGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, settings);
        this.samplingSettings = UndergroundSamplingSettingsResolver.fromNoiseSettings(settings);
        this.samplingContext = new WorldgenSamplingContext(0L, samplingSettings);
        this.regionGeneration = new RegionGeneration(List.of(
                EdgeRegionGenerationPipeline.INSTANCE,
                OriginGenerationPipeline.INSTANCE,
                PlaceholderRegionGenerationPipeline.TRANSITION_TUNNEL,
                PlaceholderRegionGenerationPipeline.RUINS,
                SnowdinGenerationPipeline.INSTANCE,
                PlaceholderRegionGenerationPipeline.WATERFALL,
                PlaceholderRegionGenerationPipeline.HOT_LAND
        ));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSetLookup, RandomState randomState, long seed) {
        bindSeed(seed);
        return super.createState(structureSetLookup, randomState, seed);
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk) {
        bindCurrentSeed();
        return super.createBiomes(randomState, blender, structureManager, chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        bindCurrentSeed();
        return super.fillFromNoise(blender, randomState, structureManager, chunk)
                .thenApply(generatedChunk -> {
                    bindCurrentSeed();
                    WorldgenContext world = worldgenContextFor(generatedChunk);
                    if (ENABLE_WORLDGEN_PROFILING) {
                        runProfiledStage("stage1", stage1Nanos, () -> regionGeneration.runStage1(world));
                        runProfiledStage("stage2", stage2Nanos, () -> regionGeneration.runStage2(world));
                    } else {
                        regionGeneration.runStage1(world);
                        regionGeneration.runStage2(world);
                    }
                    return generatedChunk;
                })
                .whenComplete((generatedChunk, throwable) -> {
                    if (throwable != null) {
                        clearChunkContext(chunk);
                    }
                });
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) {
        bindSeed(seed);
        super.applyCarvers(level, seed, random, biomeManager, structureManager, chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
        bindCurrentSeed();
        super.buildSurface(level, structureManager, random, chunk);
        try {
            WorldgenContext world = worldgenContextFor(chunk);
            if (ENABLE_WORLDGEN_PROFILING) {
                runProfiledStage("stage3", stage3Nanos, () -> regionGeneration.runStage3(world));
            } else {
                regionGeneration.runStage3(world);
            }
        } catch (RuntimeException | Error ex) {
            clearChunkContext(chunk);
            throw ex;
        }
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        bindCurrentSeed();
        try {
            super.applyBiomeDecoration(level, chunk, structureManager);
            WorldgenContext world = worldgenContextFor(chunk, level, this);
            if (ENABLE_WORLDGEN_PROFILING) {
                runProfiledStage("stage4", stage4Nanos, () -> regionGeneration.runStage4(world));
                runProfiledStage("stage5", stage5Nanos, () -> regionGeneration.runStage5(world));
                logWorldgenProfileIfDue();
            } else {
                regionGeneration.runStage4(world);
                regionGeneration.runStage5(world);
            }
        } finally {
            clearChunkContext(chunk);
        }
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        bindCurrentSeed();
        return super.getBaseHeight(x, z, type, level, random);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        bindCurrentSeed();
        return super.getBaseColumn(x, z, height, random);
    }

    private void bindSeed(long seed) {
        WorldgenSamplingContext current = samplingContext;
        if (current.worldgenSeed() != seed) {
            current = new WorldgenSamplingContext(seed, samplingSettings);
            this.samplingContext = current;
        }
        WorldgenSeedBridge.bindLevelSeed(seed);
    }

    private void bindCurrentSeed() {
        WorldgenSeedBridge.bindLevelSeed(samplingContext.worldgenSeed());
    }

    public UndergroundSamplingSettings samplingSettings() {
        return samplingSettings;
    }

    private WorldgenContext worldgenContextFor(ChunkAccess chunk) {
        return worldgenContextFor(chunk, null, null);
    }

    private WorldgenContext worldgenContextFor(ChunkAccess chunk, WorldGenLevel level, ChunkGenerator generator) {
        long chunkKey = chunk.getPos().toLong();
        ColumnCache columnCache = chunkColumnCaches.computeIfAbsent(chunkKey, ignored -> new ColumnCache());
        warnIfChunkContextCacheLooksStale();
        return new WorldgenContext(
                chunk,
                samplingContext,
                columnCache,
                level,
                generator
        );
    }

    private void clearChunkContext(ChunkAccess chunk) {
        chunkColumnCaches.remove(chunk.getPos().toLong());
    }

    private void warnIfChunkContextCacheLooksStale() {
        int size = chunkColumnCaches.size();
        if (size > CHUNK_CONTEXT_WARN_THRESHOLD) {
            MineTale.LOGGER.warn("Underground worldgen retained {} chunk column caches; generation may be ending before applyBiomeDecoration cleanup.", size);
        }
    }

    private void runProfiledStage(String stageName, LongAdder accumulator, Runnable action) {
        if (!ENABLE_WORLDGEN_PROFILING) {
            action.run();
            return;
        }
        long startedAt = System.nanoTime();
        action.run();
        long elapsed = System.nanoTime() - startedAt;
        accumulator.add(elapsed);
        if (elapsed > 20_000_000L) {
            MineTale.LOGGER.info("Underground worldgen {} took {} ms", stageName, nanosToMillis(elapsed));
        }
    }

    private void logWorldgenProfileIfDue() {
        if (!ENABLE_WORLDGEN_PROFILING) {
            return;
        }
        long chunks = profiledChunks.incrementAndGet();
        if (chunks % PROFILE_LOG_CHUNKS != 0) {
            return;
        }
        MineTale.LOGGER.info(
                "Underground worldgen average over {} chunks: stage1={} ms, stage2={} ms, stage3={} ms, stage4={} ms, stage5={} ms",
                PROFILE_LOG_CHUNKS,
                nanosToMillis(stage1Nanos.sumThenReset() / PROFILE_LOG_CHUNKS),
                nanosToMillis(stage2Nanos.sumThenReset() / PROFILE_LOG_CHUNKS),
                nanosToMillis(stage3Nanos.sumThenReset() / PROFILE_LOG_CHUNKS),
                nanosToMillis(stage4Nanos.sumThenReset() / PROFILE_LOG_CHUNKS),
                nanosToMillis(stage5Nanos.sumThenReset() / PROFILE_LOG_CHUNKS)
        );
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

}
