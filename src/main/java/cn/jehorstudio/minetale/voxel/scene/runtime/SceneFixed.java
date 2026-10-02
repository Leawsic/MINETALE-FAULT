package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneSeams;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneFixedSource;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import com.mojang.blaze3d.buffers.GpuBuffer;

// 固定几何在加载时上传；固定精度纹理完成烘焙后常驻。
// 烘焙统一产出颜色+法线+PBR 全通道（渲染端各自取用），结果按资产摘要落盘缓存
// （SceneFixedBakeCache），命中时直接写入纹素、跳过烘焙，消除进场白色占位期。
final class SceneFixed {
    // System.getLogger 的输出不落 latest.log；缓存诊断必须走模组主 logger。
    private static final org.slf4j.Logger LOGGER = MineTale.LOGGER;

    private final SceneAsset asset;
    private final int count;
    private int version;
    private SceneFixedSource source;
    private final Executor worker;
    private final List<SceneGpu.Fine> meshes = new ArrayList<>();
    private CompletableFuture<Batch> pending;
    private SceneGpu.Upload upload;
    private int cursor;
    private boolean closed;
    private CompletableFuture<Geometry> geometryRequest;
    private Geometry geometry;
    private GpuBuffer fallback;
    private int geometryCursor;
    private int[] counts, offsets;
    private record Geometry(float[] vertices, int[] counts) {}

    private record Batch(SceneVoxels.Page page, int[][][] tiles) {}

    // 烘焙缓存 PNG：[batch][channel][level]；cached 为命中内容，baked 为本次收集。
    private byte[][][][] cached;
    private byte[][][][] bakedPngs;
    private final List<CompletableFuture<?>> encodes = new ArrayList<>();
    private CompletableFuture<?> cacheWrite;
    private int[][][] pendingTiles;
    private SceneGpu.Readback readback;
    private int readbackBatch;
    private volatile boolean cacheAbandoned;
    private boolean bakedAny;

    SceneFixed(SceneAsset asset, Executor worker) throws IOException {
        this.asset = asset;
        this.worker = worker;
        if (!asset.fixedAvailable()) { count = 0; return; }
        try {
            var manifest = JsonParser.parseString(new String(asset.readSource("raw/fixed/manifest.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            version = manifest.get("version").getAsInt();
            if (version != 1 && version != 2) throw new IOException("未知的固定网格版本");
            var batches = manifest.getAsJsonArray("batches");
            count = batches.size();
            if (count < 1 || count > 4096) throw new IOException("固定网格批次数越界");
            for (int i = 0; i < count; i++)
                if (!batches.get(i).getAsJsonObject().get("entry").getAsString().equals("raw/fixed/" + i + ".bin"))
                    throw new IOException("固定网格目录无效");
        } catch (RuntimeException invalid) { throw new IOException("固定网格目录无效", invalid); }
        geometryRequest = CompletableFuture.supplyAsync(() -> {
            try {
                if (version == 2) source = new SceneFixedSource(asset);
                Geometry result = readGeometry();
                if (SceneConfig.fixedBakeDiskCache())
                    cached = SceneFixedBakeCache.read(SceneFixedBakeCache.path(asset), count);
                return result;
            }
            catch (IOException failure) { throw new CompletionException(failure); }
        }, worker);
    }

    long advance(SceneGpu gpu) throws IOException {
        if (readback != null) {
            if (!pollReadback()) return 0;
            if (cursor == count) return 0;
        }
        if (closed || cursor == count) return 0;
        if (counts == null) {
            if (!geometryRequest.isDone()) return 0;
            try { geometry = geometryRequest.join(); }
            catch (CompletionException failure) { throw new IOException("固定几何读取失败", failure.getCause()); }
            counts = geometry.counts();
            offsets = new int[count];
            for (int i = 1; i < count; i++) offsets[i] = offsets[i-1] + counts[i-1];
            fallback = gpu.rawBuffer(geometry.vertices().length/8);
            geometryRequest = null;
        }
        if (geometry != null) {
            int remaining = geometry.vertices().length/8 - geometryCursor;
            int chunk = Math.min(remaining, 512*1024/gpu.stride/3*3);
            gpu.uploadRawRange(fallback, geometry.vertices(), geometryCursor, chunk);
            geometryCursor += chunk;
            if (geometryCursor == geometry.vertices().length/8) geometry = null;
            return (long) chunk * gpu.stride;
        }
        if (!gpu.prepareMaterials()) return 0;
        if (pending == null && upload == null) {
            int index = cursor;
            pending = CompletableFuture.supplyAsync(() -> {
                try {
                    SceneVoxels.Page page = read(index);
                    int[][][] tiles = null;
                    if (cached != null && cached[index] != null)
                        try {
                            tiles = SceneFixedBakeCache.decode(cached[index]);
                        } catch (IOException | RuntimeException invalid) {
                            LOGGER.warn("固定网格烘焙缓存已拒绝", invalid);
                            cached[index] = null;
                        }
                    return new Batch(page, tiles);
                }
                catch (IOException failure) { throw new CompletionException(failure); }
            }, worker);
        }
        if (upload == null) {
            if (!pending.isDone()) return 0;
            Batch batch;
            try { batch = pending.join(); }
            catch (CompletionException failure) { throw new IOException("固定网格加载失败", failure.getCause()); }
            pending = null;
            upload = gpu.beginFine(batch.page(), new SceneSeams.Edges(new float[0], new int[6], new int[6]), null, count);
            pendingTiles = batch.tiles();
        }
        if (pendingTiles != null) {
            byte[][][] served = cached != null ? cached[cursor] : null;
            try {
                // 缓存只免去烘焙；顶点仍须完整写入 buffer，否则 finish 产出的是空网格。
                if (!upload.verticesReady() && !upload.uploadVertices()) return upload.uploaded;
                if (upload.writeCachedTiles(pendingTiles)) {
                    pendingTiles = null;
                    if (cached != null) cached[cursor] = null;
                    recordServed(cursor, served);
                    long bytes = upload.uploaded;
                    finishMesh(gpu);
                    return bytes;
                }
                return upload.uploaded;
            } catch (IOException | RuntimeException invalid) {
                // 本批缓存无效（尺寸校验失败等）：丢弃并走烘焙路径整批覆盖。
                LOGGER.warn("固定网格烘焙缓存已拒绝", invalid);
                pendingTiles = null;
                if (cached != null) cached[cursor] = null;
                cacheAbandoned = true;
            }
        }
        boolean ready = upload.advance();
        long bytes = upload.uploaded;
        if (ready) {
            beginReadback(upload, cursor);
            finishMesh(gpu);
        }
        return bytes;
    }

    /** 轮询在途烘焙回读；完成时交给 worker 编码 PNG。返回 true 表示本轮已消费。 */
    private boolean pollReadback() {
        int[][][] pixels;
        try {
            pixels = readback.poll();
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("固定网格烘焙回读失败", failure);
            readback.close();
            readback = null;
            cacheAbandoned = true;
            return true;
        }
        if (pixels == null) return false;
        readback = null;
        encodeBaked(readbackBatch, pixels);
        maybeBeginCacheWrite();
        return true;
    }

    private void finishMesh(SceneGpu gpu) throws IOException {
        var mesh = upload.finish(); mesh.markFixed(); meshes.add(mesh); upload = null; cursor++;
        if (cursor == count) {
            gpu.retireRaw(fallback); fallback = null;
            maybeBeginCacheWrite();
        }
    }

    /** 全部批次就绪且回读排空后才允许落盘；最后一批的回读在 finishMesh 之后完成。 */
    private void maybeBeginCacheWrite() {
        if (cursor == count && readback == null) beginCacheWrite();
    }

    private void recordServed(int batch, byte[][][] served) {
        if (cacheAbandoned) return;
        if (bakedPngs == null) bakedPngs = new byte[count][4][2][];
        bakedPngs[batch] = served;
    }

    private void beginReadback(SceneGpu.Upload finished, int batch) {
        if (cacheAbandoned) return;
        try {
            readback = finished.beginReadback();
            readbackBatch = batch;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("固定网格烘焙回读发起失败", failure);
            cacheAbandoned = true;
        }
    }

    private void encodeBaked(int batch, int[][][] pixels) {
        if (cacheAbandoned) return;
        if (bakedPngs == null) bakedPngs = new byte[count][4][2][];
        bakedAny = true;
        encodes.add(CompletableFuture.runAsync(() -> {
            try {
                for (int c = 0; c < 4; c++)
                    for (int level = 0; level < 2; level++)
                        if (pixels[c] != null && pixels[c][level] != null)
                            bakedPngs[batch][c][level] = SceneFixedBakeCache.encode(pixels[c][level]);
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("固定网格烘焙缓存编码失败", failure);
                cacheAbandoned = true;
            }
        }, worker));
    }

    private void beginCacheWrite() {
        if (!SceneConfig.fixedBakeDiskCache()) return;
        if (cacheAbandoned || !bakedAny || bakedPngs == null) return;
        var encodings = encodes.toArray(CompletableFuture[]::new);
        // 完整性检查必须在编码 future 全部完成后进行：槽位由这些 future 填充。
        cacheWrite = CompletableFuture.allOf(encodings).thenRunAsync(() -> {
            if (cacheAbandoned) return;
            for (var batch : bakedPngs) for (var channel : batch) for (var level : channel)
                if (level == null) {
                    LOGGER.warn("固定网格烘焙缓存不完整，跳过写入");
                    return;
                }
            try {
                Path file = SceneFixedBakeCache.path(asset);
                SceneFixedBakeCache.write(file, bakedPngs);
                LOGGER.info(
                        "固定网格烘焙缓存已写入：{} ({} KiB)", file, Files.size(file) / 1024);
            } catch (IOException failure) {
                LOGGER.warn("固定网格烘焙缓存写入失败", failure);
            }
        }, worker);
    }

    int append(List<SceneGpu.Fine> output, List<SceneGpu.Draw> raw) {
        output.addAll(meshes);
        int triangles = 0;
        for (var mesh : meshes) triangles += mesh.triangles;
        if (fallback != null && geometry == null) for (int i = cursor; i < count; i++) {
            raw.add(new SceneGpu.Draw(fallback, offsets[i], counts[i]/3));
            triangles += counts[i]/3;
        }
        return triangles;
    }

    String status() { return count == 0 ? "" : " fixed=" + cursor + "/" + count; }

    /** 主体固定网格是否全部就绪（无固定网格视为就绪）。 */
    boolean complete() { return cursor == count; }

    private Geometry readGeometry() throws IOException {
        ByteBuffer in = ByteBuffer.wrap(asset.readSource("raw/fixed/geometry.bin")).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 8 + count*4 || in.getInt() != 0x4d544647 || in.getInt() != count)
            throw new IOException("固定几何目录无效");
        int[] sizes = new int[count]; long total = 0;
        for (int i = 0; i < count; i++) {
            sizes[i] = in.getInt();
            if (sizes[i] <= 0 || sizes[i] % 6 != 0) throw new IOException("固定几何顶点数无效");
            total += sizes[i];
        }
        if (total > 1_000_000 || in.remaining() != total*32) throw new IOException("固定几何长度无效");
        float[] vertices = new float[(int) total*8];
        for (int i = 0; i < vertices.length; i++) vertices[i] = finite(in.getFloat());
        return new Geometry(vertices, sizes);
    }

    private SceneVoxels.Page read(int index) throws IOException {
        byte[] encoded = asset.readSource("raw/fixed/" + index + ".bin");
        SceneFixedSource.Batch data = version == 2 ? source.generate(encoded) : readLegacy(encoded);
        int size = data.size(), samplesCount = data.samples().length / 7;
        float resolution = data.resolution();
        float[] vertices = data.vertices(), original = data.samples();
        if (vertices.length / 56 != counts[index] / 6) throw new IOException("固定网格几何数量不匹配");
        for (int i = 0; i < vertices.length; i += 14) {
            float nx = vertices[i+5], ny = vertices[i+6], nz = vertices[i+7];
            float length = nx*nx + ny*ny + nz*nz;
            if (Math.abs(length-1) > .01 || vertices[i+3] < 0 || vertices[i+3] > 1 || vertices[i+4] < 0 || vertices[i+4] > 1)
                throw new IOException("固定网格法线或 UV 无效");
            // 与程序化法线烘焙使用同一切线帧；UV 的参数方向由独立图集映射定义。
            float tx = Math.abs(ny) < .9F ? nz : 0, ty = Math.abs(ny) < .9F ? 0 : -nz, tz = Math.abs(ny) < .9F ? -nx : ny;
            float scale = (float) (1 / Math.sqrt(tx*tx + ty*ty + tz*tz));
            vertices[i+8]=tx*scale; vertices[i+9]=ty*scale; vertices[i+10]=tz*scale; vertices[i+11]=1;
        }
        int[] masks = new int[samplesCount], ranges = new int[33];
        int maxMaterial = 0;
        for (int i = 0; i < samplesCount; i++) {
            float nx = original[i*7+3], ny = original[i*7+4], nz = original[i*7+5];
            if (Math.abs(nx*nx+ny*ny+nz*nz-1) > .01) throw new IOException("固定网格采样法线无效");
            float material = original[i*7+6];
            if (material < 0 || material > 7 || material != (int) material) throw new IOException("固定网格来源材质越界");
            maxMaterial = Math.max(maxMaterial, (int) material);
            // 统一烘焙全通道材质：mask 不随渲染端光影开关变化，缓存只有一种变体。
            masks[i] = asset.channels().material((int) material).variableMask(true);
            ranges[masks[i]+1]++;
        }
        for (int i = 1; i < ranges.length; i++) ranges[i] += ranges[i-1];
        int[] next = ranges.clone(), remap = new int[samplesCount];
        float[] samples = new float[samplesCount * 12];
        for (int i = 0; i < samplesCount; i++) {
            int at = next[masks[i]]++; remap[i] = at; at *= 12;
            System.arraycopy(original, i*7, samples, at, 6);
            samples[at+6] = masks[i]; samples[at+7] = original[i*7+6]; samples[at+8] = 1/resolution;
            samples[at+9] = original[i*7+6]; samples[at+11] = Float.intBitsToFloat(-1);
        }
        int[] texels = data.texels();
        for (int i = 0; i < texels.length; i++) {
            int id = texels[i];
            if (id < -1 || id >= samplesCount) throw new IOException("固定网格纹素索引越界");
            texels[i] = id < 0 ? -1 : remap[id];
        }
        float[] constants = new float[(maxMaterial+1)*12];
        for (int id = 0; id <= maxMaterial; id++) {
            var m = asset.channels().material(id); int at = id*12;
            constants[at]=m.normal().x(); constants[at+1]=m.normal().y(); constants[at+2]=m.normal().z();
            constants[at+3]=m.variableMask(true); constants[at+4]=m.roughness().x(); constants[at+5]=m.metalness().x();
            constants[at+6]=m.emission().x(); constants[at+7]=m.emission().y(); constants[at+8]=m.emission().z();
        }
        return new SceneVoxels.Page(vertices, samples, texels, size, 0, vertices.length/56, false,
                new SceneVoxels.Precision(2, resolution, resolution, resolution),
                new int[]{size,size,size,size}, new int[]{0,0,0,0}, vertices.length/28,
                new int[6], new int[6], true, constants, ranges,
                new int[]{size,size,size,size},
                new SceneVoxels.MaterialLayout(resolution, size, size, new int[0]), false);
    }

    private static SceneFixedSource.Batch readLegacy(byte[] encoded) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        if (in.remaining() < 20 || in.getInt() != 0x4d544658) throw new IOException("固定网格头无效");
        int size = in.getInt(), vf = in.getInt(), count = in.getInt();
        float resolution = in.getFloat();
        if (size < 16 || size > 4096 || Integer.bitCount(size) != 1 || vf <= 0 || vf % 56 != 0
                || count < 1 || count > (long) size * size || resolution != 2
                || in.remaining() != vf * 4L + count * 28L + (long) size * size * 4)
            throw new IOException("固定网格长度或精度无效");
        float[] vertices = new float[vf], samples = new float[count * 7];
        for (int i = 0; i < vf; i++) vertices[i] = finite(in.getFloat());
        for (int i = 0; i < samples.length; i++) samples[i] = finite(in.getFloat());
        int[] texels = new int[size * size];
        for (int i = 0; i < texels.length; i++) texels[i] = in.getInt();
        return new SceneFixedSource.Batch(size, resolution, vertices, samples, texels);
    }

    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("固定网格含非有限坐标");
        return value;
    }

    void close(SceneGpu gpu) {
        closed = true;
        if (upload != null) { upload.close(); upload = null; }
        if (readback != null) { readback.close(); readback = null; cacheAbandoned = true; }
        for (SceneGpu.Fine mesh : meshes) mesh.close();
        meshes.clear();
        if (fallback != null) { gpu.retireRaw(fallback); fallback = null; }
        // 读取持有资产锁；关闭资产前由 Runtime 等待这一次读取完成。
    }

    CompletableFuture<?> pending() {
        var outstanding = new ArrayList<CompletableFuture<?>>(encodes.size() + 2);
        outstanding.addAll(encodes);
        if (pending != null) outstanding.add(pending);
        if (geometryRequest != null) outstanding.add(geometryRequest);
        if (cacheWrite != null) outstanding.add(cacheWrite);
        return CompletableFuture.allOf(outstanding.toArray(CompletableFuture[]::new));
    }
}
