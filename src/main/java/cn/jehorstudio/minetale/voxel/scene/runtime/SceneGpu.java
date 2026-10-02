package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneImages;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneSeams;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.mojang.blaze3d.platform.NativeImage;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

// GPU 所有权归场景生命周期管理。常驻 Low 使用单个缓冲，连续可见页合并提交。
final class SceneGpu implements AutoCloseable {
    private record Retirement(long fence, Runnable release) {}
    private static final java.util.ArrayDeque<Retirement> RETIRED = new java.util.ArrayDeque<>();
    private static final java.util.Set<SceneGpu> LIVE = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    static long detailBytes() { return LIVE.stream().mapToLong(SceneGpu::highBytes).sum(); }
    static long allTemporaryBytes() { return SceneMaterials.allTemporaryBytes(); }
    static long allBaseBytes() { return LIVE.stream().mapToLong(SceneGpu::baseBytes).sum() + SceneBaseCache.backgroundGpuBytes(); }

    static void retire(Runnable release) {
        // fence 排在最后一次写入和绘制之后；取消 CPU 请求不能使图集槽提前复用。
        long fence = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        RETIRED.addLast(new Retirement(fence, release));
    }

    static void collectRetired() {
        long deadline = System.nanoTime() + 1_000_000;
        int remaining = 8;
        while (!RETIRED.isEmpty() && remaining-- > 0 && System.nanoTime() < deadline) {
            Retirement first = RETIRED.getFirst();
            int status = org.lwjgl.opengl.GL32.glClientWaitSync(first.fence, 0, 0);
            if (status == org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED) break;
            if (status == org.lwjgl.opengl.GL32.GL_WAIT_FAILED)
                throw new IllegalStateException("场景资源退休 fence 查询失败");
            RETIRED.removeFirst();
            org.lwjgl.opengl.GL32.glDeleteSync(first.fence);
            first.release.run();
        }
    }
    private static int nextScene;
    private final int sceneId = nextScene++;
    private ResourceLocation COLOR =
            ResourceLocation.fromNamespaceAndPath("minetale", "scene/" + sceneId + "/color");
    private final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath("minetale", "scene/" + sceneId + "/white");
    private RenderType lowType;
    private final RenderType rawType;
    private SceneTexture lowTexture;
    private final SceneTexture whiteTexture;
    final GpuBuffer low;
    private static final int INDEX_QUADS = 4096;
    private final GpuBuffer quadIndices;
    private final int[] lowIndexSource;
    private final LowBatch lowBatch = new LowBatch();
    private int quadIndexCursor;
    final int stride;
    private SceneMaterials materials;


    private boolean materialsAttempted, materialsReady, materialsFailed;
    private long materialsStart;
    private long materialCpuMax;

    boolean prepareMaterials() {
        if (materialsReady || materialsFailed) return materialsReady;
        long start = System.nanoTime();
        try {
            if (!materialsAttempted) {
                materialsAttempted = true;
                materialsStart = System.nanoTime();
                materials = new SceneMaterials();
            }
            materialsReady = materials.ready();
            if (materialsReady)
                cn.jehorstudio.minetale.MineTale.LOGGER.info(
                        "Scene material preparation: {} ms, max submission={} ms",
                        (System.nanoTime() - materialsStart) / 1e6,
                        Math.max(materialCpuMax, System.nanoTime() - start) / 1e6);
        } catch (IOException | RuntimeException failure) {
            materialsFailed = true;
            if (materials != null) {
                materials.close();
                materials = null;
            }
            cn.jehorstudio.minetale.MineTale.LOGGER.warn(
                    "Scene High material program unavailable; Low retained", failure);
        }
        materialCpuMax = Math.max(materialCpuMax, System.nanoTime() - start);
        return materialsReady;
    }

    String materialStats() {
        return materials == null
                ? "materialReady=false"
                : ("materialReady=" + materialsReady + " " + materials.stats());
    }

    private final java.util.ArrayList<Group> groups = new java.util.ArrayList<>();
    private int nextGroup;
    private final java.util.ArrayList<Group> drawGroups = new java.util.ArrayList<>();
    private long highVertexBytes, highTextureBytes, rawBytes, fixedBytes;
    private final java.util.ArrayList<GpuBuffer> spareVertices = new java.util.ArrayList<>();
    private long spareVertexBytes;
    private boolean closed;
    private final java.util.ArrayDeque<ByteBuffer> stagingPool = new java.util.ArrayDeque<>();
    private static final java.util.concurrent.atomic.AtomicLong ALL_STAGING = new java.util.concurrent.atomic.AtomicLong();
    static long allStagingBytes() { return ALL_STAGING.get(); }
    long lowUploadBytes() { return lowTexture.uploadBytes() + whiteTexture.uploadBytes(); }

    private GpuBuffer acquireVertices(int bytes) {
        GpuBuffer best = null;
        for (GpuBuffer candidate : spareVertices) {
            if (candidate.size() >= bytes && candidate.size() <= Math.max(4096L, bytes * 2L)
                    && (best == null || candidate.size() < best.size())) best = candidate;
        }
        if (best != null) {
            spareVertices.remove(best);
            spareVertexBytes -= best.size();
            return best;
        }
        int capacity = (int) Math.min(Integer.MAX_VALUE, (bytes + 4095L) / 4096 * 4096);
        GpuBuffer result = RenderSystem.getDevice().createBuffer(() -> "Scene fine brick",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
        highVertexBytes += capacity; return result;
    }
    private void releaseVertices(GpuBuffer buffer) {
        // 只接收 fence 已完成的缓冲；尺寸变化无需销毁其他可复用容量。
        if (!closed && buffer.size() <= 8 * (1L << 20)) {
            while (!spareVertices.isEmpty() && (spareVertices.size() >= 4
                    || spareVertexBytes + buffer.size() > 32 * (1L << 20))) {
                GpuBuffer oldest = spareVertices.removeFirst();
                spareVertexBytes -= oldest.size();
                highVertexBytes -= oldest.size();
                oldest.close();
            }
            spareVertices.add(buffer);
            spareVertexBytes += buffer.size();
        } else { highVertexBytes -= buffer.size(); buffer.close(); }
    }
    private ByteBuffer acquireStaging() {
        if (!stagingPool.isEmpty()) return stagingPool.removeFirst();
        ByteBuffer bytes = MemoryUtil.memAlloc(256 * 1024); ALL_STAGING.addAndGet(bytes.capacity()); return bytes;
    }
    private void releaseStaging(ByteBuffer bytes) {
        if (!closed && stagingPool.size() < Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors() - 2))) stagingPool.addLast(bytes);
        else { ALL_STAGING.addAndGet(-bytes.capacity()); MemoryUtil.memFree(bytes); }
    }

    long highBytes() {
        return highVertexBytes + highTextureBytes - fixedBytes;
    }
    long baseBytes() { return low.size() + indexBytes() + lowTexture.bytes() + whiteTexture.bytes() + rawBytes + fixedBytes; }

    void exchangeLowMaterials(SceneGpu prepared) {
        // 两份完整 Low 使用相同几何与布局；只移交图集，High 的拥有者及借用关系保持稳定。
        SceneTexture oldTexture = lowTexture; lowTexture = prepared.lowTexture; prepared.lowTexture = oldTexture;
        ResourceLocation oldId = COLOR; COLOR = prepared.COLOR; prepared.COLOR = oldId;
        RenderType oldType = lowType; lowType = prepared.lowType; prepared.lowType = oldType;
    }

    long extraBytes(SceneVoxels.Page page, SceneSeams.Edges edges) { return extraBytes(page, edges, 1); }
    long extraBytes(SceneVoxels.Page page, SceneSeams.Edges edges, int knownTiles) {
        long requested = ((long) page.vertices().length + edges.vertices().length) / 14 * stride;
        long vertices = (requested + 4095) / 4096 * 4096;
        for (GpuBuffer candidate : spareVertices)
            if (candidate.size() >= requested && candidate.size() <= Math.max(4096L, requested * 2)) {
                vertices = 0;
                break;
            }
        for (Group group : groups)
            if (group.matches(page) && group.used.cardinality() < group.columns * group.columns) return vertices;
        int columns = groupExtent(page.maximumSize(), knownTiles) / page.maximumSize();
        return vertices + page.textureBytes() * columns * columns
                + (page.outputSize(1) == 0 ? 20 : 0) + (page.outputSize(2) == 0 ? 20 : 0);
    }

    // 按整组新建估计上界，不借用尚未释放的槽位；重复计入旧图集只会推迟准入。
    long reservationBytes(List<SceneVoxels.Page> pages, List<SceneSeams.Edges> edges) {
        long bytes = 0;
        for (int i = 0; i < pages.size(); i++) {
            SceneVoxels.Page page = pages.get(i);
            bytes += ((long) page.vertices().length + edges.get(i).vertices().length) / 14 * stride;
            boolean first = true; int count = 0;
            for (int j = 0; j < pages.size(); j++) if (Arrays.equals(page.outputSizes(), pages.get(j).outputSizes())) {
                count++; if (j < i) first = false;
            }
            if (!first) continue;
            int columns = groupExtent(page.maximumSize(), count) / page.maximumSize();
            int capacity = columns * columns, groups = (count + capacity - 1) / capacity;
            bytes += groups * (page.textureBytes() * capacity
                    + (page.outputSize(1) == 0 ? 20 : 0) + (page.outputSize(2) == 0 ? 20 : 0));
        }
        return bytes;
    }

    long temporaryBytes() { return materials == null ? 0 : materials.temporaryBytes(); }

    void retireRaw(GpuBuffer buffer) { long bytes = buffer.size(); retire(() -> { buffer.close(); rawBytes -= bytes; }); }

    GpuBuffer rawBuffer(int vertices) {
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Scene Raw page",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, Math.multiplyExact(vertices, stride));
        rawBytes += buffer.size();
        return buffer;
    }

    void uploadRawRange(GpuBuffer buffer, float[] data, int first, int count) {
        ByteBuffer bytes = MemoryUtil.memAlloc(count * stride);
        try {
            SceneTerrain.encode(bytes, data, true, stride > 48, first, count);
            bytes.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(
                    buffer.slice(Math.multiplyExact(first, stride), Math.multiplyExact(count, stride)), bytes);
        } finally { MemoryUtil.memFree(bytes); }
    }

    private static int groupExtent(int tileSize, int knownTiles) {
        return tileSize <= 1024 ? tileSize * (tileSize <= 256 ? 4 : 2) : tileSize;
    }

    private Tile acquire(SceneVoxels.Page page, int knownTiles) throws IOException {
        for (Group group : groups)
            if (group.matches(page) && group.used.cardinality() < group.columns * group.columns)
                return group.acquire();
        Group group = new Group(page, knownTiles);
        groups.add(group);
        highTextureBytes += group.bytes;
        return group.acquire();
    }

    // 同尺寸砖共享图集和 RenderType。砖独立就绪与回收，图集在最后一块释放时销毁。
    private final class Group {
        final ResourceLocation location;
        final SceneTexture texture;
        final RenderType type;
        final int tileSize, columns;
        final int[] channelSizes;
        final long bytes;
        final java.util.BitSet used = new java.util.BitSet();

        final java.util.ArrayList<Draw> draws = new java.util.ArrayList<>();

        boolean matches(SceneVoxels.Page page) {
            return channelSizes[0] == page.outputSize(0)
                    && channelSizes[1] == page.outputSize(1)
                    && channelSizes[2] == page.outputSize(2) && channelSizes[3] == page.outputSize(3);
        }

        Group(SceneVoxels.Page page, int knownTiles) throws IOException {
            int size = page.maximumSize();
            channelSizes = new int[] {page.outputSize(0), page.outputSize(1), page.outputSize(2), page.outputSize(3)};
            tileSize = size;
            int extent = groupExtent(size, knownTiles);
            columns = extent / size;
            location =
                    ResourceLocation.fromNamespaceAndPath(
                            "minetale", "scene/" + sceneId + "/fine/group_" + nextGroup++);
            texture =
                    new SceneTexture(
                            location,
                            channelSizes[0] * columns,
                            channelSizes[0] * columns,
                            channelSizes[1] * columns,
                            channelSizes[2] * columns, channelSizes[3] * columns);
            bytes = texture.bytes();
            try {
                Minecraft.getInstance().getTextureManager().register(location, texture);
                type = terrainType(location, SceneTerrain.pipeline());
            } catch (RuntimeException failure) {
                Minecraft.getInstance().getTextureManager().release(location);
                throw failure;
            }
        }

        Tile acquire() {
            int index = used.nextClearBit(0);
            used.set(index);
            return new Tile(this, index);
        }

        void release(int index) {
            used.clear(index);
            if (used.isEmpty()) {
                Minecraft.getInstance().getTextureManager().release(location);
                groups.remove(this);
                highTextureBytes -= bytes;
            }
        }
    }

    private record Tile(Group group, int index) {
        int x() {
            return index % group.columns * group.tileSize;
        }

        int y() {
            return index / group.columns * group.tileSize;
        }
    }

    Upload beginFine(SceneVoxels.Page page, SceneSeams.Edges edges) throws IOException {
        return new Upload(page, edges, null, 1);
    }

    Upload beginFine(SceneVoxels.Page page, SceneSeams.Edges edges, Fine previous) throws IOException {
        return new Upload(page, edges, previous, 1);
    }

    Upload beginFine(SceneVoxels.Page page, SceneSeams.Edges edges, Fine previous, int knownTiles) throws IOException {
        return new Upload(page, edges, previous, knownTiles);
    }

    final class Upload implements AutoCloseable {
        private final SceneVoxels.Page page;
        private final SceneSeams.Edges edges;
        private final Fine previous;
        private GpuBuffer buffer;
        private Tile tile;
        private SceneMaterials.Bake bake;
        private ByteBuffer staging;
        private int vertex;
        long uploaded;

        Upload(SceneVoxels.Page page, SceneSeams.Edges edges, Fine previous, int knownTiles) throws IOException {
            this.page = page;
            this.edges = edges;
            this.previous = previous;
            try {
                // 只有 Low 接缝时借用 Low 材质，不为零面积 High 创建图集和烘焙任务。
                if (page.vertices().length > 0) tile = acquire(page, knownTiles);
                buffer = acquireVertices(Math.multiplyExact(Math.addExact(page.vertices().length, edges.vertices().length) / 14, stride));
                staging = acquireStaging();
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        }

        boolean advance() throws IOException {
            if (!uploadVertices()) return false;
            if (tile == null) return true;
            if (bake == null) {
                bake = tile.group.texture.beginBake(page, materials, tile.x(), tile.y());
                if (page.reusesColor()) {
                    if (previous == null || previous.closed) throw new IOException("复用的颜色成果已失效");
                    int colorSize = previous.layout.colorSize();
                    previous.tile.group.texture.reuseColor(bake,
                            previous.tile.index % previous.tile.group.columns * colorSize,
                            previous.tile.index / previous.tile.group.columns * colorSize);
                }
            }
            boolean ready = bake.advance();
            uploaded += bake.uploaded;
            return ready;
        }

        /** 顶点阶段：把页面与边缘顶点编码写入 buffer，按 512 KiB 块额度分批；完成返回 true。 */
        boolean uploadVertices() throws IOException {
            uploaded = 0;
            int fineVertices = page.vertices().length / 14;
            while (vertex < fineVertices + edges.vertices().length / 14) {
                boolean edge = vertex >= fineVertices;
                float[] data = edge ? edges.vertices() : page.vertices();
                int first = edge ? vertex - fineVertices : vertex;
                int count =
                        (int)
                                Math.min(
                                        data.length / 14 - first,
                                        staging.capacity() / stride / 4 * 4);
                if (count == 0) return false;
                staging.clear();
                // 边缘借用 Low 的 LabPBR 图集；顶点与 High 共用分批上传和回收生命周期。
                float scale = edge ? 1 : 1F / tile.group.columns;
                float offsetU = edge ? 0 : (tile.index % tile.group.columns) * scale;
                float offsetV = edge ? 0 : (tile.index / tile.group.columns) * scale;
                SceneTerrain.encode(
                        staging, data, false, stride > 48, first, count, scale, offsetU, offsetV);
                staging.flip();
                RenderSystem.getDevice()
                        .createCommandEncoder()
                        .writeToBuffer(buffer.slice(vertex * stride, count * stride), staging);
                vertex += count;
                uploaded += (long) count * stride;
                // 达到块额度才让出；末块完成后可直接进入材质阶段。
                if (uploaded >= 512 * 1024
                        && vertex < fineVertices + edges.vertices().length / 14) return false;
            }
            return true;
        }

        /** 顶点阶段是否已完成；缓存直写路径必须先完成顶点写入再写纹素。 */
        boolean verticesReady() {
            return vertex >= page.vertices().length / 14 + edges.vertices().length / 14;
        }

        // ---- 烘焙结果磁盘缓存：直写与回读（SceneFixed 使用） ----

        private int cacheChannel, cacheLevel;

        /**
         * 把缓存的烘焙像素（ARGB）直写进图集 tile 区域，按 8 MiB/帧预算分批完成；
         * channel 3 是 R8 自发光，取 R 通道写 LUMINANCE。
         *
         * @return 全部区域写入完毕时 true
         */
        boolean writeCachedTiles(int[][][] pixels) {
            uploaded = 0;
            if (tile == null) return true;
            while (cacheChannel < 4) {
                int[] region = pixels[cacheChannel] == null ? null : pixels[cacheChannel][cacheLevel];
                if (region != null) {
                    int size = tile.group().tileSize >> cacheLevel;
                    int x = tile.x() >> cacheLevel, y = tile.y() >> cacheLevel;
                    GpuTexture target = tile.group().texture.channelTexture(cacheChannel);
                    if (cacheChannel == 3) {
                        ByteBuffer bytes = MemoryUtil.memAlloc(size * size);
                        for (int value : region) bytes.put((byte) (value >>> 16));
                        bytes.flip();
                        RenderSystem.getDevice()
                                .createCommandEncoder()
                                .writeToTexture(
                                        target, bytes, NativeImage.Format.LUMINANCE, cacheLevel, 0, x, y, size, size);
                        MemoryUtil.memFree(bytes);
                    } else {
                        ByteBuffer bytes = MemoryUtil.memAlloc(size * size * 4);
                        for (int value : region)
                            bytes.put((byte) (value >>> 16))
                                    .put((byte) (value >>> 8))
                                    .put((byte) value)
                                    .put((byte) (value >>> 24));
                        bytes.flip();
                        RenderSystem.getDevice()
                                .createCommandEncoder()
                                .writeToTexture(
                                        target, bytes, NativeImage.Format.RGBA, cacheLevel, 0, x, y, size, size);
                        MemoryUtil.memFree(bytes);
                    }
                    uploaded += (long) size * size * 4;
                }
                if (++cacheLevel == 2) {
                    cacheLevel = 0;
                    cacheChannel++;
                }
                if (uploaded >= 8 * 1024 * 1024) return false;
            }
            return true;
        }

        /**
         * 烘焙完成后发起 tile 区域（4 通道 × 2 mip）的异步回读：像素 DMA 进 PBO，fence 就绪后再映射。
         * 直接 glReadPixels 会同步等待整个 GPU 队列排空，进场时段队列深时足以冻结渲染线程数秒以上。
         * 返回值与 Upload 生命周期解耦，由调用方逐帧轮询；渲染线程调用。
         */
        Readback beginReadback() throws IOException {
            if (tile == null) return new Readback();
            GpuTexture[] channels = new GpuTexture[4];
            for (int c = 0; c < 4; c++) channels[c] = tile.group().texture.channelTexture(c);
            return new Readback(channels, tile.group().tileSize, tile.x(), tile.y());
        }

        Fine finish() {
            Fine result =
                    new Fine(
                            buffer,
                            tile,
                            page.surfaceTriangles(),
                            page.vertices().length / 14,
                            page.capOffsets(),
                            page.capTriangles(),
                            page.gpuBytes(stride) + edges.gpuBytes(stride),
                            edges.offsets(),
                            edges.triangles(), page.layout());
            tile = null;
            buffer = null;
            close();
            return result;
        }

        @Override
        public void close() {
            SceneMaterials.Bake oldBake = bake;
            Tile oldTile = tile;
            GpuBuffer oldBuffer = buffer;
            bake = null;
            tile = null;
            buffer = null;
            if (oldBake != null || oldTile != null || oldBuffer != null) retire(() -> {
                if (oldBake != null) oldBake.close();
                if (oldTile != null) oldTile.group.release(oldTile.index);
                if (oldBuffer != null) {
                    releaseVertices(oldBuffer);
                }
            });
            if (staging != null) {
                releaseStaging(staging);
                staging = null;
            }
        }
    }

    /** 烘焙结果异步回读：像素先 DMA 进 PBO，fence 就绪后映射转换为 ARGB；全程渲染线程调用。 */
    static final class Readback implements AutoCloseable {
        private final int[] buffers = new int[8];
        private final int[] sizes = new int[8], lengths = new int[8];
        private final boolean[] luminance = new boolean[8];
        private long fence;

        Readback() {}

        Readback(GpuTexture[] channels, int tileSize, int x, int y) throws IOException {
            int previousFramebuffer = GL43.glGetInteger(GL43.GL_READ_FRAMEBUFFER_BINDING);
            int framebuffer = GL43.glGenFramebuffers();
            try {
                GL43.glBindFramebuffer(GL43.GL_READ_FRAMEBUFFER, framebuffer);
                for (int slot = 0; slot < 8; slot++) {
                    int c = slot >> 1, level = slot & 1;
                    if (!(channels[c] instanceof GlTexture gl)) throw new IOException("烘焙缓存回读需要 OpenGL 纹理后端");
                    int size = tileSize >> level;
                    sizes[slot] = size;
                    lengths[slot] = c == 3 ? size * size : size * size * 4;
                    luminance[slot] = c == 3;
                    buffers[slot] = GL43.glGenBuffers();
                    GL43.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
                    GL43.glBufferData(
                            org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER,
                            lengths[slot],
                            org.lwjgl.opengl.GL21.GL_STREAM_READ);
                    GL43.glFramebufferTexture2D(
                            GL43.GL_READ_FRAMEBUFFER,
                            GL43.GL_COLOR_ATTACHMENT0,
                            GL43.GL_TEXTURE_2D,
                            gl.glId(),
                            level);
                    if (GL43.glCheckFramebufferStatus(GL43.GL_READ_FRAMEBUFFER) != GL43.GL_FRAMEBUFFER_COMPLETE)
                        throw new IOException("烘焙缓存回读 framebuffer 不完整");
                    if (c == 3)
                        GL43.glReadPixels(
                                x >> level, y >> level, size, size, GL43.GL_RED, GL43.GL_UNSIGNED_BYTE, 0);
                    else
                        GL43.glReadPixels(
                                x >> level, y >> level, size, size, GL43.GL_RGBA, GL43.GL_UNSIGNED_BYTE, 0);
                }
                GL43.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
                fence = GL43.glFenceSync(GL43.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                GL43.glFlush();
            } finally {
                GL43.glBindFramebuffer(GL43.GL_READ_FRAMEBUFFER, previousFramebuffer);
                GL43.glDeleteFramebuffers(framebuffer);
            }
        }

        /** GPU 未就绪返回 null；就绪后映射全部槽位并转换为 ARGB 像素。 */
        int[][][] poll() throws IOException {
            if (fence != 0) {
                int status = GL43.glClientWaitSync(fence, 0, 0);
                if (status == GL43.GL_TIMEOUT_EXPIRED) return null;
                if (status == GL43.GL_WAIT_FAILED) throw new IOException("烘焙缓存回读 fence 查询失败");
                GL43.glDeleteSync(fence);
                fence = 0;
            }
            int[][][] pixels = new int[4][][];
            for (int slot = 0; slot < 8; slot++) {
                if (sizes[slot] == 0) continue;
                int c = slot >> 1, level = slot & 1, size = sizes[slot];
                if (pixels[c] == null) pixels[c] = new int[2][];
                GL43.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
                ByteBuffer data =
                        GL43.glMapBufferRange(
                                org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER,
                                0,
                                lengths[slot],
                                org.lwjgl.opengl.GL30.GL_MAP_READ_BIT);
                if (data == null) throw new IOException("烘焙缓存回读映射失败");
                int[] region = new int[size * size];
                if (luminance[slot]) {
                    for (int i = 0; i < region.length; i++) {
                        int v = data.get(i) & 255;
                        region[i] = 0xFF000000 | v << 16 | v << 8 | v;
                    }
                } else {
                    for (int i = 0; i < region.length; i++) {
                        region[i] =
                                (data.get(i * 4 + 3) & 255) << 24
                                        | (data.get(i * 4) & 255) << 16
                                        | (data.get(i * 4 + 1) & 255) << 8
                                        | (data.get(i * 4 + 2) & 255);
                    }
                }
                GL43.glUnmapBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER);
                pixels[c][level] = region;
            }
            GL43.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
            close();
            return pixels;
        }

        @Override
        public void close() {
            if (fence != 0) {
                GL43.glDeleteSync(fence);
                fence = 0;
            }
            for (int slot = 0; slot < 8; slot++)
                if (buffers[slot] != 0) {
                    GL43.glDeleteBuffers(buffers[slot]);
                    buffers[slot] = 0;
                }
        }
    }

    SceneGpu(SceneImages encoded, int width, int height, int vertexCount, int[] indices) throws IOException {
        RenderPipeline pipeline = SceneTerrain.pipeline();
        lowType = terrainType(COLOR, pipeline);
        rawType = terrainType(WHITE, pipeline);
        var textures = Minecraft.getInstance().getTextureManager();
        stride = pipeline.getVertexFormat().getVertexSize();
        try {
            lowTexture = new SceneTexture(COLOR, encoded, width, height);
            textures.register(COLOR, lowTexture);
            // 兜底色 = 主体烘焙反照率实测均值（红棕铜）；烘焙就绪前的主体不再纯白。
            whiteTexture = new SceneTexture(WHITE, null, 16, 16, 0xFF402121);
            textures.register(WHITE, whiteTexture);
            low =
                    RenderSystem.getDevice()
                            .createBuffer(
                                    () -> "Scene resident Low",
                                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                                    Math.multiplyExact(vertexCount, stride));
            quadIndices = RenderSystem.getDevice().createBuffer(() -> "Scene shared rectangle indices",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, INDEX_QUADS * 6 * 4);
            lowIndexSource = indices;
            LIVE.add(this);
        } catch (IOException | RuntimeException failure) {
            textures.release(COLOR);
            textures.release(WHITE);
            throw failure;
        }
    }

    long uploadTextures() throws IOException {
        return !lowTexture.uploaded() ? lowTexture.upload(1024 * 1024) : !whiteTexture.uploaded() ? whiteTexture.upload(1024 * 1024) : uploadIndices();
    }


    boolean texturesReady() {
        return lowTexture.uploaded() && whiteTexture.uploaded() && quadIndexCursor == INDEX_QUADS * 6;
    }

    private long uploadIndices() {
        int first = quadIndexCursor;
        int end = INDEX_QUADS * 6;
        int count = Math.min(256 * 1024, (end - first) * 4) / 4;
        if (count == 0) return 0;
        long start = System.nanoTime();
        ByteBuffer bytes = MemoryUtil.memAlloc(count * 4);
        try {
            int[] pattern = {0, 1, 2, 0, 2, 3};
            for (int i = first; i < first + count; i++) bytes.putInt(i / 6 * 4 + pattern[i % 6]);
            bytes.flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(quadIndices.slice(first * 4, count * 4), bytes);
        } finally { MemoryUtil.memFree(bytes); }
        quadIndexCursor += count;
        return (long) count * 4;
    }

    long indexBytes() { return quadIndices.size()
            + (lowBatch.indices == null ? 0 : lowBatch.indices.size()); }

    GpuBuffer upload(float[] data, int lowVertexOffset, boolean raw) {
        int vertices = data.length / (raw ? 8 : 14);
        ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(vertices, stride));
        try {
            SceneTerrain.encode(bytes, data, raw, stride > 48);
            bytes.flip();
            if (raw) {
                GpuBuffer result = RenderSystem.getDevice().createBuffer(() -> "Scene Raw page", GpuBuffer.USAGE_VERTEX, bytes);
                rawBytes += result.size();
                return result;
            }
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(low.slice(lowVertexOffset * stride, vertices * stride), bytes);
            return null;
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    void uploadRange(float[] data, int first, int count, int destination) {
        ByteBuffer bytes = MemoryUtil.memAlloc(Math.multiplyExact(count, stride));
        try {
            SceneTerrain.encode(bytes, data, false, stride > 48, first, count);
            bytes.flip();
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(
                            low.slice(
                                    Math.multiplyExact(destination, stride),
                                    Math.multiplyExact(count, stride)),
                            bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    int draw(
            RenderLevelStageEvent.AfterOpaqueBlocks event,
            Vec3 origin,
            List<Draw> lows,
            List<Draw> raws,
            List<Fine> fine) {
        return draw(event.getModelViewMatrix(), event.getLevelRenderState().cameraRenderState.pos, origin, lows, raws, fine);
    }

    int draw(Matrix4f modelView, Vec3 camera, Vec3 origin, List<Draw> lows, List<Draw> raws, List<Fine> fine) {
        Vector3f modelOffset =
                new Vector3f(
                        (float) (origin.x - camera.x),
                        (float) (origin.y - camera.y),
                        (float) (origin.z - camera.z));
        int draws = 0;
        lowBatch.prepare(lows);
        // 绘制集合按帧复用，已创建的砖和截面直接引用稳定的 Draw。
        for (Group group : drawGroups) group.draws.clear();
        drawGroups.clear();
        for (Fine page : fine) {
            if (page.tile == null || (page.triangles == 0 && page.capMask == 0)) continue;
            Group group = page.tile.group;
            var batch = group.draws;
            boolean empty = batch.isEmpty();
            if (page.triangles > 0) {
                batch.add(page.surfaceDraw);
            }
            for (int d = 0; d < 6; d++)
                if ((page.capMask & 1 << d) != 0 && page.capTriangles[d] > 0) {
                    batch.add(page.capDraws[d]);
                }
            if (empty && !batch.isEmpty()) drawGroups.add(group);
        }
        for (Group group : drawGroups)
            draws += drawLayer(
                    group.type,
                    group.location,
                    modelView,
                    modelOffset,
                    group.draws, false);
        // High 先写入深度，遮住的远景 Low 可直接通过深度测试跳过片元着色。
        draws += drawLayer(rawType, WHITE, modelView, modelOffset, raws, true);
        draws += drawLayer(lowType, COLOR, modelView, modelOffset, lows, false);
        return draws;
    }

    private int drawLayer(
            RenderType type,
            ResourceLocation texture,
            Matrix4f modelView,
            Vector3f modelOffset,
            List<Draw> draws, boolean raw) {
        if (draws.isEmpty()) return 0;
        int count = 0;
        type.setupRenderState();
        try {
            var target = Minecraft.getInstance().getMainRenderTarget();
            var color =
                    RenderSystem.outputColorTextureOverride != null
                            ? RenderSystem.outputColorTextureOverride
                            : target.getColorTextureView();
            var depth =
                    RenderSystem.outputDepthTextureOverride != null
                            ? RenderSystem.outputDepthTextureOverride
                            : target.getDepthTextureView();
            var transforms =
                    RenderSystem.getDynamicUniforms()
                            .writeTransform(
                                    modelView, new Vector4f(1), modelOffset, new Matrix4f(), 1);
            try (RenderPass pass =
                    RenderSystem.getDevice()
                            .createCommandEncoder()
                            .createRenderPass(
                                    () -> "MineTale scene static pages",
                                    color,
                                    OptionalInt.empty(),
                                    depth,
                                    OptionalDouble.empty())) {
                pass.setPipeline(type.pipeline());
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                SceneTexture channels =
                        (SceneTexture)
                                Minecraft.getInstance().getTextureManager().getTexture(texture);
                pass.bindSampler("Sampler0", channels.getTextureView());
                pass.bindSampler("SceneEmission", channels.emissionView());
                if (RenderSystem.getShaderTexture(2) != null)
                    pass.bindSampler("Sampler2", RenderSystem.getShaderTexture(2));
                if (texture.equals(COLOR) && lowBatch.indexCount > 0) {
                    pass.setVertexBuffer(0, low);
                    pass.setIndexBuffer(lowBatch.indices, com.mojang.blaze3d.vertex.VertexFormat.IndexType.INT);
                    pass.drawIndexed(0, 0, lowBatch.indexCount, 1);
                    count++;
                }
                for (Draw draw : draws) {
                    if (draw.buffer == low) continue;
                    pass.setVertexBuffer(0, draw.buffer);
                    if (raw) { pass.draw(draw.firstVertex, draw.triangles * 3); count++; }
                    else {
                        pass.setIndexBuffer(quadIndices, com.mojang.blaze3d.vertex.VertexFormat.IndexType.INT);
                        for (int first = 0; first < draw.triangles / 2; first += INDEX_QUADS) {
                            int quads = Math.min(INDEX_QUADS, draw.triangles / 2 - first);
                            pass.drawIndexed(draw.firstVertex + first * 4, 0, quads * 6, 1); count++;
                        }
                    }
                }
            }
        } finally {
            type.clearRenderState();
        }
        return count;
    }

    private static RenderType terrainType(ResourceLocation texture, RenderPipeline pipeline) {
        return RenderType.create(
                "minetale_scene_terrain",
                1536,
                pipeline,
                RenderType.CompositeState.builder()
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false))
                        .createCompositeState(false));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        while (!stagingPool.isEmpty()) { ByteBuffer bytes = stagingPool.removeFirst(); ALL_STAGING.addAndGet(-bytes.capacity()); MemoryUtil.memFree(bytes); }
        lowBatch.close();
        if (materials != null) materials.close();
        retire(() -> {
            low.close();
            for (GpuBuffer buffer : spareVertices) { highVertexBytes -= buffer.size(); buffer.close(); }
            spareVertices.clear();
            spareVertexBytes = 0;
            quadIndices.close();
            var textures = Minecraft.getInstance().getTextureManager();
            textures.release(COLOR);
            textures.release(WHITE);
            LIVE.remove(this);
        });
    }

    record Draw(GpuBuffer buffer, int firstVertex, int firstIndex, int triangles) {
        Draw(GpuBuffer buffer, int firstVertex, int triangles) { this(buffer, firstVertex, -1, triangles); }
    }

    // Low 已共享顶点缓冲；只在可见范围变化时重建索引，所有离散页一次提交。
    // 原有顶点和三角形绕序保持不变，High 借用 Low 材质的截面保留独立范围。
    private final class LowBatch {
        private final java.util.ArrayList<Draw> selected = new java.util.ArrayList<>();
        private final java.util.ArrayList<Draw> previous = new java.util.ArrayList<>();
        private GpuBuffer indices;
        private ByteBuffer staging;
        private int indexCount;

        void prepare(List<Draw> draws) {
            selected.clear();
            for (Draw draw : draws) if (draw.buffer == low) selected.add(draw);
            if (selected.equals(previous)) return;
            indexCount = 0;
            for (Draw draw : selected) indexCount = Math.addExact(indexCount, draw.triangles * 3);
            if (indexCount == 0) { previous.clear(); return; }
            int bytes = Math.multiplyExact(indexCount, 4);
            if (indices == null || indices.size() < bytes) {
                int capacity = Math.max(4096, Math.multiplyExact(Integer.highestOneBit(bytes - 1), 2));
                GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> "Scene visible Low indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, capacity);
                ByteBuffer next;
                try { next = MemoryUtil.memAlloc(capacity); }
                catch (RuntimeException | Error failure) { replacement.close(); throw failure; }
                if (indices != null) { GpuBuffer old = indices; retire(old::close); }
                if (staging != null) { ALL_STAGING.addAndGet(-staging.capacity()); MemoryUtil.memFree(staging); }
                indices = replacement;
                staging = next;
                ALL_STAGING.addAndGet(capacity);
            }
            staging.clear();
            var values = staging.asIntBuffer();
            for (Draw draw : selected) {
                if (draw.firstIndex >= 0) values.put(lowIndexSource, draw.firstIndex, draw.triangles * 3);
                else for (int q = 0; q < draw.triangles / 2; q++) {
                    int v = draw.firstVertex + q * 4;
                    values.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
                }
            }
            staging.limit(bytes);
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(indices.slice(0, bytes), staging);
            previous.clear();
            previous.addAll(selected);
        }

        void close() {
            if (indices != null) { GpuBuffer old = indices; indices = null; retire(old::close); }
            if (staging != null) {
                ALL_STAGING.addAndGet(-staging.capacity()); MemoryUtil.memFree(staging); staging = null;
            }
            selected.clear(); previous.clear();
        }
    }

    final class Fine implements AutoCloseable {
        final GpuBuffer buffer;
        private final Tile tile;
        final int triangles, edgeBase;
        final int[] capOffsets, capTriangles;
        int capMask;
        final long bytes;
        final int[] edgeOffsets, edgeTriangles;
        final SceneVoxels.MaterialLayout layout;
        private boolean closed;
        final Draw surfaceDraw;
        private long fixedAllocation;
        final Draw[] capDraws = new Draw[6];

        Fine(
                GpuBuffer buffer,
                Tile tile,
                int triangles,
                int edgeBase,
                int[] capOffsets,
                int[] capTriangles,
                long bytes,
                int[] edgeOffsets,
                int[] edgeTriangles, SceneVoxels.MaterialLayout layout) {
            this.buffer = buffer;
            this.tile = tile;
            this.triangles = triangles;
            this.edgeBase = edgeBase;
            this.capOffsets = capOffsets;
            this.capTriangles = capTriangles;
            this.bytes = bytes;
            this.edgeOffsets = edgeOffsets;
            this.edgeTriangles = edgeTriangles;
            this.layout = layout;
            surfaceDraw = new Draw(buffer, 0, triangles);
            for (int d = 0; d < 6; d++) capDraws[d] = new Draw(buffer, capOffsets[d], capTriangles[d]);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            retire(() -> {
                fixedBytes -= fixedAllocation;
                releaseVertices(buffer);
                if (tile != null) tile.group.release(tile.index);
            });
        }

        void markFixed() {
            fixedAllocation = buffer.size() + (tile == null ? 0 : tile.group.bytes / (tile.group.columns * tile.group.columns));
            fixedBytes += fixedAllocation;
        }
    }
}
