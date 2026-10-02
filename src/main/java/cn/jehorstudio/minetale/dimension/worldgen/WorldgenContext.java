package cn.jehorstudio.minetale.dimension.worldgen;

import cn.jehorstudio.minetale.dimension.worldgen.data.ColumnCache;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.Objects;
import java.util.Optional;

// 冻结一次 Chunk 生成共享的世界输入，并拥有各 Region pipeline 共用的列缓存。
public class WorldgenContext {
    private final ChunkAccess chunk;
    private final WorldgenSamplingContext samplingContext;
    private final int minY;
    private final int maxY;
    private final ColumnCache columnCache;
    private final WorldGenLevel level;
    private final ChunkGenerator generator;

    public WorldgenContext(ChunkAccess chunk, WorldgenSamplingContext samplingContext) {
        this(chunk, samplingContext, new ColumnCache(), null, null);
    }

    public WorldgenContext(ChunkAccess chunk, WorldgenSamplingContext samplingContext, ColumnCache columnCache) {
        this(chunk, samplingContext, columnCache, null, null);
    }

    public WorldgenContext(
            ChunkAccess chunk,
            WorldgenSamplingContext samplingContext,
            ColumnCache columnCache,
            WorldGenLevel level,
            ChunkGenerator generator
    ) {
        this.chunk = chunk;
        this.samplingContext = samplingContext;
        this.minY = chunk.getMinY();
        this.maxY = maxYFromHeight(this.minY, chunk.getHeight());
        this.columnCache = Objects.requireNonNull(columnCache, "columnCache");
        this.level = level;
        this.generator = generator;
    }

    public ChunkAccess chunk() {
        return chunk;
    }

    public WorldgenSamplingContext samplingContext() {
        return samplingContext;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    static int maxYFromHeight(int minY, int height) {
        return minY + height - 1;
    }

    public ColumnCache columnCache() {
        return columnCache;
    }

    public Optional<WorldGenLevel> level() {
        return Optional.ofNullable(level);
    }

    public Optional<ChunkGenerator> generator() {
        return Optional.ofNullable(generator);
    }
}
