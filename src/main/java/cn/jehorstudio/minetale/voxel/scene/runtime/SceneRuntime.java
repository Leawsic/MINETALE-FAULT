package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneImages;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneBase;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneIndex;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneLayout;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneSeams;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneSurface;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels;

import com.mojang.blaze3d.buffers.GpuBuffer;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

// 页负责 IO 和粗剔除，砖负责 High 生成与覆盖；远处闲置成果分帧回收。
final class SceneRuntime implements AutoCloseable {
    private SceneVoxels.Precision precision =
            new SceneVoxels.Precision(
                    SceneVoxels.GEOMETRY_RESOLUTION, SceneVoxels.COLOR_RESOLUTION,
                    SceneVoxels.PBR_RESOLUTION, SceneVoxels.NORMAL_RESOLUTION);
    private final Map<SceneLayout.Cell, SceneVoxels.Precision> precisionOverrides = new HashMap<>();
    private long nextPlanAt;
    private boolean planDirty;

    private static final double ENTER = 96, EXIT = 120;
    private static final double MAX_FINE_ENTER = 256;

    private double highRange(SceneLayout.Cell cell, boolean leaving) {
        Slot slot = bricks.get(cell);
        float geometry = Math.min(precisionOverrides.getOrDefault(cell, precision).geometry(), slot == null ? 8 : slot.maxGeometryResolution);
        // 纹理保持独立精度，范围保守地按平方根扩展并封顶
        double enter = Math.min(MAX_FINE_ENTER, ENTER * Math.sqrt(8 / geometry));
        return leaving ? enter * EXIT / ENTER : enter;
    }

    private boolean withinHighRange(SceneLayout.Cell cell, boolean wanted, double distanceSquared) {
        double range = highRange(cell, wanted);
        return distanceSquared <= range * range;
    }
    final SceneAsset asset;
    private final Executor worker;
    private final Parent[] parents;
    private final Map<SceneLayout.Cell, Slot> bricks = new HashMap<>();
    private final List<Slot> requested = new ArrayList<>(),
            residents = new ArrayList<>(),
            jobs = new ArrayList<>();
    private final int brickSize, lowTriangles;
    private List<SceneLayout.Brick> lowData;
    private SceneImages textures;
    private SceneGpu gpu;
    private SceneFixed fixed;
    private SceneInstances instances;
    private volatile SceneSurface surface;
    private final Lane[] lanes = new Lane[WORKERS];
    static final int WORKERS = Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
    private static final java.util.concurrent.Semaphore PERMITS = new java.util.concurrent.Semaphore(WORKERS);
    private final boolean shaderPack;
    private final int lowVertexCount;
    private int[] lowIndices;
    private final Map<Integer, Integer> lowIndexOffsets = new HashMap<>();
    private final SceneSeams seams;
    private final SceneSeamCache seamCache;
    private final SceneIndex staticIndex;
    private final List<Parent> visibleParents = new ArrayList<>();
    private Vec3 spatialCamera;
    private final Matrix4f spatialView = new Matrix4f(), spatialProjection = new Matrix4f();
    private boolean spatialCulling;
    private int indexedActive, reclaimCursor;
    private final List<SceneGpu.Draw> frameLows = new ArrayList<>(), frameRaws = new ArrayList<>();
    private final List<SceneGpu.Fine> frameFines = new ArrayList<>();
    private long visibilityQueries;
    private boolean fineMode, fineAvailable, greedy = true;
    private volatile boolean closed;
    private Mode plannedMode;
    private volatile long geometryGeneration;
    private int lowCursor,
            lowVertexCursor,
            failedPages,
            pending,
            resident,
            active,
            visible,
            triangles,
            draws;
    private long residentBytes, uploadedTotal, switches, evictions, discarded;
    private final long[] frameTimes = new long[600], cpuTimes = new long[600];
    private final long[] generationTimes = new long[600];
    private int generationSamples;
    private int samples;
    private long lastFrame, uploadedFrame;

    SceneRuntime(
            SceneAsset asset,
            SceneLayout layout,
            SceneImages textures,
            SceneSeams seams,
            SceneIndex staticIndex,
            SceneSurface surface,
            Executor worker, boolean shaderPack) {
        this.asset = asset;
        this.worker = worker;
        this.shaderPack = shaderPack;
        if (asset.partsAvailable()) instances = new SceneInstances(asset, layout, worker, shaderPack);
        lowVertexCount = layout.vertexCount;
        lowIndices = layout.indices;
        if (lowIndices != null) for (var b : layout.uploads) lowIndexOffsets.put(b.offset(), b.firstIndex());
        for (int i = 0; i < lanes.length; i++) lanes[i] = new Lane();
        this.textures = textures;
        this.seams = seams;
        this.staticIndex = staticIndex;
        this.surface = surface;
        brickSize = layout.size;
        var seamDirectory = SceneConfig.seamCacheDirectory(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath());
        // 导入资产的静态截面已有磁盘表示；生成的 Low 按实际几何和 UV 复用截面。
        seamCache = seams != null && (asset.generatedLod() || !asset.staticIndexAvailable())
                ? new SceneSeamCache(seamDirectory == null ? null
                        : seamDirectory.resolve("v1-" + seams.cacheKey(brickSize)))
                : null;
        lowData = new ArrayList<>(layout.uploads);
        lowTriangles = layout.triangles;
        var runtimePages = staticIndex == null ? asset.pages() : Arrays.asList(staticIndex.pages());
        parents = new Parent[runtimePages.size()];
        for (var page : runtimePages) {
            var bounds =
                    bounds(
                            new SceneLayout.Cell(
                                    page.coordinate(0), page.coordinate(1), page.coordinate(2)),
                            asset.pageSize());
            parents[page.id()] =
                    new Parent(
                            page,
                            bounds,
                            page.id() < layout.parentOffsets.length
                                    ? layout.parentOffsets[page.id()]
                                    : 0,
                            page.id() < layout.parentTriangles.length
                                    ? layout.parentTriangles[page.id()]
                                    : 0);
        }
        if (staticIndex != null) {
            for (var brick : staticIndex.bricks()) {
                Parent parent = parents[brick.parent()];
                Slot slot =
                        new Slot(
                                parent,
                                brick.cell(),
                                bounds(brick.cell(), brickSize),
                                brick.lowOffset(),
                                brick.lowTriangles());
                var limit = surface.precisionLimit(brick.sources());
                slot.maxGeometryResolution = limit.geometry();
                slot.maxMaterialResolution = limit.color();
                bricks.put(brick.cell(), slot);
                linkNeighbors(slot);
                parent.bricks.add(slot);
            }
            return;
        }
        for (var brick : layout.bricks) {
            Parent parent = parents[brick.parent().id()];
            Slot slot =
                    new Slot(
                            parent,
                            brick.cell(),
                            bounds(brick.cell(), brickSize),
                            brick.offset(),
                            brick.triangles());
            if (bricks.put(brick.cell(), slot) != null)
                throw new IllegalArgumentException("重复的 Low 砖");
            linkNeighbors(slot);
            parent.bricks.add(slot);
        }
    }

    private void linkNeighbors(Slot slot) {
        for (int d = 0; d < 6; d++) {
            Slot adjacent = bricks.get(slot.cell.neighbor(d));
            slot.neighbors[d] = adjacent;
            if (adjacent != null) adjacent.neighbors[d ^ 1] = slot;
        }
    }

    private static AABB bounds(SceneLayout.Cell cell, int size) {
        double x = (double) cell.x() * size,
                y = (double) cell.y() * size,
                z = (double) cell.z() * size;
        return new AABB(x, y, z, x + size, y + size, z + size);
    }

    void render(
            RenderLevelStageEvent.AfterOpaqueBlocks event,
            Matrix4f projection,
            Vec3 origin,
            Mode mode,
            boolean culling)
            throws IOException {
        if (closed) return;
        if (asset.staticIndexAvailable() && staticIndex == null) mode = Mode.LOW;
        if (mode != Mode.RAW
                && precision.equals(SceneBase.PRECISION)
                && precisionOverrides.isEmpty()) mode = Mode.LOW;
        long start = System.nanoTime();
        uploadedFrame = 0;
        if (gpu == null) {
            gpu = new SceneGpu(textures, asset.atlasWidth(), asset.atlasHeight(), lowVertexCount, lowIndices);
            textures = null;
            lowIndices = null;
        }
        if (fixed == null) fixed = new SceneFixed(asset, worker);
        boolean partsReady = instances == null || instances.prepare();
        if (!gpu.texturesReady()) uploadedFrame += gpu.uploadTextures();
        if (lowData != null && gpu.texturesReady()) uploadLow();
        if (lowData != null || !gpu.texturesReady()) {
            finishFrame(start);
            return;
        }
        boolean requestedFine =
                partsReady && asset.sourceAvailable()
                        && (seams != null || staticIndex != null)
                        && (!precision.equals(SceneBase.PRECISION) || !precisionOverrides.isEmpty())
                        && mode != Mode.RAW
                        && mode != Mode.LOW;
        if (fineMode != requestedFine) {
            clearHigh();
            fineMode = requestedFine;
        }
        boolean becameAvailable = false;
        if (fineMode && !fineAvailable) {
            fineAvailable = gpu.prepareMaterials();
            becameAvailable = fineAvailable;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos.subtract(origin);
        Frustum frustum = new Frustum(event.getModelViewMatrix(), projection);
        frustum.prepare(camera.x, camera.y, camera.z);
        uploadedFrame += fixed.advance(gpu);
        boolean spatialChanged =
                staticIndex != null
                        && (spatialCamera == null
                                || !spatialCamera.equals(camera)
                                || !spatialView.equals(event.getModelViewMatrix())
                                || !spatialProjection.equals(projection)
                                || spatialCulling != culling);
        if (spatialChanged || plannedMode != mode || becameAvailable || staticIndex == null) {
            if (staticIndex != null) {
                updateVisibility(camera, frustum, culling, mode);
                spatialCamera = camera;
                spatialView.set(event.getModelViewMatrix());
                spatialProjection.set(projection);
                spatialCulling = culling;
            }
            planDirty = true;
        }
        // 可见性逐帧更新；异步细节需求按游戏 tick 节奏合并
        // 等待期间完整 Low 保持覆盖；模式和精度变更立即重新规划。
        if (plannedMode != mode || becameAvailable || planDirty && start >= nextPlanAt) {
            plan(camera, frustum, mode, culling);
            plannedMode = mode;
            planDirty = false;
            nextPlanAt = start + 50_000_000L;
        }
        reclaimDistant(camera);
        collect();
        submit();
        draw(event, origin, frustum, culling);
        // 加载次序：先 Core 主体（固定网格烘焙），主体就绪后再上传与放置部件。
        if (instances != null && partsReady && fixed.complete()) {
            var activeAttachments = new HashMap<SceneLayout.Cell,SceneAttachments.Cell>();
            if (fineMode) for (Slot slot : residents) if (slot.wanted && slot.ready && slot.attachment != null)
                activeAttachments.put(slot.cell,slot.attachment);
            instances.attach(activeAttachments);
            uploadedFrame += instances.advance(camera, projection, frustum, culling);
            draws += instances.draw(event.getModelViewMatrix(), event.getLevelRenderState().cameraRenderState.pos, origin, frustum, culling);
        }
        finishFrame(start);
    }

    private void updateVisibility(Vec3 camera, Frustum frustum, boolean culling, Mode mode) {
        visibleParents.clear();
        visibilityQueries++;
        staticIndex.visiblePages(
                camera.x,
                camera.y,
                camera.z,
                (x0, y0, z0, x1, y1, z1) -> frustum.isVisible(new AABB(x0, y0, z0, x1, y1, z1)),
                culling,
                mode != Mode.RAW,
                id -> {
                    Parent parent = parents[id];
                    // 远处仅绘制完整 Low；没有 High 覆盖时，逐砖剔除对绘制和请求都无贡献。
                    if (fineMode && (parent.displayedBricks > 0
                            || distanceSquared(parent.bounds, camera) <= Math.pow(MAX_FINE_ENTER * EXIT / ENTER, 2)))
                        updateVisibleBricks(parent, frustum, culling);
                    visibleParents.add(parent);
                });
    }

    private void updateVisibleBricks(Parent parent, Frustum frustum, boolean culling) {
        parent.visibleBricks.clear();
        for (Slot slot : parent.bricks) {
            boolean visible = !culling || frustum.isVisible(slot.bounds);
            if (visible != slot.visible) {
                slot.visible = visible;
                parent.dirty = true;
            }
            if (visible) parent.visibleBricks.add(slot);
        }
    }

    private void plan(Vec3 camera, Frustum frustum, Mode mode, boolean culling) {
        List<Slot> candidates = candidates(camera, frustum, mode, culling);
        List<Slot> previous = new ArrayList<>(requested);
        requested.clear();
        candidates.sort(Comparator.comparingDouble(s -> s.distance));
        for (Slot slot : candidates) {
            if (slot.failed && !slot.ready) continue;
            if (fineMode) {
                SceneVoxels.Precision target = materialPrecision(slot);
                if (slot.precision != null && !slot.precision.equals(target)) {
                    boolean retain = slot.ready && slot.precision.geometry() == target.geometry();
                    invalidate(slot, retain);
                }
                slot.precision = target;
                if (slot.precision.equals(SceneBase.PRECISION)) continue;
            }
            want(slot, true);
            requested.add(slot);
        }
        var selected = new java.util.HashSet<>(requested);
        for (Slot slot : previous) if (!selected.contains(slot)) want(slot, false);
        if (fineMode) {
            refreshCapRequests();
            return;
        }
    }

    private SceneVoxels.Precision materialPrecision(Slot slot) {
        SceneVoxels.Precision explicit = precisionOverrides.get(slot.cell);
        SceneVoxels.Precision target = explicit == null ? precision : explicit;
        // 几何范围与材质清晰度独立；退出阈值留出滞回
        int band = slot.materialBand;
        if (explicit == null) {
            if (band == 8 && slot.distance > 56 * 56) band = 4;
            if (band == 4 && slot.distance > 112 * 112) band = 2;
            if (band == 2 && slot.distance < 96 * 96) band = 4;
            if (band == 4 && slot.distance < 48 * 48) band = 8;
        } else band = 8;
        slot.materialBand = band;
        float material = Math.min(band, slot.maxMaterialResolution);
        return new SceneVoxels.Precision(Math.min(target.geometry(), slot.maxGeometryResolution),
                Math.min(target.color(), material), Math.min(target.roughness(), material),
                Math.min(target.normal(), material), Math.min(target.metalness(), material));
    }

    private List<Slot> candidates(Vec3 camera, Frustum frustum, Mode mode, boolean culling) {
        List<Slot> candidates = new ArrayList<>();
        for (Parent parent : staticIndex == null ? Arrays.asList(parents) : visibleParents) {
            if (mode == Mode.RAW || (mode == Mode.PAGED && !fineMode && asset.rawAvailable())) {
                Slot slot = parent.raw;
                slot.distance = distanceSquared(slot.bounds, camera);
                if (mode == Mode.RAW
                        || slot.distance <= (slot.wanted ? EXIT * EXIT : ENTER * ENTER))
                    candidates.add(slot);
                continue;
            }
            if (!fineMode
                    || !fineAvailable
                    || distanceSquared(parent.bounds, camera) > Math.pow(MAX_FINE_ENTER * EXIT / ENTER, 2)
                    || culling && !frustum.isVisible(parent.bounds)) continue;
            if (staticIndex != null) {
                for (Slot slot : parent.visibleBricks) {
                    double distance = distanceSquared(slot.bounds, camera);
                    if (withinHighRange(slot.cell, slot.wanted, distance)) {
                        slot.distance = distance;
                        candidates.add(slot);
                    }
                }
                continue;
            }
            int count = asset.pageSize() / brickSize;
            for (int z = 0; z < count; z++)
                for (int y = 0; y < count; y++)
                    for (int x = 0; x < count; x++) {
                        var cell =
                                new SceneLayout.Cell(
                                        parent.page.coordinate(0) * count + x,
                                        parent.page.coordinate(1) * count + y,
                                        parent.page.coordinate(2) * count + z);
                        Slot slot = bricks.get(cell);
                        AABB bounds = slot == null ? bounds(cell, brickSize) : slot.bounds;
                        double distance = distanceSquared(bounds, camera);
                        if (!withinHighRange(cell, slot != null && slot.wanted, distance)
                                || culling && !frustum.isVisible(bounds)) continue;
                        if (slot == null) {
                            // Low 为空只表示当前表示的三角形数为零；邻近空砖同样可请求精细曲面。
                            slot = new Slot(parent, cell, bounds, 0, 0);
                            bricks.put(cell, slot);
                            linkNeighbors(slot);
                            parent.bricks.add(slot);
                        }
                        slot.distance = distance;
                        candidates.add(slot);
                    }
        }
        return candidates;
    }

    private void submit() {
        for (Slot slot : requested) {
            // 完成但尚未上传的成果也占据队列容量
            if (jobs.size() >= WORKERS * 2) break;
            if (!slot.wanted || slot.ready && !slot.refreshing || slot.request != null || slot.failed) continue;
            if (!fineMode && slot.parent.page.raw_triangles() == 0) {
                slot.ready = true;
                refreshSlot(slot);
                continue;
            }
            Lane lane = null;
            for (Lane candidate : lanes) if (!candidate.busy) { lane = candidate; break; }
            if (lane == null || !PERMITS.tryAcquire()) break;
            Lane selectedLane = lane;
            boolean fine = fineMode, merge = greedy;
            long token = geometryGeneration, revision = slot.revision;
            SceneVoxels.Precision selectedPrecision = slot.precision;
            int capMask = fine ? requiredCaps(slot) : 0;
            SceneVoxels.GeometryResult cached = slot.geometry;
            if (cached != null && (cached.resolution() != selectedPrecision.geometry()
                    || slot.geometryGreedy != merge || (slot.geometryCaps & capMask) != capMask)) cached = null;
            SceneVoxels.GeometryResult reusable = cached;
            int generatedCaps = cached == null ? capMask : slot.geometryCaps;
            slot.preparedCaps = generatedCaps;
            slot.generationRecorded = false;
            slot.requestEpoch = token;
            slot.requestRevision = revision;
            slot.lane = lane;
            lane.busy = true;
            try {
                slot.request = CompletableFuture.supplyAsync(() -> {
                    try { return prepare(slot, selectedLane, fine, merge, token, revision,
                            selectedPrecision, generatedCaps, reusable); }
                    finally { PERMITS.release(); }
                }, worker);
            } catch (RuntimeException rejected) {
                lane.busy = false; slot.lane = null; PERMITS.release();
                throw rejected;
            }
            jobs.add(slot);
        }
        pending = jobs.size();
    }

    private Prepared prepare(Slot slot, Lane lane, boolean fine, boolean merge, long token,
            long revision, SceneVoxels.Precision selectedPrecision, int capMask,
            SceneVoxels.GeometryResult reusable) {
        long generationStart = System.nanoTime();
        java.util.function.BooleanSupplier cancelled = () -> closed || token != geometryGeneration
                || revision != slot.revision || !slot.wanted;
        try {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (fine) {
                if (surface == null) synchronized (this) {
                    if (surface == null) surface = SceneSurface.read(asset);
                }
                if (lane.voxels == null) lane.voxels = new SceneVoxels(surface);
                SceneVoxels.GeometryResult geometry = reusable != null ? reusable
                        : lane.voxels.geometry(slot.cell, brickSize, selectedPrecision.geometry(), capMask, merge, cancelled);
                SceneVoxels.Page page = lane.voxels.material(geometry, selectedPrecision, shaderPack, cancelled);
                SceneSeams.Edges edges = seamCache != null
                        ? seamCache.get(slot.cell, staticIndex, seams, brickSize, cancelled)
                        : staticIndex != null ? staticIndex.caps(slot.cell, cancelled)
                        : seams.lowCaps(slot.cell, brickSize, cancelled);
                return new Prepared(null, page, edges, geometry, merge, capMask, System.nanoTime() - generationStart,
                        instances == null ? null : instances.attachments().prepare(slot.cell,geometry));
            }
            return new Prepared(asset.read(slot.parent.page, true), null, null, null, merge, 0,
                    System.nanoTime() - generationStart, null);
        } catch (IOException failure) { throw new CompletionException(failure); }
    }

    private void collect() {
        // 两路 CPU 与有界上传队列重叠；轮转并在成果之间检查软限额。
        if (jobs.size() > 1) java.util.Collections.rotate(jobs, -1);
        long deadline = System.nanoTime() + 1_500_000;
        for (int index = 0; index < jobs.size(); ) {
            if (System.nanoTime() >= deadline || uploadedFrame >= 2 * 1024 * 1024) break;
            Slot slot = jobs.get(index);
            if (!slot.request.isDone()) {
                index++;
                continue;
            }
            // Future 完成后快照独立拥有数据，工作区可立即生成下一砖。
            if (slot.lane != null) {
                slot.lane.busy = false;
                slot.lane = null;
            }
            if (!slot.wanted || slot.requestEpoch != geometryGeneration || slot.requestRevision != slot.revision) {
                if (slot.upload != null) {
                    slot.upload.close();
                    slot.upload = null;
                }
                completeRequest(slot, index);
                discarded++;
                continue;
            }
            Prepared data;
            try {
                data = slot.request.join();
            } catch (CompletionException | java.util.concurrent.CancellationException failure) {
                completeRequest(slot, index);
                if (failure instanceof java.util.concurrent.CancellationException
                        || failure.getCause() instanceof java.util.concurrent.CancellationException)
                    continue;
                slot.failed = true;
                if (!slot.ready) want(slot, false);
                failedPages++;
                MineTale.LOGGER.warn(
                        "Scene brick {} of page {} generation failed; existing representation retained",
                        slot.cell,
                        slot.parent.page.id(),
                        failure);
                continue;
            }
            if (!slot.generationRecorded) {
                slot.geometry = data.geometry;
                slot.geometryGreedy = data.merge;
                slot.geometryCaps = data.capMask;
                generationTimes[generationSamples++ % generationTimes.length] = data.nanos;
                slot.generationRecorded = true;
            }
            long bytes =
                    fineMode
                            ? data.fine.gpuBytes(gpu.stride) + data.edges.gpuBytes(gpu.stride)
                            : (long) data.raw.length / 8 * gpu.stride;
            try {
                if (fineMode) {
                    if (data.fine.vertices().length > 0 || data.edges.vertices().length > 0) {
                        if (slot.upload == null) {
                            slot.upload = gpu.beginFine(data.fine, data.edges);
                        }
                        boolean ready = slot.upload.advance();
                        uploadedFrame += slot.upload.uploaded;
                        if (!ready) {
                            index++;
                            continue;
                        }
                        SceneGpu.Fine replacement = slot.upload.finish();
                        slot.upload = null;
                        if (slot.fine != null) slot.fine.close();
                        slot.fine = replacement;
                    } else {
                        bytes = 0;
                        if (slot.fine != null) slot.fine.close();
                        slot.fine = null;
                    }
                    slot.fineTriangles = data.fine.surfaceTriangles();
                } else {
                    slot.buffer = gpu.upload(data.raw, 0, true);
                    uploadedFrame += bytes;
                }
            } catch (IOException | RuntimeException failure) {
                if (slot.upload != null) {
                    slot.upload.close();
                    slot.upload = null;
                }
                completeRequest(slot, index);
                slot.failed = true;
                if (!slot.ready) want(slot, false);
                failedPages++;
                MineTale.LOGGER.warn(
                        "Scene brick {} material upload failed; existing representation retained", slot.cell, failure);
                continue;
            }
            if (slot.ready) residentBytes -= slot.cost;
            slot.cost = bytes;
            slot.refreshing = false;
            slot.ready = true;
            slot.attachment = data.attachment;
            slot.parent.dirty = true;
            refreshSlot(slot);
            switches++;
            completeRequest(slot, index);
            if (slot.residentIndex < 0) {
                slot.residentIndex = residents.size();
                residents.add(slot);
            }
            residentBytes += bytes;
        }
        pending = jobs.size();
        resident = residents.size();
    }

    private void completeRequest(Slot slot, int index) {
        slot.request = null;
        if (slot.lane != null) slot.lane.busy = false;
        slot.lane = null;
        jobs.remove(index);
    }

    private void draw(
            RenderLevelStageEvent.AfterOpaqueBlocks event,
            Vec3 origin,
            Frustum frustum,
            boolean culling) {
        if (staticIndex != null) {
            drawIndexed(event, origin);
            return;
        }
        List<SceneGpu.Draw> lows = frameLows, raws = frameRaws;
        List<SceneGpu.Fine> fines = frameFines;
        lows.clear(); raws.clear(); fines.clear();
        active = visible = triangles = 0;
        for (Parent parent : parents) parent.high = false;
        for (Slot slot : requested) if (slot.wanted && slot.ready) parentHigh(slot);
        for (Parent parent : parents) {
            if (culling && !frustum.isVisible(parent.bounds)) continue;
            visible++;
            if (!parent.high) {
                addLow(lows, gpu.low, parent.offset, parent.triangles);
                continue;
            }
            if (!fineMode) {
                Slot raw = parent.raw;
                if (raw.buffer != null) {
                    int count = parent.page.raw_triangles();
                    raws.add(new SceneGpu.Draw(raw.buffer, 0, count));
                    triangles += count;
                }
                continue;
            }
            for (Slot slot : parent.bricks) {
                if (culling && !frustum.isVisible(slot.bounds)) continue;
                if (slot.wanted && slot.ready) {
                    if (slot.fine != null) {
                        updateCaps(slot);
                        fines.add(slot.fine);
                        triangles += fineTriangles(slot.fine);
                    }
                } else addLow(lows, gpu.low, slot.lowOffset, slot.lowTriangles);
            }
        }
        if (fineMode) {
            // 缓冲随被替换砖分配，截面属于其邻接 Low，法线朝向挖空区域。
            // 邻接砖完成接管后隐藏共享截面；表示类型决定选择，High 轮廓由生成路径维护。
            for (Slot high : requested) {
                if (!high.wanted || !high.ready || high.fine == null) continue;
                for (int direction = 0; direction < 6; direction++) {
                    int count = high.fine.edgeTriangles[direction];
                    if (count == 0) continue;
                    Slot low = high.neighbors[direction];
                    if (low != null && low.wanted && low.ready) continue;
                    if (culling && !frustum.isVisible(high.bounds)) continue;
                    addLow(
                            lows,
                            high.fine.buffer,
                            high.fine.edgeBase + high.fine.edgeOffsets[direction],
                            count);
                }
            }
        }
        triangles += fixed.append(fines, raws);
        draws = gpu.draw(event, origin, lows, raws, fines);
    }

    private void updateCaps(Slot slot) {
        slot.fine.capMask = 0;
        for (int d = 0; d < 6; d++) {
            if (slot.fine.capTriangles[d] == 0) continue;
            Slot adjacent = slot.neighbors[d];
            if (adjacent != null
                    && adjacent.displayed
                    && adjacent.precision.geometry() > slot.precision.geometry())
                slot.fine.capMask |= 1 << d;
        }
    }

    private static int fineTriangles(SceneGpu.Fine fine) {
        int count = fine.triangles;
        for (int d = 0; d < 6; d++) if ((fine.capMask & 1 << d) != 0) count += fine.capTriangles[d];
        return count;
    }

    void setPrecision(SceneVoxels.Precision selected) {
        if (precision.equals(selected) && precisionOverrides.isEmpty()) return;
        precision = selected;
        precisionOverrides.clear();
        clearHigh();
    }

    private int requiredCaps(Slot slot) {
        int mask = 0;
        for (int d = 0; d < 6; d++) {
            Slot adjacent = slot.neighbors[d];
            if (adjacent != null
                    && adjacent.wanted
                    && adjacent.precision.geometry() > slot.precision.geometry()) mask |= 1 << d;
        }
        return mask;
    }

    private void refreshCapRequests() {
        // 目标集合变化时刷新依赖；稳定帧复用已生成的截面和绘制范围。
        for (Slot slot : requested) {
            int needed = requiredCaps(slot);
            if ((slot.ready || slot.request != null) && (slot.preparedCaps & needed) != needed)
                invalidate(slot);
        }
    }

    String precisionDescription() {
        return "geometry="
                + precision.geometry()
                + " color="
                + precision.color()
                + " pbr="
                + precision.pbr()
                + " normal="
                + precision.normal()
                + " brickOverrides="
                + precisionOverrides.size();
    }

    // 调度层只提交目标档位；取消、资源回收和相邻截面刷新由运行时统一处理。
    void setPrecision(SceneLayout.Cell cell, SceneVoxels.Precision selected) {
        if (selected.equals(precisionOverrides.getOrDefault(cell, precision))) return;
        if (selected.equals(precision)) precisionOverrides.remove(cell);
        else precisionOverrides.put(cell, selected);
        Slot slot = bricks.get(cell);
        if (slot != null) {
            invalidate(slot);
            slot.precision = selected;
        }
        plannedMode = null;
    }

    private void invalidate(Slot slot) {
        invalidate(slot, false);
    }

    private void invalidate(Slot slot, boolean retain) {
        slot.revision++;

        if (slot.upload != null) {
            slot.upload.close();
            slot.upload = null;
        }
        // 材质换档保留已显示的 High，完整上传后在渲染线程替换。
        if (slot.ready && !retain) release(slot);
        slot.refreshing = retain;
        slot.failed = false;
    }

    private void want(Slot slot, boolean value) {
        if (slot.wanted == value) return;
        slot.wanted = value;
        refreshSlot(slot);
    }

    private void refreshSlot(Slot slot) {
        boolean displayed = slot.wanted && slot.ready;
        if (displayed == slot.displayed) return;
        slot.displayed = displayed;
        indexedActive += displayed ? 1 : -1;
        if (slot.cell != null) slot.parent.displayedBricks += displayed ? 1 : -1;
        slot.parent.dirty = true;
        if (slot.cell != null)
            for (int d = 0; d < 6; d++) {
                Slot adjacent = slot.neighbors[d];
                if (adjacent != null) adjacent.parent.dirty = true;
            }
    }

    private void drawIndexed(RenderLevelStageEvent.AfterOpaqueBlocks event, Vec3 origin) {
        List<SceneGpu.Draw> lows = frameLows, raws = frameRaws;
        List<SceneGpu.Fine> fines = frameFines;
        lows.clear(); raws.clear(); fines.clear();
        active = indexedActive;
        visible = visibleParents.size();
        triangles = 0;
        for (Parent parent : visibleParents) {
            if (parent.dirty) {
                parent.lows.clear();
                parent.raws.clear();
                parent.fines.clear();
                if (!fineMode) {
                    if (parent.raw.displayed) {
                        if (parent.raw.buffer != null)
                            parent.raws.add(
                                    new SceneGpu.Draw(
                                            parent.raw.buffer, 0, parent.page.raw_triangles()));
                    } else cacheLow(parent.lows, gpu.low, parent.offset, parent.triangles);
                } else {
                    boolean replaced = false;
                    for (Slot slot : parent.bricks)
                        if (slot.displayed) {
                            replaced = true;
                            break;
                        }
                    if (!replaced) cacheLow(parent.lows, gpu.low, parent.offset, parent.triangles);
                    else
                        for (Slot slot : parent.visibleBricks) {
                            if (slot.displayed) {
                                if (slot.fine != null) {
                                    updateCaps(slot);
                                    parent.fines.add(slot.fine);
                                }
                            } else
                                cacheLow(parent.lows, gpu.low, slot.lowOffset, slot.lowTriangles);
                        }
                    for (Slot high : parent.visibleBricks) {
                        if (!high.displayed || high.fine == null) continue;
                        for (int d = 0; d < 6; d++) {
                            if (high.fine.edgeTriangles[d] == 0) continue;
                            Slot neighbor = high.neighbors[d];
                            if (neighbor != null && neighbor.displayed) continue;
                            cacheLow(
                                    parent.lows,
                                    high.fine.buffer,
                                    high.fine.edgeBase + high.fine.edgeOffsets[d],
                                    high.fine.edgeTriangles[d]);
                        }
                    }
                }
                parent.drawTriangles = 0;
                for (var draw : parent.lows) parent.drawTriangles += draw.triangles();
                for (var draw : parent.raws) parent.drawTriangles += draw.triangles();
                for (var fine : parent.fines) parent.drawTriangles += fineTriangles(fine);
                parent.dirty = false;
            }
            lows.addAll(parent.lows);
            raws.addAll(parent.raws);
            fines.addAll(parent.fines);
            triangles += parent.drawTriangles;
        }
        triangles += fixed.append(fines, raws);
        draws = gpu.draw(event, origin, lows, raws, fines);
    }

    private void cacheLow(List<SceneGpu.Draw> draws, GpuBuffer buffer, int first, int count) {
        if (count == 0) return;
        int firstIndex = buffer == gpu.low && !lowIndexOffsets.isEmpty() ? lowIndexOffsets.get(first) : -1;
        if (firstIndex >= 0) first = 0;
        if (!draws.isEmpty()) {
            var last = draws.getLast();
            if (last.buffer() == buffer && (firstIndex >= 0
                    ? last.firstVertex() == first && last.firstIndex() + last.triangles() * 3 == firstIndex
                    : last.firstIndex() < 0 && last.firstVertex() + last.triangles() * 2 == first)) {
                draws.set(draws.size() - 1, new SceneGpu.Draw(buffer, last.firstVertex(), last.firstIndex(), last.triangles() + count)); return;
            }
        }
        draws.add(new SceneGpu.Draw(buffer, first, firstIndex, count));
    }

    private void parentHigh(Slot slot) {
        slot.parent.high = true;
        active++;
    }

    private void addLow(List<SceneGpu.Draw> lows, GpuBuffer buffer, int offset, int count) {
        cacheLow(lows, buffer, offset, count);
        triangles += count;
    }

    private void uploadLow() {
        while (lowCursor < lowData.size() && uploadedFrame < 1024 * 1024) {
            var brick = lowData.get(lowCursor);
            float[] data = brick.vertices();
            int remaining = data.length / 14 - lowVertexCursor;
            int count = Math.min(remaining, (int) ((1024 * 1024 - uploadedFrame) / gpu.stride));
            if (count == 0) break;
            gpu.uploadRange(data, lowVertexCursor, count, brick.offset() + lowVertexCursor);
            lowVertexCursor += count;
            uploadedFrame += (long) count * gpu.stride;
            if (lowVertexCursor == data.length / 14) {
                lowData.set(lowCursor++, null);
                lowVertexCursor = 0;
            }
        }
        if (lowCursor == lowData.size()) lowData = null;
    }

    private void reclaimDistant(Vec3 camera) {
        // 保留 High 进入半径两倍以内的折返成果；回收范围随几何档位扩大。
        // 游标扫描避免探索范围扩大后在每帧遍历全部历史成果。
        int examined = Math.min(64, residents.size()), released = 0;
        while (examined-- > 0 && !residents.isEmpty() && released < 2) {
            if (reclaimCursor >= residents.size()) reclaimCursor = 0;
            Slot slot = residents.get(reclaimCursor);
            if (!slot.wanted && slot.request == null
                    && distanceSquared(slot.bounds, camera) > Math.pow(fineMode ? highRange(slot.cell, false) * 2 : 192, 2)) {
                release(slot);
                slot.geometry = null;
                evictions++;
                released++;
            } else reclaimCursor++;
        }
    }

    private void release(Slot slot) {
        if (slot.buffer != null) {
            gpu.retireRaw(slot.buffer);
            slot.buffer = null;
        }
        if (slot.fine != null) {
            slot.fine.close();
            slot.fine = null;
        }
        residentBytes -= slot.cost;
        if (slot.residentIndex >= 0) {
            Slot last = residents.removeLast();
            if (last != slot) {
                residents.set(slot.residentIndex, last);
                last.residentIndex = slot.residentIndex;
            }
            slot.residentIndex = -1;
        }
        slot.ready = false;
        slot.refreshing = false;
        slot.cost = 0;
        refreshSlot(slot);
        resident = residents.size();
    }

    private void finishFrame(long start) {
        uploadedTotal += uploadedFrame;
        long end = System.nanoTime();
        if (lastFrame != 0) {
            frameTimes[samples % frameTimes.length] = start - lastFrame;
            cpuTimes[samples % cpuTimes.length] = end - start;
            samples++;
        }
        lastFrame = start;
    }

    static double distanceSquared(AABB b, Vec3 p) {
        double dx = Math.max(Math.max(b.minX - p.x, p.x - b.maxX), 0),
                dy = Math.max(Math.max(b.minY - p.y, p.y - b.maxY), 0),
                dz = Math.max(Math.max(b.minZ - p.z, p.z - b.maxZ), 0);
        return dx * dx + dy * dy + dz * dz;
    }

    void resetMetrics() {
        samples = generationSamples = 0;
        lastFrame = 0;
        uploadedTotal = switches = evictions = discarded = visibilityQueries = 0;
    }

    String stats() {
        int count = Math.min(samples, frameTimes.length);
        long[] times = Arrays.copyOf(frameTimes, count);
        Arrays.sort(times);
        long[] generation =
                Arrays.copyOf(generationTimes, Math.min(generationSamples, generationTimes.length));
        Arrays.sort(generation);
        long preparedBytes = 0;
        for (Slot slot : jobs)
            if (slot.request.isDone() && !slot.request.isCompletedExceptionally()) {
                Prepared data = slot.request.getNow(null);
                if (data != null)
                    preparedBytes +=
                            data.fine == null
                                    ? (long) data.raw.length * 4
                                    : ((long) data.fine.vertices().length
                                                    + data.fine.samples().length
                                                    + data.edges.vertices().length
                                                    + data.fine.texels().length)
                                            * 4;
            }
        return String.format(
                        Locale.ROOT,
                        "pages=%d bricks=%d lowReady=%d rawAvailable=%s rawActive=%d rawResident=%d"
                            + " pending=%d visible=%d triangles=%d draws=%d gpuMiB=%.2f"
                            + " uploadFrame=%d uploadTotal=%d switches=%d evictions=%d discarded=%d"
                            + " samples=%d frameP50/P95/P99/max=%.3f/%.3f/%.3f/%.3fms cpuAvg=%.3fms"
                            + " high=%s greedy=%s failed=%d",
                        parents.length,
                        bricks.size(),
                        lowCursor,
                        asset.rawAvailable(),
                        active,
                        resident,
                        pending,
                        visible,
                        triangles,
                        draws,
                        (gpu == null ? 0 : gpu.baseBytes() + gpu.highBytes()) / 1048576.0,
                        uploadedFrame,
                        uploadedTotal,
                        switches,
                        evictions,
                        discarded,
                        count,
                        percentile(times, .5),
                        percentile(times, .95),
                        percentile(times, .99),
                        percentile(times, 1),
                        count == 0
                                ? 0
                                : Arrays.stream(cpuTimes, 0, count).average().orElse(0) / 1e6,
                        fineMode ? "voxel" : "raw",
                        greedy,
                        failedPages)
                + String.format(
                        Locale.ROOT,
                        " genP95/max=%.3f/%.3fms preparedMiB=%.2f indexPayloadMiB=%.2f"
                                + " workspaceMiB=%.2f curveCacheMiB=%.2f visibilityQueries=%d",
                        percentile(generation, .95),
                        percentile(generation, 1),
                        preparedBytes / 1048576.0,
                        staticIndex == null ? 0 : staticIndex.bytes() / 1048576.0,
                        Arrays.stream(lanes).mapToLong(lane -> lane.voxels == null ? 0 : lane.voxels.workspaceBytes()).sum() / 1048576.0,
                        surface == null ? 0 : surface.cacheBytes() / 1048576.0,
                        visibilityQueries)
                + (seamCache == null ? "" : seamCache.stats())
                + (gpu == null ? "" : " " + gpu.materialStats())
                + (fixed == null ? "" : fixed.status())
                + (instances == null ? "" : " " + instances.stats());
    }

    private static double percentile(long[] sorted, double p) {
        return sorted.length == 0
                ? 0
                : sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))] / 1e6;
    }

    @Override
    public void close() {
        closed = true;
        clearHigh();
        if (fixed != null) fixed.close(gpu);
        if (instances != null) instances.close();
        if (gpu != null) gpu.close();
        CompletableFuture<?>[] unfinished = java.util.stream.Stream.concat(
                jobs.stream().map(slot -> slot.request),
                java.util.stream.Stream.of(fixed == null ? CompletableFuture.completedFuture(null) : fixed.pending(),
                        instances == null ? CompletableFuture.completedFuture(null) : instances.pending()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(unfinished).whenComplete((ignored, failure) -> {
            try { asset.close(); } catch (IOException error) {
                MineTale.LOGGER.warn("Scene asset close failed", error);
            }
        });
        for (Slot slot : bricks.values()) slot.geometry = null;
    }

    void setGreedy(boolean value) {
        if (greedy != value) {
            greedy = value;
            if (fineMode) clearHigh();
        }
    }

    private void clearHigh() {
        geometryGeneration++;
        for (Slot slot : requested) want(slot, false);
        for (Slot slot : jobs) {
            if (slot.upload != null) {
                slot.upload.close();
                slot.upload = null;
            }
        }

        for (Slot slot : new ArrayList<>(residents)) release(slot);
        for (Slot slot : bricks.values()) {
            slot.ready = slot.wanted = slot.failed = false;
            refreshSlot(slot);
            slot.cost = 0;
            slot.fineTriangles = 0;
        }
        for (Parent parent : parents) {
            parent.raw.ready = parent.raw.wanted = parent.raw.failed = false;
            refreshSlot(parent.raw);
            parent.dirty = true;
            parent.raw.cost = 0;
        }
        requested.clear();
        residentBytes = 0;
        resident = failedPages = 0;
        pending = jobs.size();
        plannedMode = null;
    }

    enum Mode {
        LOW,
        PAGED,
        RAW,
        VOXEL
    }

    private record Prepared(float[] raw, SceneVoxels.Page fine, SceneSeams.Edges edges,
            SceneVoxels.GeometryResult geometry, boolean merge, int capMask, long nanos, SceneAttachments.Cell attachment) {}

    private static final class Lane {
        SceneVoxels voxels;
        boolean busy;
    }

    private static final class Parent {
        final SceneAsset.Page page;
        final AABB bounds;
        final int offset, triangles;
        final Slot raw;
        final List<Slot> bricks = new ArrayList<>();
        final List<Slot> visibleBricks = new ArrayList<>();
        final List<SceneGpu.Draw> lows = new ArrayList<>(), raws = new ArrayList<>();
        final List<SceneGpu.Fine> fines = new ArrayList<>();
        boolean dirty = true;
        int drawTriangles;
        int displayedBricks;
        boolean high;

        Parent(SceneAsset.Page page, AABB bounds, int offset, int triangles) {
            this.page = page;
            this.bounds = bounds;
            this.offset = offset;
            this.triangles = triangles;
            raw = new Slot(this, null, bounds, offset, triangles);
        }
    }

    private static final class Slot {
        final Parent parent;
        final SceneLayout.Cell cell;
        final AABB bounds;
        final int lowOffset, lowTriangles;
        final Slot[] neighbors = new Slot[6];
        CompletableFuture<Prepared> request;
        Lane lane;
        long requestEpoch, requestRevision;
        SceneVoxels.GeometryResult geometry;
        boolean geometryGreedy;
        int geometryCaps;
        SceneAttachments.Cell attachment;
        GpuBuffer buffer;
        SceneGpu.Fine fine;
        SceneGpu.Upload upload;
        int fineTriangles;
        int residentIndex = -1;
        int preparedCaps;
        long cost;
        boolean failed, ready, generationRecorded, displayed, refreshing, visible;
        volatile boolean wanted;
        volatile long revision;
        SceneVoxels.Precision precision;
        int materialBand = 8;
        float maxGeometryResolution = 8, maxMaterialResolution = 8;
        double distance;

        Slot(Parent parent, SceneLayout.Cell cell, AABB bounds, int offset, int triangles) {
            this.parent = parent;
            this.cell = cell;
            this.bounds = bounds;
            lowOffset = offset;
            lowTriangles = triangles;
        }
    }
}
