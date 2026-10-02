package cn.jehorstudio.minetale.dimension.region.core.collision;

import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Core 碰撞栅格的磁盘持久化：服务端地图数据与客户端本地缓存共用
 * {@link CoreCollisionGrid} 的序列化格式与同目录临时文件的原子写约定。
 * 路径决策（维度 data 目录、何时删除）归调用方；本类只负责读写机制。
 */
public final class CoreCollisionStore {
    private CoreCollisionStore() {}

    /** 客户端本地缓存路径，键 = 资产摘要 + 放置坐标。 */
    public static Path clientCache(String digest, int x, int y, int z) {
        return FMLPaths.GAMEDIR.get()
                .resolve("cache/minetale/core-collision")
                .resolve("col-" + digest + "-" + x + "-" + y + "-" + z + ".bin");
    }

    /** 原子写：临时文件与目标同目录（同卷 move），成功或失败后都清理临时文件。 */
    public static void write(
            Path file, String tempPrefix, CoreCollisionGrid grid, StandardCopyOption... options)
            throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), tempPrefix, ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                grid.write(out);
            }
            Files.move(temporary, file, options);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 还原栅格；文件不存在或格式与原点不符抛 IOException，由调用方回退重建。 */
    public static CoreCollisionGrid read(Path file, int originX, int originY, int originZ)
            throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return CoreCollisionGrid.read(in, originX, originY, originZ);
        }
    }

    /** 客户端单条目策略：删除缓存目录下其它 col-* 条目，只保留本次写入的文件。 */
    public static void pruneClientSiblings(Path keep) throws IOException {
        try (var entries = Files.list(keep.getParent())) {
            for (var entry : (Iterable<Path>) entries::iterator)
                if (!entry.equals(keep) && entry.getFileName().toString().startsWith("col-"))
                    Files.deleteIfExists(entry);
        }
    }
}
