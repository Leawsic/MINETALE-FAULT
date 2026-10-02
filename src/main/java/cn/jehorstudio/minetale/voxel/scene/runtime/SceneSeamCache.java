package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneIndex;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneLayout;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneSeams;

import java.io.*;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

final class SceneSeamCache {
    private static final int MAGIC = 0x4d545343, MAX_ENTRY = 8 * 1024 * 1024;
    private static final long MEMORY_LIMIT = 16 * 1024 * 1024;
    private final Path directory;
    private final LinkedHashMap<SceneLayout.Cell, SceneSeams.Edges> memory =
            new LinkedHashMap<>(256, .75F, true);
    private long bytes, memoryHits, diskHits, misses;
    private volatile boolean diskFailed;

    SceneSeamCache(Path directory) {
        this.directory = directory;
    }

    SceneSeams.Edges get(SceneLayout.Cell cell, SceneIndex index, SceneSeams seams,
            int size, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        synchronized (this) {
            SceneSeams.Edges hit = memory.get(cell);
            if (hit != null) { memoryHits++; return hit; }
        }
        Path file = directory == null ? null
                : directory.resolve(cell.x() + "_" + cell.y() + "_" + cell.z() + ".bin");
        SceneSeams.Edges edges = null;
        if (file != null && !diskFailed && Files.isRegularFile(file)) {
            try { edges = read(file); }
            catch (IOException invalid) {
                // 损坏条目由同一次请求重新计算并原子替换；缓存失败不影响几何成果。
                MineTale.LOGGER.debug("Scene seam cache rejected: {}", file, invalid);
            }
        }
        if (edges == null) {
            synchronized (this) { misses++; }
            edges = index == null ? seams.lowCaps(cell, size, cancelled) : index.caps(cell, cancelled);
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (file != null && !diskFailed && cost(edges) <= MAX_ENTRY) {
                try { write(file, edges); }
                catch (IOException failure) {
                    diskFailed = true;
                    MineTale.LOGGER.warn("Scene seam disk cache unavailable; using memory: {}", directory, failure);
                }
            }
        } else synchronized (this) { diskHits++; }
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        synchronized (this) {
            if (cost(edges) <= MEMORY_LIMIT) {
                SceneSeams.Edges old = memory.put(cell, edges);
                bytes += cost(edges) - (old == null ? 0 : cost(old));
                while (bytes > MEMORY_LIMIT || memory.size() > 16384) {
                    var first = memory.entrySet().iterator();
                    bytes -= cost(first.next().getValue());
                    first.remove();
                }
            }
        }
        return edges;
    }

    private static long cost(SceneSeams.Edges edges) { return 192L + edges.vertices().length * 4L; }

    synchronized String stats() {
        return " seamDisk=" + (directory == null ? "off" : diskFailed ? "failed" : "on")
                + " seamMemoryHits=" + memoryHits + " seamDiskHits=" + diskHits
                + " seamMisses=" + misses + " seamBytes=" + bytes;
    }

    private static SceneSeams.Edges read(Path path) throws IOException {
        long length = Files.size(path);
        if (length < 36 || length > MAX_ENTRY) throw new IOException("接缝缓存长度错误");
        byte[] data = Files.readAllBytes(path);
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 8);
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IOException("接缝缓存版本错误");
            int[] offsets = new int[6], triangles = new int[6];
            int count = 0;
            for (int d = 0; d < 6; d++) {
                offsets[d] = count * 2;
                int n = in.readInt();
                if (n < 0 || (n & 1) != 0 || n > MAX_ENTRY / 112)
                    throw new IOException("接缝缓存面数错误");
                count += n;
                triangles[d] = n;
            }
            if (data.length != 36L + count * 112L) throw new IOException("接缝缓存顶点长度错误");
            float[] vertices = new float[count * 28];
            for (int i = 0; i < vertices.length; i++) {
                vertices[i] = in.readFloat();
                if (!Float.isFinite(vertices[i])) throw new IOException("接缝缓存顶点错误");
            }
            if (in.readLong() != crc.getValue()) throw new IOException("接缝缓存校验失败");
            return new SceneSeams.Edges(vertices, offsets, triangles);
        }
    }

    private static void write(Path path, SceneSeams.Edges edges) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            for (int count : edges.triangles()) out.writeInt(count);
            for (float value : edges.vertices()) out.writeFloat(value);
            CRC32 crc = new CRC32();
            crc.update(bytes.toByteArray());
            out.writeLong(crc.getValue());
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".seam-", ".tmp");
        try {
            Files.write(temporary, bytes.toByteArray());
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
