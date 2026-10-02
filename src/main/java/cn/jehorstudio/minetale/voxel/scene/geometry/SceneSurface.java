package cn.jehorstudio.minetale.voxel.scene.geometry;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneChannels;

import com.google.gson.Gson;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntConsumer;

// 完整控制拓扑定义曲面；近场查询按需求值 CC，并按原始面缓存局部结果。
public final class SceneSurface {
    private static final int LEVELS = 3;
    private final List<Part> parts = new ArrayList<>();
    private final LinkedHashMap<Patch, Mesh> cache = new LinkedHashMap<>(128, .75F, true);
    private final Map<Patch, java.util.concurrent.CompletableFuture<Mesh>> building = new HashMap<>();
    private volatile long cacheLimit = 256L << 20;
    private long cacheBytes;
    private long cacheTreeBytes;
    private volatile long retainedCacheBytes;
    private SceneSourceMaterials materials;
    private SceneChannels channels;

    // 混合砖采用所有来源中最大的上限。
    public SceneVoxels.Precision precisionLimit(int[] sources) {
        float geometry = .5F, material = .5F;
        for (int source : sources) {
            Control control = parts.get(source >>> 20).control;
            geometry = Math.max(geometry, control.max_geometry_resolution);
            material = Math.max(material, control.max_material_resolution);
        }
        if (sources.length == 0) geometry = material = 8;
        return new SceneVoxels.Precision(geometry, material, material, material);
    }

    SceneChannels.Material material(int sourceId) {
        return channels.material(materials == null ? sourceId : materials.materialId(sourceId));
    }

    boolean bakeMaterials(float[] samples, java.util.function.BooleanSupplier cancelled) {
        if (materials == null) return false;
        for (int i = 0; i < samples.length; i += SceneVoxels.SAMPLE_FLOATS) {
            if ((i & 32767) == 0 && cancelled.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            materials.sample(samples, i);
        }
        return true;
    }

    int capMaterial(double[] point) {
        // 截面位于实体内部，原始 UV 由来源面提供；材质取最近来源影响区域，并投影到来源面。
        double best = Double.POSITIVE_INFINITY;
        int material = 0;
        for (Part part : parts) {
            int face = part.index.tree.nearest(point, part.index.boxes);
            if (face < 0) continue;
            double distance = SceneBvh.distanceSquared(part.index.boxes[face], 0, point);
            if (distance < best) {
                best = distance;
                material = part.control.faces[face].material;
            }
        }
        return material;
    }

    boolean closureOverlaps(double[] bounds, float resolution) {
        for (Part part : parts) {
            Mesh closure = part.closure(levels(resolution));
            if (closure != null && closure.tree.overlaps(bounds)) return true;
        }
        return false;
    }

    public long cacheBytes() {
        return retainedCacheBytes + (materials == null ? 0 : materials.textureBytes());
    }

    /** 固定来源数组的有效载荷估计；不含 JVM 对象头与容器空余容量。 */
    public long sourceBytes() {
        long bytes = materials == null ? 0 : materials.bytes() - materials.textureBytes();
        for (Part part : parts) {
            bytes += part.control.positions.length * 12L + part.index.boxes.length * 48L + part.index.tree.bytes();
            if (part.control.creases != null) for (float[] crease : part.control.creases) bytes += crease.length * 4L;
            for (Face face : part.control.faces) {
                bytes += face.vertices.length * 4L;
                for (int[] triangle : face.triangles) bytes += triangle.length * 4L;
                if (face.uv != null) for (float[] uv : face.uv) bytes += uv.length * 4L;
                if (face.normals != null) for (float[] normal : face.normals) bytes += normal.length * 4L;
            }
            if (part.cc != null) bytes += part.cc.bytes();
            bytes += part.extraClosureBytes();
            if (part.base != null) bytes += part.base.triangles.length * 4L + part.base.tree.bytes();
            if (part.closure != null && part.closure != part.base) bytes += part.closure.triangles.length * 4L + part.closure.tree.bytes();
        }
        return bytes;
    }

    public void cacheLimit(long bytes) {
        cacheLimit = Math.max(0, bytes);
        synchronized (cache) {
            var entries = cache.entrySet().iterator();
            while (cacheBytes() > cacheLimit && entries.hasNext()) {
                Mesh oldest = entries.next().getValue();
                cacheBytes -= oldest.triangles.length * 4L; cacheTreeBytes -= oldest.tree.bytes(); entries.remove();
                retainedCacheBytes = cacheBytes + cacheTreeBytes;
            }
        }
        if (materials != null) materials.trimTextures(Math.max(0, cacheLimit - retainedCacheBytes));
    }

    private SceneIndex staticIndex;

    void staticIndex(SceneIndex index) throws IOException {
        for (var brick : index.bricks)
            for (int source : brick.sources()) {
                int part = source >>> 20, face = source & 0xfffff;
                if (part >= parts.size() || face >= parts.get(part).control.faces.length)
                    throw new IOException("静态砖来源越界");
            }
        staticIndex = index;
    }

    private SceneSurface(List<Control> controls, boolean surfaceSamples, DataInput prepared)
            throws IOException {
        if (prepared != null && prepared.readInt() != controls.size())
            throw new IOException("静态部件数量不符");
        for (Control control : controls) parts.add(new Part(control, surfaceSamples, prepared));
    }

    public static SceneSurface read(SceneAsset asset) throws IOException {
        return read(asset, null);
    }

    static SceneSurface read(SceneAsset asset, DataInput prepared) throws IOException {
        Source source =
                new Gson()
                        .fromJson(
                                new String(
                                        asset.readSource("raw/source.json"),
                                        StandardCharsets.UTF_8),
                                Source.class);
        if (source == null
                || !"1.0".equals(source.version)
                || source.parts == null
                || source.parts.isEmpty()
                || source.parts.size() > 1 << 11) throw new IOException("无效的精细来源目录");
        String subdivisionInput = source.subdivision_input;
        if (!"control".equals(subdivisionInput) && !"surface_samples".equals(subdivisionInput))
            throw new IOException("未知的细分来源语义");
        if (!"procedural".equals(source.material_mode) && !"textured".equals(source.material_mode))
            throw new IOException("未知的来源材质模式");
        SceneSourceMaterials materials =
                "textured".equals(source.material_mode)
                        ? new SceneSourceMaterials(
                                asset,
                                new Gson()
                                        .fromJson(
                                                source.materials,
                                                SceneSourceMaterials.Definition[].class))
                        : null;
        validateControls(source, materials);
        SceneSurface result =
                new SceneSurface(
                        source.parts, "surface_samples".equals(subdivisionInput), prepared);
        result.materials = materials;
        result.channels = asset.channels();
        return result;
    }

    // 来源 JSON 的约束在进入曲面缓存前建立；细分和占用查询直接使用已验证拓扑。
    private static void validateControls(Source source, SceneSourceMaterials materials)
            throws IOException {
        for (Control c : source.parts) {
            if (c == null
                    || c.positions == null
                    || c.positions.length == 0
                    || c.faces == null
                    || c.faces.length == 0
                    || c.creases == null) throw new IOException("无效控制网格");
            if (materials != null && c.subdivision) throw new IOException("Raw 求值网格不能重复细分");
            for (float limit : new float[] {c.max_geometry_resolution, c.max_material_resolution})
                if (!(limit == .5F || limit == 1 || limit == 2 || limit == 4 || limit == 8))
                    throw new IOException("部件精度上限必须为 0.5、1、2、4、8");
            // 静态来源 ID 的低 20 位保存面号，高 11 位保存部件号，符号位固定为零。
            if (c.faces.length > 1 << 20) throw new IOException("来源面号超出编码范围");
            for (float[] p : c.positions) {
                if (p == null || p.length != 3) throw new IOException("无效控制点");
                for (float value : p)
                    if (!Float.isFinite(value) || Math.abs(value) > 1_000_000)
                        throw new IOException("控制点越界");
            }
            for (Face f : c.faces) {
                if (f == null
                        || f.vertices == null
                        || f.vertices.length < 3
                        || f.material < 0
                        || (materials == null && f.material > 7)
                        || f.triangles == null) throw new IOException("无效控制面");
                Set<Integer> corners = new HashSet<>();
                for (int v : f.vertices)
                    if (v < 0 || v >= c.positions.length || !corners.add(v))
                        throw new IOException("控制面索引无效");
                for (int[] tri : f.triangles) {
                    if (tri == null || tri.length != 3) throw new IOException("无效源三角化");
                    for (int v : tri) if (!corners.contains(v)) throw new IOException("三角化超出控制面");
                }
                if (materials != null) f.material = materials.add(c.positions, f);
                else if (f.smooth || f.normals != null || f.uv != null)
                    throw new IOException("程序材质来源要求逐面法线");
            }
            for (float[] e : c.creases)
                if (e == null
                        || e.length != 3
                        || e[0] != (int) e[0]
                        || e[1] != (int) e[1]
                        || e[0] < 0
                        || e[1] < 0
                        || e[0] >= c.positions.length
                        || e[1] >= c.positions.length
                        || e[0] == e[1]
                        || e[2] != 1) throw new IOException("当前 CC 来源要求 0/1 硬折痕");
        }
    }

    void writePrepared(DataOutput out) throws IOException {
        out.writeInt(parts.size());
        for (Part part : parts) {
            if (part.cc != null) {
                for (float[] position : part.cc.fittedPositions())
                    for (float value : position) out.writeFloat(value);
                part.cc.writePrepared(out);
            }
            for (double[] box : part.index.boxes) for (double value : box) out.writeDouble(value);
            part.index.tree.write(out);
        }
    }

    int partCount() {
        return parts.size();
    }

    double[][] faceBounds(int part) {
        return parts.get(part).index.boxes;
    }

    Map<SceneLayout.Cell, int[]> associations(
            int size, java.util.function.BooleanSupplier cancelled) throws IOException {
        var pending =
                new TreeMap<SceneLayout.Cell, it.unimi.dsi.fastutil.ints.IntArrayList>(
                        Comparator.comparingInt(SceneLayout.Cell::z)
                                .thenComparingInt(SceneLayout.Cell::y)
                                .thenComparingInt(SceneLayout.Cell::x));
        for (int part = 0; part < parts.size(); part++) {
            double[][] boxes = faceBounds(part);
            for (int face = 0; face < boxes.length; face++) {
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                double[] b = boxes[face];
                int[] first = new int[3], last = new int[3];
                for (int a = 0; a < 3; a++) {
                    first[a] = (int) Math.floor(Math.nextDown((b[a] - SceneVoxels.STEP) / size));
                    last[a] = (int) Math.floor((b[a + 3] + SceneVoxels.STEP) / size);
                }
                for (int z = first[2]; z <= last[2]; z++)
                    for (int y = first[1]; y <= last[1]; y++)
                        for (int x = first[0]; x <= last[0]; x++) {
                            pending.computeIfAbsent(
                                            new SceneLayout.Cell(x, y, z),
                                            ignored ->
                                                    new it.unimi.dsi.fastutil.ints.IntArrayList())
                                    .add((part << 20) | face);
                        }
            }
        }
        var result = new LinkedHashMap<SceneLayout.Cell, int[]>();
        pending.forEach((cell, ids) -> result.put(cell, ids.toIntArray()));
        return result;
    }

    int[][] select(double[] bounds) {
        int[][] result = new int[parts.size()][];
        for (int p = 0; p < result.length; p++) {
            var found = new it.unimi.dsi.fastutil.ints.IntArrayList();
            parts.get(p).index.box(bounds, found::add);
            result[p] = found.toIntArray();
            Arrays.sort(result[p]);
        }
        return result;
    }

    private static int levels(float resolution) {
        return resolution >= 8 ? 3 : resolution >= 4 ? 2 : 1;
    }

    List<Mesh> query(double[] bounds, float resolution, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        int levels = levels(resolution);
        List<Mesh> result = new ArrayList<>();
        // 来源关联沿用最高几何档的一格 Halo，较粗档通过 BVH 查询完整来源。
        if (staticIndex != null
                && bounds[3] - bounds[0] <= staticIndex.brickSize + 2 * staticIndex.sourceHalo()
                && bounds[4] - bounds[1] <= staticIndex.brickSize + 2 * staticIndex.sourceHalo()
                && bounds[5] - bounds[2] <= staticIndex.brickSize + 2 * staticIndex.sourceHalo()) {
            int size = staticIndex.brickSize;
            var cell =
                    new SceneLayout.Cell(
                            (int) Math.floor((bounds[0] + bounds[3]) * .5 / size),
                            (int) Math.floor((bounds[1] + bounds[4]) * .5 / size),
                            (int) Math.floor((bounds[2] + bounds[5]) * .5 / size));
            int[] sources = staticIndex.sources(cell);
            for (int first = 0; first < sources.length; ) {
                int part = sources[first] >>> 20, end = first + 1;
                while (end < sources.length && sources[end] >>> 20 == part) end++;
                Set<Integer> selected = new TreeSet<>();
                for (int i = first; i < end; i++) {
                    int face = sources[i] & 0xfffff;
                    double[] box = parts.get(part).index.boxes[face];
                    if (box[0] <= bounds[3] && box[3] >= bounds[0]
                            && box[1] <= bounds[4] && box[4] >= bounds[1]
                            && box[2] <= bounds[5] && box[5] >= bounds[2]) selected.add(face);
                }
                append(parts.get(part), selected, result, levels, cancelled);
                first = end;
            }
            return result;
        }
        for (Part part : parts) {
            Set<Integer> selected = new TreeSet<>();
            part.index.box(bounds, selected::add);
            append(part, selected, result, levels, cancelled);
        }
        return result;
    }

    private void append(
            Part part,
            Set<Integer> selected,
            List<Mesh> result, int levels,
            java.util.function.BooleanSupplier cancelled) {
        if (selected.isEmpty()) return;
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        if (!part.control.subdivision) {
            result.add(part.base);
            return;
        }
        result.addAll(patches(part, selected, levels, cancelled).values());
    }

    private it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap<Mesh> patches(
            Part part, Set<Integer> selected, int levels, java.util.function.BooleanSupplier cancelled) {
        var ordered = new it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap<Mesh>();
        var waiting = new LinkedHashMap<Integer, java.util.concurrent.CompletableFuture<Mesh>>();
        List<Integer> selectedFaces = new ArrayList<>(selected);
        // 每批先认领再在锁外生成，等待发生在发布本线程认领的全部成果之后，避免交叉等待。
        for (int first = 0; first < selectedFaces.size(); first += 32) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            Set<Integer> owned = new TreeSet<>();
            synchronized (cache) {
                for (int face : selectedFaces.subList(first, Math.min(first + 32, selectedFaces.size()))) {
                    Patch key = new Patch(part, face, levels);
                    Mesh found = cache.get(key);
                    if (found != null) {
                        ordered.put(face, found);
                        continue;
                    }
                    var flight = building.get(key);
                    if (flight == null) {
                        flight = new java.util.concurrent.CompletableFuture<>();
                        building.put(key, flight);
                        owned.add(face);
                    }
                    waiting.put(face, flight);
                }
            }
            if (owned.isEmpty()) continue;
            try {
                var evaluated = part.cc.evaluate(owned, levels);
                for (int face : owned) {
                    Mesh mesh = new Mesh(evaluated.getOrDefault(face, new float[0]), false);
                    synchronized (cache) {
                        remember(new Patch(part, face, levels), mesh);
                        building.remove(new Patch(part, face, levels)).complete(mesh);
                    }
                }
            } catch (Throwable failure) {
                synchronized (cache) {
                    for (int face : owned) {
                        var flight = building.remove(new Patch(part, face, levels));
                        if (flight != null) flight.completeExceptionally(failure);
                    }
                }
                throw failure;
            }
        }
        for (var entry : waiting.entrySet()) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            ordered.put(entry.getKey(), entry.getValue().join());
        }
        // 调用方持有不可变 Mesh；LRU 移除缓存引用不会使正在查询的曲面失效。
        return ordered;
    }

    void inside(
            double[] points,
            int count,
            boolean[] result, float resolution,
            java.util.function.BooleanSupplier cancelled) {
        int levels = levels(resolution);
        Arrays.fill(result, 0, count, false);
        // 查询点由砖工作区去重；每批共享射线命中的曲面片。
        for (int first = 0; first < count; first += 256) {
            int end = Math.min(count, first + 256);
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            for (Part part : parts) {
                if (!part.control.closed && part.closure == null) continue;
                if (part.base != null) {
                    for (int i = first; i < end; i++) {
                        double x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
                        if (!result[i] && part.index.tree.contains(x, y, z))
                            result[i] = part.inside(x, y, z);
                    }
                    continue;
                }
                var facesAtPoint = new it.unimi.dsi.fastutil.ints.IntArrayList[end - first];
                var selected = new it.unimi.dsi.fastutil.ints.IntAVLTreeSet();
                for (int i = first; i < end; i++) {
                    double x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
                    if (result[i] || !part.index.tree.contains(x, y, z)) continue;
                    var faces = new it.unimi.dsi.fastutil.ints.IntArrayList();
                    part.index.ray(
                            x,
                            y,
                            z,
                            face -> {
                                faces.add(face);
                                selected.add(face);
                            });
                    facesAtPoint[i - first] = faces;
                }
                if (selected.isEmpty() && part.closure == null) continue;
                var meshes = patches(part, selected, levels, cancelled);
                Mesh closure = part.closure(levels);
                for (int i = first; i < end; i++) {
                    var faces = facesAtPoint[i - first];
                    if (faces == null) continue;
                    double x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
                    int hits = closure == null ? 0 : closure.crossings(x, y, z);
                    for (int f = 0; f < faces.size(); f++)
                        hits += meshes.get(faces.getInt(f)).crossings(x, y, z);
                    result[i] = (hits & 1) != 0;
                }
            }
        }
    }

    boolean certifiedInside(double[] box) {
        // 曲面影响包围盒与单元分离后，才能证明整个单元为实体；人工推测封口归入辅助几何，实体证明使用可靠部件。
        double[] padded = box.clone();
        for (int a = 0; a < 3; a++) {
            padded[a] -= SceneVoxels.STEP;
            padded[a + 3] += SceneVoxels.STEP;
        }
        for (Part part : parts) {
            if (!part.control.closed
                    && (part.closure == null || part.closure.triangles.length != 0)) continue;
            if (!part.index.tree.contains(padded[0], padded[1], padded[2])
                    || !part.index.tree.contains(padded[3], padded[4], padded[5])) continue;
            boolean[] boundary = {false};
            part.index.box(padded, ignored -> boundary[0] = true);
            if (!boundary[0]
                    && part.inside(
                            (box[0] + box[3]) * .5, (box[1] + box[4]) * .5, (box[2] + box[5]) * .5))
                return true;
        }
        return false;
    }

    boolean inside(double x, double y, double z) {
        // 覆盖当前页的闭合部件参与实体并集，表面查询范围之外的部件也进入判定。
        for (Part part : parts) {
            if (part.index.tree.contains(x, y, z) && part.inside(x, y, z)) return true;
        }
        return false;
    }

    private void remember(Patch key, Mesh mesh) {
        Mesh previous = cache.put(key, mesh);
        cacheBytes += mesh.triangles.length * 4L - (previous == null ? 0 : previous.triangles.length * 4L);
        cacheTreeBytes += mesh.tree.bytes() - (previous == null ? 0 : previous.tree.bytes());
        var entries = cache.entrySet().iterator();
        while (cacheBytes + cacheTreeBytes > cacheLimit && entries.hasNext()) {
            Mesh oldest = entries.next().getValue();
            cacheBytes -= oldest.triangles.length * 4L;
            cacheTreeBytes -= oldest.tree.bytes();
            entries.remove();
        }
        retainedCacheBytes = cacheBytes + cacheTreeBytes;
    }

    private record Source(
            String version,
            String material_mode,
            String subdivision_input,
            List<Control> parts,
            com.google.gson.JsonElement materials) {}

    private record Patch(Part part, int face, int levels) {}

    private static final class Control {
        float max_geometry_resolution = 8, max_material_resolution = 8;
        float[][] positions, creases;
        Face[] faces;
        boolean subdivision, closed;
    }

    static final class Face {
        int[] vertices;
        int[][] triangles;
        int material;
        boolean smooth;
        float[][] uv, normals;
    }

    private final class Part {
        final Control control;
        final SceneSubdivision cc;
        final FaceIndex index;
        final Mesh base;
        final Mesh closure;
        private final Mesh[] closures = new Mesh[LEVELS + 1];

        // 实体射线与可见曲面使用同一细分级别，开放边界的辅助封口也必须匹配。
        synchronized long extraClosureBytes() {
            long bytes = 0;
            for (int level = 1; level < LEVELS; level++)
                if (closures[level] != null) bytes += closures[level].triangles.length * 4L + closures[level].tree.bytes();
            return bytes;
        }

        synchronized Mesh closure(int levels) {
            if (cc == null || closure == null || control.closed) return closure;
            if (closures[levels] == null) {
                float[] caps = SceneSurface.closure(control, cc, levels);
                if (caps != null) closures[levels] = new Mesh(caps, true);
            }
            return closures[levels];
        }

        Part(Control control, boolean surfaceSamples, DataInput prepared) throws IOException {
            this.control = control;
            float[][] fitted = control.positions;
            if (prepared != null && control.subdivision) {
                fitted = new float[control.positions.length][3];
                for (float[] point : fitted)
                    for (int axis = 0; axis < 3; axis++) {
                        float value = point[axis] = prepared.readFloat();
                        if (!Float.isFinite(value) || Math.abs(value) > 2_000_000)
                            throw new IOException("静态控制点越界");
                    }
            }
            cc =
                    control.subdivision
                            ? new SceneSubdivision(
                                    fitted,
                                    control.faces,
                                    control.creases,
                                    prepared == null && surfaceSamples,
                                    LEVELS)
                            : null;
            if (prepared != null && cc != null) {
                cc.readPrepared(prepared);
            }
            double[][] bounds = new double[control.faces.length][];
            FloatArrayList vertices = new FloatArrayList();
            for (int f = 0; f < bounds.length; f++) {
                Face face = control.faces[f];
                if (cc != null)
                    bounds[f] = prepared == null ? cc.influenceBounds(f) : new double[6];
                else {
                    bounds[f] =
                            new double[] {
                                Double.POSITIVE_INFINITY,
                                Double.POSITIVE_INFINITY,
                                Double.POSITIVE_INFINITY,
                                Double.NEGATIVE_INFINITY,
                                Double.NEGATIVE_INFINITY,
                                Double.NEGATIVE_INFINITY
                            };
                    for (int v : face.vertices)
                        for (int a = 0; a < 3; a++) {
                            bounds[f][a] = Math.min(bounds[f][a], control.positions[v][a]);
                            bounds[f][a + 3] = Math.max(bounds[f][a + 3], control.positions[v][a]);
                        }
                    double[] normal = new double[3];
                    for (int i = 0; i < face.vertices.length; i++) {
                        float[] a = control.positions[face.vertices[i]],
                                b =
                                        control.positions[
                                                face.vertices[(i + 1) % face.vertices.length]];
                        normal[0] += (a[1] - b[1]) * (a[2] + b[2]);
                        normal[1] += (a[2] - b[2]) * (a[0] + b[0]);
                        normal[2] += (a[0] - b[0]) * (a[1] + b[1]);
                    }
                    double length =
                            Math.sqrt(
                                    normal[0] * normal[0]
                                            + normal[1] * normal[1]
                                            + normal[2] * normal[2]);
                    if (length < 1e-15) continue;
                    for (int[] tri : face.triangles) {
                        for (int v : tri) {
                            for (float p : control.positions[v]) vertices.add(p);
                            if (face.normals == null) {
                                for (double n : normal) vertices.add((float) (n / length));
                            } else {
                                int corner = 0;
                                while (face.vertices[corner] != v) corner++;
                                for (float n : face.normals[corner]) vertices.add(n);
                            }
                        }
                        vertices.add(face.material);
                    }
                }
            }
            if (prepared != null)
                for (double[] box : bounds) {
                    for (int a = 0; a < 6; a++) {
                        box[a] = prepared.readDouble();
                        if (!Double.isFinite(box[a]) || Math.abs(box[a]) > 2_000_000)
                            throw new IOException("静态控制面边界越界");
                    }
                    for (int a = 0; a < 3; a++)
                        if (box[a] > box[a + 3]) throw new IOException("静态控制面边界反向");
                }
            index = new FaceIndex(bounds, prepared);
            base = cc == null ? new Mesh(vertices.toFloatArray(), control.closed) : null;
            float[] caps = control.closed ? null : SceneSurface.closure(control, cc, LEVELS);
            closure = caps == null ? null : new Mesh(caps, true);
            closures[LEVELS] = closure;
        }

        boolean inside(double x, double y, double z) {
            if (!control.closed && closure == null) return false;
            int initial = closure == null ? 0 : closure.crossings(x, y, z);
            if (base != null) return ((initial + base.crossings(x, y, z)) & 1) != 0;
            int[] crossings = {initial};
            // 闭合部件的起始内外状态需要完整射线；只细化实际可能命中的源面。
            index.ray(
                    x,
                    y,
                    z,
                    face -> {
                        Mesh patch = patches(this, Set.of(face), LEVELS, () -> false).get(face);
                        crossings[0] += patch.crossings(x, y, z);
                    });
            return (crossings[0] & 1) != 0;
        }
    }

    private static float[] closure(Control control, SceneSubdivision subdivision, int levels) {
        Map<Position, Integer> positions = new HashMap<>();
        int[] welded = new int[control.positions.length];
        for (int i = 0; i < welded.length; i++) {
            float[] p = control.positions[i];
            Position key = new Position(p[0], p[1], p[2]);
            Integer previous = positions.putIfAbsent(key, i);
            welded[i] = previous == null ? i : previous;
        }
        Map<Long, Integer> edges = new HashMap<>(), geometric = new HashMap<>();
        for (Face face : control.faces)
            for (int i = 0; i < face.vertices.length; i++) {
                int a = face.vertices[i], b = face.vertices[(i + 1) % face.vertices.length];
                edges.merge(edge(a, b), 1, Integer::sum);
                if (welded[a] != welded[b])
                    geometric.merge(edge(welded[a], welded[b]), 1, Integer::sum);
            }
        // 重合顶点和重复退化边只影响索引拓扑；体积射线按几何奇偶覆盖判断闭合。
        if (geometric.values().stream().allMatch(count -> (count & 1) == 0)) return new float[0];
        Map<Integer, List<Integer>> boundary = new HashMap<>();
        for (var entry : edges.entrySet())
            if ((entry.getValue() & 1) != 0) {
                int a = (int) (entry.getKey() >>> 32), b = (int) (long) entry.getKey();
                boundary.computeIfAbsent(a, ignored -> new ArrayList<>()).add(b);
                boundary.computeIfAbsent(b, ignored -> new ArrayList<>()).add(a);
            }
        if (boundary.isEmpty()
                || boundary.values().stream().anyMatch(neighbors -> neighbors.size() != 2))
            return null;
        Set<Integer> remaining = new TreeSet<>(boundary.keySet());
        FloatArrayList caps = new FloatArrayList();
        while (!remaining.isEmpty()) {
            int first = remaining.iterator().next(), previous = -1, current = first;
            List<Integer> loop = new ArrayList<>();
            do {
                if (!remaining.remove(current)) return null;
                loop.add(current);
                List<Integer> neighbors = boundary.get(current);
                int next = neighbors.get(0) == previous ? neighbors.get(1) : neighbors.get(0);
                previous = current;
                current = next;
            } while (current != first);
            float[][] polygon = new float[loop.size()][];
            for (int i = 0; i < polygon.length; i++) polygon[i] = control.positions[loop.get(i)];
            double[] normal = new double[3], center = new double[3];
            double tolerance = 1e-5;
            for (int i = 0; i < polygon.length; i++)
                for (int axis = 0; axis < 3; axis++) {
                    int u = (axis + 1) % 3, v = (axis + 2) % 3;
                    normal[axis] +=
                            (polygon[i][u] - polygon[(i + 1) % polygon.length][u])
                                    * (polygon[i][v] + polygon[(i + 1) % polygon.length][v]);
                    tolerance = Math.max(tolerance, Math.ulp(polygon[i][axis]) * 4);
                }
            double length =
                    Math.sqrt(
                            normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            if (length < 1e-10) return null;
            for (int axis = 0; axis < 3; axis++) normal[axis] /= length;
            for (int i = 0; i < polygon.length; i++) {
                double distance = 0, turn = 0;
                float[] p = polygon[i],
                        a = polygon[(i + polygon.length - 1) % polygon.length],
                        b = polygon[(i + 1) % polygon.length];
                for (int axis = 0; axis < 3; axis++) {
                    int u = (axis + 1) % 3, v = (axis + 2) % 3;
                    distance += (p[axis] - polygon[0][axis]) * normal[axis];
                    turn +=
                            ((p[u] - a[u]) * (b[v] - p[v]) - (p[v] - a[v]) * (b[u] - p[u]))
                                    * normal[axis];
                }
                if (Math.abs(distance) > tolerance || turn < -tolerance) return null;
            }
            if (subdivision != null)
                polygon =
                        subdivision.boundaryCurve(
                                loop.stream().mapToInt(Integer::intValue).toArray(), levels);
            for (float[] p : polygon)
                for (int axis = 0; axis < 3; axis++)
                    center[axis] += p[axis] / (double) polygon.length;
            // 平面凸开口用于封闭占用体积；这些三角形的作用域限定为体积判定，query、细分表面和渲染缓冲沿用原曲面。
            for (int i = 0; i < polygon.length; i++) {
                for (double p : center) caps.add((float) p);
                for (double n : normal) caps.add((float) n);
                for (int vertex : new int[] {i, (i + 1) % polygon.length}) {
                    for (float p : polygon[vertex]) caps.add(p);
                    for (double n : normal) caps.add((float) n);
                }
                caps.add(0);
            }
        }
        return caps.toFloatArray();
    }

    private static long edge(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    private record Position(float x, float y, float z) {
        Position {
            if (x == 0) x = 0;
            if (y == 0) y = 0;
            if (z == 0) z = 0;
        }
    }

    private static final class FaceIndex {
        final double[][] boxes;
        final SceneBvh tree;

        FaceIndex(double[][] boxes, DataInput prepared) throws IOException {
            this.boxes = boxes;
            tree = prepared == null ? new SceneBvh(boxes) : SceneBvh.read(prepared, boxes);
        }

        void box(double[] b, IntConsumer visit) {
            tree.box(
                    b,
                    face -> {
                        if (intersects(boxes[face], b)) visit.accept(face);
                    });
        }

        void ray(double x, double y, double z, IntConsumer visit) {
            tree.ray(
                    x,
                    y,
                    z,
                    face -> {
                        if (rayBox(boxes[face], x, y, z)) visit.accept(face);
                    });
        }

        static boolean intersects(double[] a, double[] b) {
            for (int i = 0; i < 3; i++) if (a[i] > b[i + 3] || a[i + 3] < b[i]) return false;
            return true;
        }
    }

    private static boolean rayBox(double[] b, double x, double y, double z) {
        double near =
                Math.max(
                        0,
                        Math.max(
                                b[0] - x,
                                Math.max(
                                        (b[1] - y) / .3713906763541037,
                                        (b[2] - z) / .52999894000318)));
        double far =
                Math.min(
                        b[3] - x,
                        Math.min((b[4] - y) / .3713906763541037, (b[5] - z) / .52999894000318));
        return far >= near;
    }

    static final class Mesh implements SceneBvh.RayTest {
        final float[] triangles;
        final SceneBvh tree;
        final boolean closed;
        Part owner;

        Mesh(float[] triangles, boolean closed) {
            this.triangles = triangles;
            this.closed = closed;
            double[][] boxes = new double[triangles.length / 19][6];
            for (int i = 0; i < boxes.length; i++)
                for (int a = 0; a < 3; a++) {
                    int t = i * 19 + a;
                    boxes[i][a] =
                            Math.min(triangles[t], Math.min(triangles[t + 6], triangles[t + 12]));
                    boxes[i][a + 3] =
                            Math.max(triangles[t], Math.max(triangles[t + 6], triangles[t + 12]));
                }
            tree = new SceneBvh(boxes);
        }

        void visit(double[] bounds, java.util.function.IntConsumer visitor) {
            tree.box(bounds, visitor);
        }

        // 固定非轴向射线避开规则网格的共面边；完整部件提供局部页的内外起始状态。
        boolean inside(double x, double y, double z) {
            return closed
                    && (owner != null ? owner.inside(x, y, z) : (crossings(x, y, z) & 1) != 0);
        }

        private int crossings(double x, double y, double z) {
            return tree.crossings(x, y, z, this);
        }

        @Override
        public boolean crosses(int triangle, double x, double y, double z) {
            double dy = .3713906763541037, dz = .52999894000318;
            int t = triangle * 19;
            double ax = triangles[t], ay = triangles[t + 1], az = triangles[t + 2];
            double ex = triangles[t + 6] - ax,
                    ey = triangles[t + 7] - ay,
                    ez = triangles[t + 8] - az;
            double fx = triangles[t + 12] - ax,
                    fy = triangles[t + 13] - ay,
                    fz = triangles[t + 14] - az;
            double px = dy * fz - dz * fy, py = dz * fx - fz, pz = fy - dy * fx;
            double det = ex * px + ey * py + ez * pz;
            if (Math.abs(det) < 1e-12) return false;
            double sx = x - ax, sy = y - ay, sz = z - az;
            double u = (sx * px + sy * py + sz * pz) / det;
            if (u < 0 || u > 1) return false;
            double qx = sy * ez - sz * ey, qy = sz * ex - sx * ez, qz = sx * ey - sy * ex;
            double v = (qx + dy * qy + dz * qz) / det;
            if (v < 0 || u + v > 1) return false;
            return (fx * qx + fy * qy + fz * qz) / det > 1e-9;
        }
    }
}
