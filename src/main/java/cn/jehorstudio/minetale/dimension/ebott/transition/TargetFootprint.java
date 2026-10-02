package cn.jehorstudio.minetale.dimension.ebott.transition;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 将水平预览范围冻结为数据列与编译邻域；客户端暂存和服务端预热共用该列集合。
public final class TargetFootprint {
    private final int centerBlockX;
    private final int centerBlockZ;
    private final Set<Long> renderColumns;
    private final Set<Long> dataColumns;

    private TargetFootprint(
            int centerBlockX,
            int centerBlockZ,
            Set<Long> renderColumns,
            Set<Long> dataColumns
    ) {
        this.centerBlockX = centerBlockX;
        this.centerBlockZ = centerBlockZ;
        this.renderColumns = Collections.unmodifiableSet(renderColumns);
        this.dataColumns = Collections.unmodifiableSet(dataColumns);
    }

    public static TargetFootprint create(
            int centerBlockX,
            int centerBlockZ,
            int previewRadius
    ) {
        return create(
                centerBlockX,
                centerBlockZ,
                previewRadius,
                centerBlockX,
                centerBlockZ
        );
    }

    // 优先点只决定列的迭代次序
    public static TargetFootprint create(
            int centerBlockX,
            int centerBlockZ,
            int previewRadius,
            int priorityBlockX,
            int priorityBlockZ
    ) {
        if (previewRadius < 0) {
            throw new IllegalArgumentException("previewRadius must be non-negative");
        }
        LinkedHashSet<Long> renderColumns = new LinkedHashSet<>();
        int minChunkX = Math.floorDiv(centerBlockX - previewRadius, 16);
        int maxChunkX = Math.floorDiv(centerBlockX + previewRadius, 16);
        int minChunkZ = Math.floorDiv(centerBlockZ - previewRadius, 16);
        int maxChunkZ = Math.floorDiv(centerBlockZ + previewRadius, 16);
        long radiusSquared = (long) previewRadius * previewRadius;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                int minX = chunkX * 16;
                int minZ = chunkZ * 16;
                int closestX = clamp(centerBlockX, minX, minX + 15);
                int closestZ = clamp(centerBlockZ, minZ, minZ + 15);
                long dx = (long) closestX - centerBlockX;
                long dz = (long) closestZ - centerBlockZ;
                if (dx * dx + dz * dz <= radiusSquared) {
                    renderColumns.add(pack(chunkX, chunkZ));
                }
            }
        }

        LinkedHashSet<Long> dataColumns = new LinkedHashSet<>();
        for (long packed : renderColumns) {
            int chunkX = chunkX(packed);
            int chunkZ = chunkZ(packed);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    dataColumns.add(pack(chunkX + dx, chunkZ + dz));
                }
            }
        }
        int priorityChunkX = Math.floorDiv(priorityBlockX, 16);
        int priorityChunkZ = Math.floorDiv(priorityBlockZ, 16);
        return new TargetFootprint(
                centerBlockX,
                centerBlockZ,
                orderFrom(renderColumns, priorityChunkX, priorityChunkZ),
                orderFrom(dataColumns, priorityChunkX, priorityChunkZ)
        );
    }

    public int dataChunkRadius() {
        int centerChunkX = Math.floorDiv(centerBlockX, 16);
        int centerChunkZ = Math.floorDiv(centerBlockZ, 16);
        return dataChunkRadius(centerChunkX, centerChunkZ);
    }

    // 计算客户端缓存完整容纳数据列所需的最小半径。
    public int dataChunkRadius(int viewCenterChunkX, int viewCenterChunkZ) {
        int radius = 0;
        for (long packed : dataColumns) {
            radius = Math.max(radius, Math.abs(chunkX(packed) - viewCenterChunkX));
            radius = Math.max(radius, Math.abs(chunkZ(packed) - viewCenterChunkZ));
        }
        return radius;
    }

    // 检查给定缓存半径能否覆盖全部数据列
    public boolean fitsWithinChunkRadius(int viewCenterChunkX, int viewCenterChunkZ, int chunkRadius) {
        for (long packed : dataColumns) {
            if (Math.abs(chunkX(packed) - viewCenterChunkX) > chunkRadius
                    || Math.abs(chunkZ(packed) - viewCenterChunkZ) > chunkRadius) {
                return false;
            }
        }
        return true;
    }

    public Set<Long> renderColumns() {
        return renderColumns;
    }

    public Set<Long> dataColumns() {
        return dataColumns;
    }

    public static long pack(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }

    public static int chunkX(long packed) {
        return (int) packed;
    }

    public static int chunkZ(long packed) {
        return (int) (packed >> 32);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static LinkedHashSet<Long> orderFrom(
            Set<Long> columns,
            int priorityChunkX,
            int priorityChunkZ
    ) {
        List<Long> ordered = columns.stream()
                .sorted(Comparator
                        .comparingLong((Long packed) -> ringDistance(
                                packed, priorityChunkX, priorityChunkZ))
                        .thenComparingLong(packed -> distanceSquared(
                                packed, priorityChunkX, priorityChunkZ))
                        .thenComparingInt(TargetFootprint::chunkZ)
                        .thenComparingInt(TargetFootprint::chunkX))
                .toList();
        return new LinkedHashSet<>(ordered);
    }

    private static long ringDistance(long packed, int centerChunkX, int centerChunkZ) {
        long dx = Math.abs((long) chunkX(packed) - centerChunkX);
        long dz = Math.abs((long) chunkZ(packed) - centerChunkZ);
        return Math.max(dx, dz);
    }

    private static long distanceSquared(long packed, int centerChunkX, int centerChunkZ) {
        long dx = (long) chunkX(packed) - centerChunkX;
        long dz = (long) chunkZ(packed) - centerChunkZ;
        return dx * dx + dz * dz;
    }
}
