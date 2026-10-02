package cn.jehorstudio.minetale.dimension.ebott.mountain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

// CARVERS 后、Feature 前冻结的原版地表与海床。
public final class SurfaceSnapshot {
    private static final int SIDE = 16;
    private static final int COLUMNS = SIDE * SIDE;
    private static final Codec<int[]> HEIGHTS_CODEC = Codec.INT_STREAM.comapFlatMap(
            stream -> {
                int[] heights = stream.toArray();
                return heights.length == COLUMNS
                        ? DataResult.success(heights)
                        : DataResult.error(() -> "expected " + COLUMNS + " height values, got " + heights.length);
            },
            Arrays::stream
    );

    public static final MapCodec<SurfaceSnapshot> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            HEIGHTS_CODEC.fieldOf("surface").forGetter(snapshot -> snapshot.surfaceY),
            HEIGHTS_CODEC.fieldOf("ocean_floor").forGetter(snapshot -> snapshot.oceanFloorY)
    ).apply(instance, SurfaceSnapshot::new));

    private final int[] surfaceY;
    private final int[] oceanFloorY;

    private SurfaceSnapshot(int[] surfaceY, int[] oceanFloorY) {
        this.surfaceY = requireColumns(surfaceY);
        this.oceanFloorY = requireColumns(oceanFloorY);
    }

    static SurfaceSnapshot of(int[] surfaceY, int[] oceanFloorY) {
        return new SurfaceSnapshot(surfaceY, oceanFloorY);
    }

    public static SurfaceSnapshot capture(ChunkAccess chunk) {
        int[] surfaceY = new int[COLUMNS];
        int[] oceanFloorY = new int[COLUMNS];
        for (int localZ = 0; localZ < SIDE; localZ++) {
            for (int localX = 0; localX < SIDE; localX++) {
                int index = index(localX, localZ);
                surfaceY[index] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
                oceanFloorY[index] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, localX, localZ);
            }
        }
        return new SurfaceSnapshot(surfaceY, oceanFloorY);
    }

    int surfaceY(int localX, int localZ) {
        return surfaceY[index(localX, localZ)];
    }

    int oceanFloorY(int localX, int localZ) {
        return oceanFloorY[index(localX, localZ)];
    }

    private static int[] requireColumns(int[] heights) {
        if (heights.length != COLUMNS) {
            throw new IllegalArgumentException("expected " + COLUMNS + " height values, got " + heights.length);
        }
        return heights;
    }

    private static int index(int localX, int localZ) {
        return localZ * SIDE + localX;
    }
}
