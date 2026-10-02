package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneImages;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneParts;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneLayout;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

// 共享原型的几何和图集各有一份 GPU 副本；每次绘制只上传可见摆放的矩阵。
final class SceneInstances implements AutoCloseable {
    private static final long BUDGET = 768L << 20;
    private static final int UPLOAD = 1 << 20, INDEX_QUADS = 65536;
    // 上传吞吐按时间补充令牌，与帧率解耦；空闲累积为小突发额度。
    private static final long UPLOAD_RATE = 64L << 20, UPLOAD_BURST = 4L << 20;
    // 单帧上传 CPU 软预算：纹理像素转换与顶点编码共用，超时让出、令牌保留。
    private static final long UPLOAD_CPU = 2_000_000L;
    // 驱逐冷却与 LRU 用单调时钟；缓存保护不随帧率升高而缩短。
    private static final long COOLDOWN = 2_000_000_000L;
    // GPU 缓冲回收后保留解码结果；重新驻留不再退回完整 readMesh。
    private static final long DECODED_BUDGET = 128L << 20;
    // arena 准入上限在软预算余额上放宽一个步进：被保护的当前需求可短暂超额。
    private static final long ARENA_GROW = 64L << 20;
    // 动态层预算线 = 池容量 - 余量；余量为碎片整理保留连续段空间。
    private static final long DYN_MARGIN = 32L << 20;
    // 每帧驱逐上限，与退休 fence 的回收节奏一致，削平驱逐尖峰。
    private static final int EVICT_FRAME = 8;
    // 连续等待 5 秒优先级翻倍，限定低优先级网格的饿死上界。
    private static final long AGE_DOUBLE = 5_000_000_000L;
    private static int nextId;
    private final int id = nextId++;
    private final Executor worker;
    private final boolean shaderPack;
    private final CompletableFuture<Prepared> loading;

    private record Prepared(SceneParts parts, SceneAttachments attachments) {}

    private SceneAttachments attachments;
    private SceneParts parts;
    private final Map<Integer, Mesh> meshes = new HashMap<>();
    private Atlas[] atlases;
    private Placement[] placements;
    private final List<Job> jobs = new ArrayList<>();
    private final List<Batch> batches = new ArrayList<>();
    private GpuBuffer indices;
    // 全部驻留网格共用单一顶点 arena；meshBytes 记驻留字节数，arenaBytes 记容量。
    // 容量初始化即定（静态层 + min(准入余量, 精细层全量)），运行期不增长、不搬移数据。
    // atlasPlanned 为图集规划总量：参与准入余量计算，图集流式落地前 arena 不占其份额。
    private GpuBuffer arena;
    private long arenaBytes, atlasPlanned, staticBytes, dynamicBudget;
    // 本帧因碎片无连续段而推迟驻留的最大请求；驱动空洞生长驱逐拼段。
    private long arenaWanted;
    private int evicted;
    // 一轮准入共享的驱逐候选：只建立一次、按 usedAt 排序、各请求顺序消费。
    private java.util.List<java.util.Map.Entry<Integer, Mesh>> evictCandidates;
    private int evictCursor;
    // 待 fence 的空洞预约：目标优先取得 [windowLo, windowHi)，空闲部分扣住防切碎。
    private Mesh windowTarget;
    private long windowLo, windowHi;
    private final List<long[]> windowHeld = new ArrayList<>();
    // 预约窗口内尚待驱逐的网格：完整方案一次选定，驱逐由每帧额度分帧执行。
    private final List<Mesh> windowPending = new ArrayList<>();
    private final java.util.TreeMap<Long, Long> arenaFree = new java.util.TreeMap<>();
    private int storage, storageBytes, triangles, draws, passes, visible;
    private ByteBuffer staging;
    private long atlasBytes, meshBytes, sharedBytes, retiredBytes, decodedBytes, preparedBytes;
    private long uploadTokens = UPLOAD_BURST, uploadStamp;
    // 插入序即冷热序：命中即取出，重新驻留完成后回到队尾。
    private final Map<Integer, SceneParts.Mesh> decoded = new java.util.LinkedHashMap<>();
    // 提交路径的 CPU 耗时指数均值（ms），供性能归因；0.95/0.05 平滑。
    private double advanceMs, drawMs;
    private boolean closed;

    SceneInstances(SceneAsset asset, SceneLayout layout, Executor worker, boolean shaderPack) {
        this.worker = worker;
        this.shaderPack = shaderPack;
        loading =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                SceneParts catalog = new SceneParts(asset);
                                return new Prepared(
                                        catalog, new SceneAttachments(asset, layout, catalog));
                            } catch (IOException failure) {
                                throw new CompletionException(failure);
                            }
                        },
                        worker);
    }

    boolean prepare() {
        if (!loading.isDone()) return false;
        if (parts == null) initialize();
        return true;
    }

    SceneAttachments attachments() {
        return attachments;
    }

    void attach(Map<SceneLayout.Cell, SceneAttachments.Cell> active) {
        if (attachments == null || !attachments.update(active, false)) return;
        for (Placement p : placements) {
            float[] position = attachments.position(p.source);
            Vector3f delta =
                    new Vector3f(
                            position[0] - p.anchor.x,
                            position[1] - p.anchor.y,
                            position[2] - p.anchor.z);
            p.transform.setTranslation(
                    p.transform.m30() + delta.x,
                    p.transform.m31() + delta.y,
                    p.transform.m32() + delta.z);
            p.anchor.set(position);
            p.rebuild(p.localBounds);
        }
    }

    private static final class Mesh {
        final int prototype, level;
        SceneParts.Mesh data;
        // arena 驻留区间：base 为顶点基址（drawIndexed 第一参直接叠加），-1 表示未驻留。
        int base = -1, rangeBytes;
        int cursor;
        long usedAt, wantedAt;
        int[] offsets, quads;
        boolean failed;
        // base >= 0 只表示区间已预留；完整上传完成后才可绘制。
        boolean uploaded;

        Mesh(int prototype, int level) {
            this.prototype = prototype;
            this.level = level;
        }
    }

    private static final class Atlas {
        ResourceLocation location;
        SceneTexture texture;
        RenderType type;
        boolean loading, failed;
    }

    private record Job(Mesh mesh, int atlas, CompletableFuture<?> future) {}

    // 空闲段内可参与 best-fit 的窗口外片段：owner 记所属空闲段键便于分配后拆段。
    private record Part(long owner, long at, long size) {}

    // firstInstance 在矩阵打包时固定；同一实例区间可被多个图集 pass 引用。
    private static final class Batch {
        final Mesh mesh;
        final boolean mirrored;
        final List<Placement> placements;
        int firstInstance;

        Batch(Mesh mesh, boolean mirrored, List<Placement> placements) {
            this.mesh = mesh;
            this.mirrored = mirrored;
            this.placements = placements;
        }
    }

    private static final class Placement {
        final int source, prototype;
        final Matrix4f transform;
        final Vector3f anchor;
        final Matrix3f normal;
        final boolean mirrored;
        final float scale;
        final float[] rows = new float[28];
        final float[] localBounds;
        AABB bounds;
        int level;

        Placement(int source, int prototype, Matrix4f transform, float[] bounds, Vector3f anchor) {
            this.source = source;
            this.prototype = prototype;
            this.transform = transform;
            this.anchor = new Vector3f(anchor);
            this.localBounds = bounds;
            normal = new Matrix3f(transform).invert().transpose();
            mirrored = transform.determinant3x3() < 0;
            scale = maxStretch(new Matrix3f(transform));
            rebuild(bounds);
        }

        // σmax 上界取 G = MᵀM 最大绝对行和的平方根：Gershgorin 圆盘在 PSD（圆心
        // 非负）下给出 λmax(G) ≤ max 行和；列向量正交时 G 为对角阵，上界即精确的
        // 最大列长度，剪切只增加交叉项的保守余量。double 计算后向 +∞ 取相邻
        // float，舍入不破坏上界。
        private static float maxStretch(Matrix3f m) {
            float x0 = m.m00(), y0 = m.m01(), z0 = m.m02();
            float x1 = m.m10(), y1 = m.m11(), z1 = m.m12();
            float x2 = m.m20(), y2 = m.m21(), z2 = m.m22();
            double c0 = (double) x0 * x0 + (double) y0 * y0 + (double) z0 * z0;
            double c1 = (double) x1 * x1 + (double) y1 * y1 + (double) z1 * z1;
            double c2 = (double) x2 * x2 + (double) y2 * y2 + (double) z2 * z2;
            double d01 = Math.abs((double) x0 * x1 + (double) y0 * y1 + (double) z0 * z1);
            double d02 = Math.abs((double) x0 * x2 + (double) y0 * y2 + (double) z0 * z2);
            double d12 = Math.abs((double) x1 * x2 + (double) y1 * y2 + (double) z1 * z2);
            double bound = Math.max(c0 + d01 + d02, Math.max(d01 + c1 + d12, d02 + d12 + c2));
            return Math.nextUp((float) Math.sqrt(bound));
        }

        void rebuild(float[] local) {
            Vector3f lo = new Vector3f(Float.POSITIVE_INFINITY),
                    hi = new Vector3f(Float.NEGATIVE_INFINITY),
                    v = new Vector3f();
            for (int c = 0; c < 8; c++) {
                transform.transformPosition(
                        v.set(
                                local[(c & 1) == 0 ? 0 : 3],
                                local[(c & 2) == 0 ? 1 : 4],
                                local[(c & 4) == 0 ? 2 : 5]));
                lo.min(v);
                hi.max(v);
            }
            bounds = new AABB(lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 4; c++) rows[r * 4 + c] = transform.get(c, r);
                for (int c = 0; c < 3; c++) rows[12 + r * 4 + c] = normal.get(c, r);
            }
            rows[24] = mirrored ? -1 : 1;
        }
    }

    private void initialize() {
        Prepared prepared = loading.join();
        parts = prepared.parts;
        attachments = prepared.attachments;
        atlases = new Atlas[parts.atlases().size()];
        Arrays.setAll(atlases, ignored -> new Atlas());
        var all = new ArrayList<Placement>();
        for (int i = 0; i < parts.instances().size(); i++) {
            var instance = parts.instances().get(i);
            var prototype = parts.prototypes().get(instance.prototype());
            float[] linear = instance.linear();
            float[] localBounds = prototype.lods().getFirst().bounds().clone();
            for (var lod : prototype.lods())
                for (int axis = 0; axis < 3; axis++) {
                    localBounds[axis] = Math.min(localBounds[axis], lod.bounds()[axis]);
                    localBounds[axis + 3] = Math.max(localBounds[axis + 3], lod.bounds()[axis + 3]);
                }
            Vector3f position = new Vector3f(attachments.position(i));
            Matrix4f base =
                    affine(
                            new float[] {
                                linear[0],
                                linear[1],
                                linear[2],
                                position.x,
                                linear[3],
                                linear[4],
                                linear[5],
                                position.y,
                                linear[6],
                                linear[7],
                                linear[8],
                                position.z
                            });
            for (float[] copy : prototype.copies())
                all.add(
                        new Placement(
                                i,
                                instance.prototype(),
                                new Matrix4f(base).mul(affine(copy)),
                                localBounds,
                                position));
        }
        placements = all.toArray(Placement[]::new);
        staging = MemoryUtil.memAlloc(UPLOAD);
        indices = createIndices();
        sharedBytes = indices.size();
        // 按 SceneTexture.bytes() 同口径预估全部图集；normal/specular 仅在光影模式下加载。
        for (var metadata : parts.atlases()) {
            long area = (long) metadata.width() * metadata.height();
            atlasPlanned += area * 5 + area * 5 / 4 + 40;
            if (shaderPack && metadata.channels().containsKey("normal"))
                atlasPlanned += area * 5 - 20;
            if (shaderPack && metadata.channels().containsKey("specular"))
                atlasPlanned += area * 5 - 20;
        }
        // 静态层（LOD1-2 全量常驻、前缀固定偏移、永不驱逐）与容量在初始化时定死：
        // 粗级兜底不受动态预算约束，运行期没有任何数据搬移。动态预算线留出碎片余量。
        int stride = SceneTerrain.instances().getVertexFormat().getVertexSize();
        long fine = 0;
        for (var prototype : parts.prototypes())
            for (int l = 2; l < prototype.lods().size(); l++)
                fine += prototype.lods().get(l).faces() * 4L * stride;
        for (var prototype : parts.prototypes())
            for (int l = 0; l < 2; l++)
                staticBytes += prototype.lods().get(l).faces() * 4L * stride;
        long admit = BUDGET - sharedBytes - atlasPlanned + ARENA_GROW;
        arenaBytes = (Math.max(staticBytes, Math.min(admit, staticBytes + fine))
                        + stride - 1) / stride * stride;
        arena =
                RenderSystem.getDevice()
                        .createBuffer(
                                () -> "Scene part arena",
                                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                                (int) arenaBytes);
        if (arenaBytes > staticBytes) arenaFree.put(staticBytes, arenaBytes - staticBytes);
        long prefix = 0;
        for (int p = 0; p < parts.prototypes().size(); p++) {
            var lods = parts.prototypes().get(p).lods();
            for (int l = 0; l < 2; l++) {
                int prototype = p, level = l;
                Mesh mesh =
                        meshes.computeIfAbsent(
                                p * 4 + l, ignored -> new Mesh(prototype, level));
                mesh.rangeBytes = Math.toIntExact(lods.get(l).faces() * 4L * stride);
                mesh.base = (int) (prefix / stride);
                prefix += mesh.rangeBytes;
                meshBytes += mesh.rangeBytes;
            }
        }
        dynamicBudget = Math.max(0, arenaBytes - staticBytes - DYN_MARGIN);
        // 静态层只在此建立元数据并预留前缀区间；解码任务走统一派发入口，
        // 与动态几何、图集共享任务槽，兜底层最终就绪但不越过调度上限。
        storage = GL43.glGenBuffers();
    }

    private static Matrix4f affine(float[] a) {
        return new Matrix4f(
                a[0], a[4], a[8], 0, a[1], a[5], a[9], 0, a[2], a[6], a[10], 0, a[3], a[7], a[11],
                1);
    }

    private GpuBuffer createIndices() {
        // 前半 CCW、后半 CW；两半的索引值各自从 0 计，第二半以元素偏移寻址——
        // MDI 命令与逐 draw 回退共用同一套 firstIndex/baseVertex 语义。
        ByteBuffer data = MemoryUtil.memAlloc(INDEX_QUADS * 2 * 6 * 4);
        try {
            for (int half = 0; half < 2; half++)
                for (int q = 0; q < INDEX_QUADS; q++) {
                    int v = q * 4;
                    boolean reverse = half == 1;
                    data.putInt(v)
                            .putInt(v + (reverse ? 2 : 1))
                            .putInt(v + (reverse ? 1 : 2))
                            .putInt(v)
                            .putInt(v + (reverse ? 3 : 2))
                            .putInt(v + (reverse ? 2 : 3));
                }
            data.flip();
            return RenderSystem.getDevice()
                    .createBuffer(() -> "Scene instance quad indices", GpuBuffer.USAGE_INDEX, data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    long advance(Vec3 camera, Matrix4f projection, Frustum frustum, boolean culling)
            throws IOException {
        long start = System.nanoTime();
        try {
            return advanceImpl(camera, projection, frustum, culling);
        } finally {
            advanceMs = advanceMs * .95 + (System.nanoTime() - start) / 1e6 * .05;
        }
    }

    private long advanceImpl(Vec3 camera, Matrix4f projection, Frustum frustum, boolean culling)
            throws IOException {
        if (closed || !loading.isDone()) return 0;
        if (parts == null) initialize();
        long now = System.nanoTime();
        collect();
        evicted = 0;
        boolean[] wanted = new boolean[parts.prototypes().size() * 4];
        double[] demand = new double[parts.prototypes().size() * 4];
        double pixelScale =
                Math.abs(projection.m11()) * Minecraft.getInstance().getWindow().getHeight() * .5;
        for (Placement p : placements) {
            wanted[p.prototype * 4] = true;
            double distance = Math.sqrt(p.bounds.distanceToSqr(camera));
            var lods = parts.prototypes().get(p.prototype).lods();
            double pixels = p.scale * pixelScale / Math.max(.05, distance);
            int level = p.level;
            while (level < lods.size() - 1 && lods.get(level).error() * pixels > 5) level++;
            while (level > 0 && lods.get(level - 1).error() * pixels < 3) level--;
            p.level = level;
            if (!culling || frustum.isVisible(p.bounds)) {
                int key = p.prototype * 4 + level;
                wanted[key] = true;
                double want = lods.get(level).error() * pixels * parts.priority(p.prototype);
                if (want > demand[key]) demand[key] = want;
            }
        }
        int stride = SceneTerrain.instances().getVertexFormat().getVertexSize();
        evictCandidates = null;
        arenaWanted = 0;
        // 静态层始终保有加载需求，与相机可见性无关；绕过视锥的往返不出现兜底缺失。
        for (int p = 0; p < parts.prototypes().size(); p++)
            for (int l = 0; l < 2 && l < parts.prototypes().get(p).lods().size(); l++)
                wanted[p * 4 + l] = true;
        for (var entry : meshes.entrySet())
            if (!wanted[entry.getKey()]) {
                Mesh m = entry.getValue();
                m.wantedAt = 0;
                // 脱离需求的已解码数据归还 CPU 缓存，释放读取准入额度；
                // 未完成上传的预留区间一并归还，base 置 -1 保证只归还一次。
                if (m.data != null) {
                    preparedBytes -= m.data.bytes();
                    retainDecoded(entry.getKey(), m.data);
                    m.data = null;
                }
                if (m.base >= 0 && !m.uploaded) releaseReservation(m, stride);
            }
        // 粗级别常驻，覆盖主视野外的投影；细节只对可见实例提出请求。
        for (int level = 0; level < 4; level++)
            for (int p = 0; p < parts.prototypes().size(); p++) {
                int key = p * 4 + level;
                if (!wanted[key]) continue;
                Mesh mesh = meshes.computeIfAbsent(key, ignored -> new Mesh(key / 4, key % 4));
                mesh.usedAt = now;
                if (mesh.base < 0 && mesh.wantedAt == 0) mesh.wantedAt = now;
            }
        dispatch(wanted, demand, now);
        return upload(demand, now);
    }

    // 统一任务派发：先派发已解码网格引用的图集（与可见性和几何背压解耦），
    // 再按"静态最粗 → 静态第二档 → 精细需求分数"的顺序准入。每个候选先检查
    // 任务槽与待上传条件，再尝试动态预算与连续区间预留；预留成功才移交缓存
    // 或提交读取，装不下的请求只保留元数据并继续检查后面的可行请求。
    private void dispatch(boolean[] wanted, double[] demand, long now) {
        int stride = SceneTerrain.instances().getVertexFormat().getVertexSize();
        cancelWindow(wanted);
        advanceWindow(wanted, now);
        for (Mesh mesh : meshes.values())
            if (mesh.quads != null)
                for (int a = 0; a < mesh.quads.length; a++)
                    if (mesh.quads[a] > 0) tryDispatchAtlas(a);
        record Wait(Mesh mesh, double score, int gpuBytes) {}
        var waiting = new ArrayList<Wait>();
        for (int p = 0; p < parts.prototypes().size(); p++) {
            var lods = parts.prototypes().get(p).lods();
            for (int l = 0; l < lods.size(); l++) {
                Mesh mesh = meshes.get(p * 4 + l);
                if (mesh == null || mesh.uploaded || mesh.data != null || mesh.failed) continue;
                if (l >= 2 && mesh.wantedAt == 0) continue;
                if (hasJob(mesh)) continue;
                double score =
                        l < 2
                                ? 0
                                : demand[p * 4 + l]
                                        * (1.0 + (now - mesh.wantedAt) / (double) AGE_DOUBLE);
                waiting.add(
                        new Wait(
                                mesh,
                                score,
                                Math.toIntExact(lods.get(l).faces() * 4L * stride)));
            }
        }
        waiting.sort(
                java.util.Comparator.comparingInt((Wait w) -> Math.min(w.mesh().level, 2))
                        .thenComparing(w -> -w.score()));
        long budget = dynamicBudget;
        for (Mesh mesh : meshes.values())
            if (mesh.level >= 2 && mesh.base >= 0) budget -= mesh.rangeBytes;
        for (Wait w : waiting) {
            Mesh mesh = w.mesh();
            int key = mesh.prototype * 4 + mesh.level;
            SceneParts.Mesh cached = decoded.get(key);
            if (cached == null && (jobs.size() >= 2 || preparedBytes >= (64L << 20)))
                continue; // 无任务槽或几何背压：只保留元数据
            if (mesh.level >= 2 && w.gpuBytes() > budget) {
                budget += fund(wanted, now, w.gpuBytes() - budget);
                if (w.gpuBytes() > budget) continue;
            }
            if (mesh.base < 0) {
                long at = arenaAlloc(w.gpuBytes());
                if (at < 0 && windowTarget == mesh && claimWindow(w.gpuBytes()) >= 0) at = windowLo;
                if (at < 0) {
                    arenaWanted = Math.max(arenaWanted, w.gpuBytes());
                    if (windowTarget == null) planWindow(wanted, now, mesh, w.gpuBytes());
                    continue;
                }
                mesh.base = (int) (at / stride);
                mesh.rangeBytes = w.gpuBytes();
                mesh.cursor = 0;
                mesh.uploaded = false;
                meshBytes += w.gpuBytes();
                if (windowTarget == mesh) dropWindow();
                if (mesh.level >= 2) budget -= w.gpuBytes();
            }
            if (cached != null) {
                decoded.remove(key);
                decodedBytes -= cached.bytes();
                // 缓存移交立即占用待上传额度，同轮后续冷读取的背压使用更新值。
                preparedBytes += cached.bytes();
                mesh.data = cached;
                mesh.offsets = cached.offsets();
                mesh.quads = cached.quads();
            } else {
                jobs.add(
                        new Job(
                                mesh,
                                -1,
                                CompletableFuture.supplyAsync(
                                        () -> {
                                            try {
                                                return parts.readMesh(
                                                        mesh.prototype, mesh.level);
                                            } catch (IOException failure) {
                                                throw new CompletionException(failure);
                                            }
                                        },
                                        worker)));
            }
        }
    }

    private boolean hasJob(Mesh mesh) {
        for (Job job : jobs) if (job.mesh == mesh) return true;
        return false;
    }

    private void tryDispatchAtlas(int index) {
        Atlas atlas = atlases[index];
        if (jobs.size() >= 2 || atlas.texture != null || atlas.loading || atlas.failed) return;
        atlas.loading = true;
        jobs.add(
                new Job(
                        null,
                        index,
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        // 实时查询光影状态：Iris 开关触发的重载早期，
                                        // 捕获值可能滞后，会导致关→开后的部件 PBR 图集缺失。
                                        return parts.readAtlas(index, SceneTerrain.shaderPackInUse());
                                    } catch (IOException failure) {
                                        throw new CompletionException(failure);
                                    }
                                },
                                worker)));
    }

    private void collect() throws IOException {
        for (int i = jobs.size() - 1; i >= 0; i--) {
            Job job = jobs.get(i);
            if (!job.future.isDone()) continue;
            jobs.remove(i);
            try {
                Object result = job.future.join();
                if (job.mesh != null) {
                    SceneParts.Mesh data = (SceneParts.Mesh) result;
                    Mesh mesh = job.mesh;
                    if (mesh.base < 0) {
                        // 请求已失效（预留已归还）：结果退回有界缓存，不发布给网格。
                        retainDecoded(mesh.prototype * 4 + mesh.level, data);
                    } else {
                        mesh.data = data;
                        mesh.offsets = data.offsets();
                        mesh.quads = data.quads();
                        preparedBytes += data.bytes();
                    }
                } else {
                    var metadata = parts.atlases().get(job.atlas);
                    Atlas atlas = atlases[job.atlas];
                    atlas.location =
                            ResourceLocation.fromNamespaceAndPath(
                                    "minetale", "scene/parts/" + id + "/" + job.atlas);
                    atlas.texture =
                            new SceneTexture(
                                    atlas.location,
                                    (SceneImages) result,
                                    metadata.width(),
                                    metadata.height());
                    Minecraft.getInstance()
                            .getTextureManager()
                            .register(atlas.location, atlas.texture);
                    atlas.type =
                            RenderType.create(
                                    "minetale_scene_instances",
                                    1536,
                                    SceneTerrain.instances(),
                                    RenderType.CompositeState.builder()
                                            .setLightmapState(RenderStateShard.LIGHTMAP)
                                            .setTextureState(
                                                    new RenderStateShard.TextureStateShard(
                                                            atlas.location, false))
                                            .createCompositeState(false));
                    atlasBytes += atlas.texture.bytes();
                    atlas.loading = false;
                }
            } catch (CompletionException failure) {
                if (job.mesh != null) {
                    job.mesh.failed = true;
                    releaseReservation(
                            job.mesh,
                            SceneTerrain.instances().getVertexFormat().getVertexSize());
                } else {
                    atlases[job.atlas].failed = true;
                    atlases[job.atlas].loading = false;
                }
                cn.jehorstudio.minetale.MineTale.LOGGER.error(
                        "Scene part load failed", failure.getCause());
            }
        }
    }

    private long upload(double[] demand, long now) throws IOException {
        // 实际传输从这里开始计时：准入规划、筹资与空洞规划不挤占 2 ms 预算，
        // 令牌补充与纹理、顶点传输共用同一起点；前置规划再久也不阻止本轮首块上传。
        long uploadStart = System.nanoTime();
        if (uploadStamp != 0)
            uploadTokens =
                    Math.min(
                            UPLOAD_BURST,
                            uploadTokens
                                    + Math.min(uploadStart - uploadStamp, 1_000_000_000L)
                                            * UPLOAD_RATE
                                            / 1_000_000_000L);
        uploadStamp = uploadStart;
        long deadline = uploadStart + UPLOAD_CPU;
        long granted = uploadTokens;
        long remaining = granted;
        for (Atlas atlas : atlases)
            if (atlas.texture != null && !atlas.texture.uploaded() && remaining > 0) {
                // 纹理逐块转换像素，单块与网格 staging 同量级；长期速率由令牌桶约束。
                remaining -= atlas.texture.upload((int) Math.min(remaining, UPLOAD));
                if (System.nanoTime() >= deadline) break;
            }
        int stride = SceneTerrain.instances().getVertexFormat().getVertexSize();
        // 上传顺序沿用本轮优先序：静态兜底优先，其后按需求×老化降序。
        var pending = new ArrayList<Mesh>();
        for (Mesh mesh : meshes.values())
            if (mesh.data != null && mesh.base >= 0) pending.add(mesh);
        pending.sort(
                java.util.Comparator.comparingInt((Mesh m) -> Math.min(m.level, 2))
                        .thenComparing(
                                m ->
                                        m.level < 2
                                                ? 0d
                                                : -demand[m.prototype * 4 + m.level]
                                                        * (1.0
                                                                + (now - m.wantedAt)
                                                                        / (double) AGE_DOUBLE)));
        for (Mesh mesh : pending) {
            // 纹理与顶点共用同一时间检查；预算耗尽后不再启动下一块，未用令牌保留。
            if (System.nanoTime() - uploadStart >= UPLOAD_CPU || remaining < stride * 4) break;
            int total = mesh.data.vertices().length / 14;
            // 单网格单帧不超过 staging 容量；突发额度跨网格连续消费。
            int count =
                    Math.min(
                            total - mesh.cursor,
                            (int) Math.min(remaining, UPLOAD) / stride / 4 * 4);
            staging.clear();
            SceneTerrain.encode(
                    staging, mesh.data.vertices(), false, stride > 48, mesh.cursor, count);
            staging.flip();
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(
                            arena.slice((mesh.base + mesh.cursor) * stride, count * stride),
                            staging);
            mesh.cursor += count;
            remaining -= count * stride;
            if (mesh.cursor == total) {
                preparedBytes -= mesh.data.bytes();
                retainDecoded(mesh.prototype * 4 + mesh.level, mesh.data);
                mesh.data = null;
                mesh.uploaded = true;
            }
        }
        uploadTokens = remaining;
        return granted - remaining;
    }

    private void retainDecoded(int key, SceneParts.Mesh data) {
        decoded.put(key, data);
        decodedBytes += data.bytes();
        var iterator = decoded.entrySet().iterator();
        while (decodedBytes > DECODED_BUDGET && iterator.hasNext()) {
            decodedBytes -= iterator.next().getValue().bytes();
            iterator.remove();
        }
    }

    // 固定容量、纯 best-fit：容量与碎片都只推迟准入，运行期不搬移数据。
    // 活动预约窗口对普通分配保持独占——包括 fence 归还后重新进入空闲表的部分，
    // 否则等高优先级请求可以取走窗口尾段，使预约目标永远拼不齐。跨越窗口的
    // 空闲段按窗口外的左右两段分别参与匹配；预约目标只经 claimWindow 取得。
    private long arenaAlloc(long bytes) {
        boolean reserved = windowTarget != null;
        Part best = null;
        for (var entry : arenaFree.entrySet()) {
            long lo = entry.getKey(), hi = lo + entry.getValue();
            if (reserved && lo < windowHi && hi > windowLo) {
                if (lo < windowLo) best = better(best, new Part(lo, lo, windowLo - lo), bytes);
                if (hi > windowHi) best = better(best, new Part(lo, windowHi, hi - windowHi), bytes);
            } else best = better(best, new Part(lo, lo, hi - lo), bytes);
            if (best != null && best.size() == bytes) break;
        }
        if (best == null) return -1;
        long at = best.at(), end = at + bytes, ownerEnd = best.owner() + arenaFree.remove(best.owner());
        if (best.owner() < at) arenaFree.put(best.owner(), at - best.owner());
        if (end < ownerEnd) arenaFree.put(end, ownerEnd - end);
        return at;
    }

    private static Part better(Part best, Part candidate, long bytes) {
        return candidate.size() >= bytes && (best == null || candidate.size() < best.size()) ? candidate : best;
    }

    // 退休 fence 之后（渲染线程）归还区间并与相邻空闲合并。
    private void arenaRelease(long offset, long size) {
        var floor = arenaFree.floorEntry(offset);
        if (floor != null && floor.getKey() + floor.getValue() == offset) {
            offset = floor.getKey();
            size += floor.getValue();
            arenaFree.remove(floor.getKey());
        }
        var ceiling = arenaFree.ceilingEntry(offset);
        if (ceiling != null && offset + size == ceiling.getKey()) {
            size += ceiling.getValue();
            arenaFree.remove(ceiling.getKey());
        }
        arenaFree.put(offset, size);
    }

    private boolean ready(Mesh mesh) {
        // base >= 0 只代表区间预留；未完成上传的网格不可绘制。
        if (mesh == null
                || !mesh.uploaded
                || mesh.base < 0
                || mesh.data != null
                || mesh.quads == null) return false;
        for (int a = 0; a < mesh.quads.length; a++)
            if (mesh.quads[a] > 0 && (atlases[a].texture == null || !atlases[a].texture.uploaded()))
                return false;
        return true;
    }

    // 驱逐共享路径：预算口径即时扣除，区间等 fence 完成后归还 arena 并合并。
    // base 置 -1 使重复调用（fund 与窗口推进等多路径）只结算一次。
    private void release(Mesh m, int stride) {
        if (m.base < 0) return;
        meshBytes -= m.rangeBytes;
        long offset = (long) m.base * stride;
        m.base = -1;
        retiredBytes += m.rangeBytes;
        SceneGpu.retire(
                () -> {
                    arenaRelease(offset, m.rangeBytes);
                    retiredBytes -= m.rangeBytes;
                });
        meshes.remove(m.prototype * 4 + m.level);
    }

    private boolean evictable(Mesh m, boolean wanted, long now) {
        // 静态层永不驱逐；预留未完成（无数据或上传中）的区间没有可回收的安全点，
        // 只有完整上传后的网格才具备淘汰资格。
        return m.level >= 2 && !wanted && m.base >= 0 && m.data == null && m.uploaded
                && now - m.usedAt >= COOLDOWN;
    }

    // 一轮准入只建立一次驱逐候选：按 usedAt 排序后由各请求顺序消费；
    // 候选耗尽或达到每帧驱逐上限即停止，不为后续失败请求重复复制排序目录。
    private long fund(boolean[] wanted, long now, long need) {
        if (evictCandidates == null) {
            evictCandidates = new ArrayList<>(meshes.entrySet());
            evictCandidates.sort(java.util.Comparator.comparingLong(e -> e.getValue().usedAt));
            evictCursor = 0;
        }
        int stride = SceneTerrain.instances().getVertexFormat().getVertexSize();
        long freed = 0;
        while (freed < need && evicted < EVICT_FRAME && evictCursor < evictCandidates.size()) {
            var entry = evictCandidates.get(evictCursor++);
            Mesh m = entry.getValue();
            // 窗口预约的成员由窗口推进统一释放，fund 不重复消耗额度。
            if (!windowPending.isEmpty() && windowPending.contains(m)) continue;
            if (!evictable(m, wanted[entry.getKey()], now)) continue;
            release(m, stride);
            freed += m.rangeBytes;
            evicted++;
        }
        return freed;
    }

    // 归还预留区间：未写入的部分直接合并回空闲表，已写入的走 fence 退休；
    // base 置 -1 保证失败、请求失效、卸载多重触发时只归还一次。
    private void releaseReservation(Mesh mesh, int stride) {
        if (mesh.base < 0) return;
        long offset = (long) mesh.base * stride;
        long size = mesh.rangeBytes;
        boolean written = mesh.cursor != 0;
        mesh.base = -1;
        mesh.cursor = 0;
        mesh.uploaded = false;
        meshBytes -= size;
        if (written) {
            retiredBytes += size;
            SceneGpu.retire(
                    () -> {
                        arenaRelease(offset, size);
                        retiredBytes -= size;
                    });
        } else arenaRelease(offset, size);
    }

    private void dropWindow() {
        for (long[] seg : windowHeld) arenaRelease(seg[0], seg[1]);
        windowHeld.clear();
        windowPending.clear();
        windowTarget = null;
    }

    // 目标失效（读取失败或脱离需求）时取消空洞预约，空闲部分原样归还。
    private void cancelWindow(boolean[] wanted) {
        if (windowTarget == null) return;
        Mesh target = windowTarget;
        if (target.failed || !wanted[target.prototype * 4 + target.level]) dropWindow();
    }

    // 预约窗口的空闲部分先归还空闲表，与 fence 完成后的驱逐段合并；整窗覆盖时
    // 目标优先取得窗口前段，余量退回空闲表。fence 未完成则重新扣住窗口继续等待。
    private long claimWindow(long bytes) {
        for (long[] seg : windowHeld) arenaRelease(seg[0], seg[1]);
        windowHeld.clear();
        var cover = arenaFree.floorEntry(windowLo);
        long coverEnd = cover == null ? 0 : cover.getKey() + cover.getValue();
        if (cover == null || cover.getKey() > windowLo || coverEnd < windowHi) {
            carveWindow();
            return -1;
        }
        arenaFree.remove(cover.getKey());
        if (cover.getKey() < windowLo) arenaFree.put(cover.getKey(), windowLo - cover.getKey());
        if (windowHi < coverEnd) arenaFree.put(windowHi, coverEnd - windowHi);
        if (windowLo + bytes < windowHi)
            arenaFree.put(windowLo + bytes, windowHi - windowLo - bytes);
        windowPending.clear();
        windowTarget = null;
        return windowLo;
    }

    // 重新扣住 [windowLo, windowHi) 内的空闲段，防止其他分配把预约窗口切碎。
    private void carveWindow() {
        windowHeld.clear();
        List<long[]> taken = new ArrayList<>();
        for (var entry : arenaFree.entrySet()) {
            long lo = entry.getKey(), hi = lo + entry.getValue();
            if (hi <= windowLo || lo >= windowHi) continue;
            taken.add(new long[] {lo, hi});
        }
        for (long[] seg : taken) {
            arenaFree.remove(seg[0]);
            long lo = seg[0], hi = seg[1];
            if (lo < windowLo) arenaFree.put(lo, windowLo - lo);
            if (hi > windowHi) arenaFree.put(windowHi, hi - windowHi);
            windowHeld.add(
                    new long[] {
                        Math.max(lo, windowLo), Math.min(hi, windowHi) - Math.max(lo, windowLo)
                    });
        }
    }

    // 空洞规划：把当前空闲段与可驱逐冷网格按地址组成有序区间（静态层、受保护
    // 与正在使用的区间作为边界），滑动窗口挑选能容纳目标且驱逐字节最少的连续
    // 窗口，不局限于最大空洞。可行性判断只看完整窗口，不受本帧驱逐额度约束：
    // 空闲部分扣住防切碎，待驱逐对象记入 windowPending，由每帧额度分帧释放成形。
    // 没有可行窗口时保留请求元数据，等待条件变化重试，不做没有收益的驱逐。
    private void planWindow(boolean[] wanted, long now, Mesh target, long need) {
        int stride = SceneTerrain.instances().getVertexFormat().getVertexSize();
        record Seg(long lo, long hi, Mesh mesh) {}
        var segments = new ArrayList<Seg>();
        for (var entry : arenaFree.entrySet())
            segments.add(new Seg(entry.getKey(), entry.getKey() + entry.getValue(), null));
        for (var entry : meshes.entrySet()) {
            Mesh m = entry.getValue();
            if (evictable(m, wanted[entry.getKey()], now))
                segments.add(
                        new Seg(
                                (long) m.base * stride,
                                (long) m.base * stride + m.rangeBytes,
                                m));
        }
        segments.sort(java.util.Comparator.comparingLong(Seg::lo));
        int bestLo = -1, bestHi = -1;
        long bestEvict = Long.MAX_VALUE;
        int left = 0;
        long total = 0, evictBytes = 0;
        for (int right = 0; right < segments.size(); right++) {
            Seg seg = segments.get(right);
            if (right > left && seg.lo() != segments.get(right - 1).hi()) {
                left = right;
                total = evictBytes = 0;
            }
            total += seg.hi() - seg.lo();
            if (seg.mesh() != null) evictBytes += seg.hi() - seg.lo();
            while (left < right) {
                Seg drop = segments.get(left);
                long size = drop.hi() - drop.lo();
                if (total - size < need) break;
                total -= size;
                if (drop.mesh() != null) evictBytes -= size;
                left++;
            }
            if (total >= need && evictBytes < bestEvict) {
                bestEvict = evictBytes;
                bestLo = left;
                bestHi = right;
            }
        }
        if (bestLo < 0) return;
        windowTarget = target;
        windowLo = segments.get(bestLo).lo();
        windowHi = segments.get(bestHi).hi();
        windowHeld.clear();
        windowPending.clear();
        for (int i = bestLo; i <= bestHi; i++) {
            Seg seg = segments.get(i);
            if (seg.mesh() == null) {
                windowHeld.add(new long[] {seg.lo(), seg.hi() - seg.lo()});
                arenaFree.remove(seg.lo());
            } else windowPending.add(seg.mesh());
        }
        advanceWindow(wanted, now);
    }

    // 预约窗口跨帧推进：完整窗口一次选定，每帧额度只限制驱逐执行的节奏。
    // 继续前重检尚未驱逐对象的资格，任一仍驻留的对象变为受保护即取消预约并
    // 归还已扣住的空闲部分；已被其他路径（如 fund）释放的成员直接跳过。
    private void advanceWindow(boolean[] wanted, long now) {
        if (windowTarget == null || windowPending.isEmpty()) return;
        int stride = SceneTerrain.instances().getVertexFormat().getVertexSize();
        var iterator = windowPending.iterator();
        while (iterator.hasNext()) {
            Mesh m = iterator.next();
            if (m.base < 0) {
                iterator.remove();
                continue;
            }
            if (!evictable(m, wanted[m.prototype * 4 + m.level], now)) {
                dropWindow();
                return;
            }
            if (evicted >= EVICT_FRAME) return;
            release(m, stride);
            iterator.remove();
            evicted++;
        }
    }

    int draw(Matrix4f view, Vec3 camera, Vec3 origin, Frustum frustum, boolean culling) {
        long start = System.nanoTime();
        try {
            return drawImpl(view, camera, origin, frustum, culling);
        } finally {
            drawMs = drawMs * .95 + (System.nanoTime() - start) / 1e6 * .05;
        }
    }

    private int drawImpl(Matrix4f view, Vec3 camera, Vec3 origin, Frustum frustum, boolean culling) {
        if (parts == null || closed) return 0;
        batches.clear();
        Batch[] grouped = new Batch[parts.prototypes().size() * 8];
        triangles = draws = passes = visible = 0;
        for (Placement p : placements) {
            if (culling && !frustum.isVisible(p.bounds)) continue;
            Mesh chosen = null;
            for (int level = p.level; level >= 0; level--) {
                Mesh candidate = meshes.get(p.prototype * 4 + level);
                if (ready(candidate)) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) continue;
            chosen.usedAt = System.nanoTime();
            int key = (p.prototype * 4 + chosen.level) * 2 + (p.mirrored ? 1 : 0);
            Batch batch = grouped[key];
            if (batch == null) {
                batch = new Batch(chosen, p.mirrored, new ArrayList<>());
                grouped[key] = batch;
                batches.add(batch);
            }
            batch.placements.add(p);
            visible++;
        }
        if (visible == 0) return 0;
        int bytes = Math.multiplyExact(visible, 112);
        ByteBuffer matrices = MemoryUtil.memAlloc(bytes);
        int previous = GL43.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            int base = 0;
            for (Batch batch : batches) {
                batch.firstInstance = base;
                base += batch.placements.size();
                for (Placement p : batch.placements) for (float v : p.rows) matrices.putFloat(v);
            }
            matrices.flip();
            GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, storage);
            // orphan 避免覆盖上一帧尚未消费的实例矩阵。
            GL43.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, matrices, GL43.GL_STREAM_DRAW);
            storageBytes = bytes;
        } finally {
            GL43.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previous);
            MemoryUtil.memFree(matrices);
        }
        Vector3f offset =
                new Vector3f(
                        (float) (origin.x - camera.x),
                        (float) (origin.y - camera.y),
                        (float) (origin.z - camera.z));
        var transforms =
                RenderSystem.getDynamicUniforms()
                        .writeTransform(view, new Vector4f(1), offset, new Matrix4f(), 1);
        // 换图集才需要换渲染目标与采样器；网格共用 arena 顶点缓冲与两半索引缓冲，
        // 镜像经元素偏移寻址，实例基址仍由每次 draw 的 setup 更新。
        try (var scope = SceneInstanceShader.begin(storage, 0)) {
            for (int a = 0; a < atlases.length; a++) {
                boolean drawn = false;
                for (Batch batch : batches)
                    if (batch.mesh.quads[a] > 0) {
                        drawn = true;
                        break;
                    }
                if (!drawn) continue;
                Atlas atlas = atlases[a];
                atlas.type.setupRenderState();
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
                    try (var pass =
                            RenderSystem.getDevice()
                                    .createCommandEncoder()
                                    .createRenderPass(
                                            () -> "Scene instanced parts",
                                            color,
                                            OptionalInt.empty(),
                                            depth,
                                            OptionalDouble.empty())) {
                        passes++;
                        pass.setPipeline(SceneTerrain.instances());
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("DynamicTransforms", transforms);
                        pass.bindSampler("Sampler0", atlas.texture.getTextureView());
                        pass.bindSampler("SceneEmission", atlas.texture.emissionView());
                        if (RenderSystem.getShaderTexture(2) != null)
                            pass.bindSampler("Sampler2", RenderSystem.getShaderTexture(2));
                        pass.setVertexBuffer(0, arena);
                        pass.setIndexBuffer(indices, VertexFormat.IndexType.INT);
                        for (Batch batch : batches) {
                            Mesh mesh = batch.mesh;
                            if (mesh.quads[a] == 0) continue;
                            scope.base = batch.firstInstance;
                            int mirror = batch.mirrored ? INDEX_QUADS * 6 : 0;
                            for (int q = 0; q < mesh.quads[a]; q += INDEX_QUADS) {
                                int count = Math.min(INDEX_QUADS, mesh.quads[a] - q);
                                pass.drawIndexed(
                                        mesh.base + mesh.offsets[a] + q * 4,
                                        mirror,
                                        count * 6,
                                        batch.placements.size());
                                draws++;
                                triangles += count * 2 * batch.placements.size();
                            }
                        }
                    }
                } finally {
                    atlas.type.clearRenderState();
                }
            }
        }
        return draws;
    }

    String stats() {
        return "parts="
                + visible
                + " partPasses="
                + passes
                + " partDraws="
                + draws
                + " partTriangles="
                + triangles
                + " partGpuMiB="
                + ((atlasBytes + arenaBytes + sharedBytes + storageBytes) / (1 << 20))
                + " partAtlasMiB="
                + (atlasBytes >> 20)
                + " partMeshMiB="
                + (meshBytes >> 20)
                + " partArenaMiB="
                + (arenaBytes >> 20)
                + " partRetiredMiB="
                + (retiredBytes >> 20)
                + " partDecodedMiB="
                + (decodedBytes >> 20)
                + " partPreparedMiB="
                + (preparedBytes >> 20)
                + " partJobs="
                + jobs.size()
                + " partAdvanceMs="
                + String.format(java.util.Locale.ROOT, "%.2f", advanceMs)
                + " partDrawMs="
                + String.format(java.util.Locale.ROOT, "%.2f", drawMs)
                + (attachments == null ? "" : attachments.stats());
    }

    CompletableFuture<?> pending() {
        return CompletableFuture.allOf(
                java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(loading), jobs.stream().map(Job::future))
                        .toArray(CompletableFuture[]::new));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        decoded.clear();
        decodedBytes = 0;
        preparedBytes = 0;
        evictCandidates = null;
        dropWindow();
        arenaFree.clear();
        SceneGpu.retire(
                () -> {
                    if (arena != null) arena.close();
                    if (atlases != null)
                        for (Atlas atlas : atlases)
                            if (atlas.texture != null)
                                Minecraft.getInstance().getTextureManager().release(atlas.location);
                    if (indices != null) indices.close();
                    if (storage != 0) GL43.glDeleteBuffers(storage);
                });
    }
}
