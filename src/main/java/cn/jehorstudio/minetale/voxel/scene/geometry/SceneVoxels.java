package cn.jehorstudio.minetale.voxel.scene.geometry;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneChannels;

// 体素占用、来源和合并规则集中在此处；Height 只参与材质法线。
public final class SceneVoxels {
    public static final int SAMPLE_FLOATS = 12;
    public static final int GEOMETRY_RESOLUTION = 2;
    public static final int COLOR_RESOLUTION = 8;
    public static final int PBR_RESOLUTION = 8;
    public static final int NORMAL_RESOLUTION = 8;
    // 静态来源关联使用最高支持精度的 Halo，与运行时默认几何档位无关。
    public static final float STEP = 1F / 8;

    private final SceneSurface source;

    /**
     * 创建绑定到同一来源的体素生成器。调用方在单一工作线程串行生成；
     * 工作区跨砖复用，生成结果独立拥有输出数组。释放生成器和来源引用即可释放 CPU 缓存。
     *
     * @param source 已验证的场景来源，与生成器具有相同生命周期
     */
    public SceneVoxels(SceneSurface source) {
        this.source = source;
    }

    private volatile Workspace workspace;
    private volatile long retainedWorkspaceBytes;

    private Workspace workspace(int side) {
        if (workspace == null || workspace.side != side) {
            // 每个工作线程只持有当前尺寸；降档后不保留高精度三维数组。
            workspace = new Workspace(side);
            retainWorkspace(workspace);
        }
        return workspace;
    }

    private void retainWorkspace(Workspace completed) {
        completed.retainedBytes = completed.bytes();
        retainedWorkspaceBytes = completed.retainedBytes;
    }

    public long workspaceBytes() {
        return retainedWorkspaceBytes;
    }

    /** 仅在生成器空闲时释放可重建工作区；已发布成果独立持有自己的数组。 */
    public void releaseWorkspace() { workspace = null; retainedWorkspaceBytes = 0; }

    public long[] generationPhases() {
        return workspace == null ? new long[5] : workspace.phases.clone();
    }

    public Page generate(
            SceneLayout.Cell cell,
            int pageSize,
            boolean merge,
            java.util.function.BooleanSupplier cancelled)
            throws IOException {
        return generate(
                cell,
                pageSize,
                new Precision(
                        GEOMETRY_RESOLUTION, COLOR_RESOLUTION, PBR_RESOLUTION, NORMAL_RESOLUTION),
                merge,
                cancelled);
    }

    public Page generate(
            SceneLayout.Cell cell,
            int pageSize,
            Precision precision,
            boolean merge,
            java.util.function.BooleanSupplier cancelled)
            throws IOException {
        return generate(cell, pageSize, precision, 0, merge, cancelled);
    }

    /**
     * 在场景局部网格生成一个空间砖，并串行复用本生成器的工作区。
     *
     * @param cell 以 pageSize 为单位的整数坐标
     * @param pageSize 空间边长，必须对齐几何网格；0.5 档最多 32，其余档最多 8
     * @param precision 几何与三个材质通道的目标精度
     * @param capMask 需要生成的相邻档位截面；低六位依次表示 -X、+X、-Y、+Y、-Z、+Z
     * @param merge 是否合并具有相同着色帧的共面体素面
     * @param cancelled 取消检查器；在生成检查点返回 true 时抛出 CancellationException
     * @return 独立拥有顶点、采样、纹素索引和截面范围的结果；数组在交付后保持稳定
     */
    public Page generate(
            SceneLayout.Cell cell,
            int pageSize,
            Precision precision,
            int capMask,
            boolean merge,
            java.util.function.BooleanSupplier cancelled)
            throws IOException {
        if ((capMask & ~63) != 0) throw new IllegalArgumentException("截面方向掩码越界");
        Build build = new Build(cell, pageSize, precision, capMask, merge, cancelled);
        GeometryResult geometry = build.geometry();
        return material(geometry, precision, cancelled);
    }

    /** 生成可跨材质精度与渲染出口复用的独立几何； */
    public GeometryResult geometry(SceneLayout.Cell cell, int size, float resolution,
            boolean merge, java.util.function.BooleanSupplier cancelled) throws IOException {
        return new Build(cell, size, new Precision(resolution, .5F, .5F, .5F), 0, merge,
                cancelled).geometry();
    }

    public GeometryResult geometry(SceneLayout.Cell cell, int size, float resolution,
            int capMask, boolean merge, java.util.function.BooleanSupplier cancelled) throws IOException {
        return new Build(cell, size, new Precision(resolution, .5F, .5F, .5F), capMask, merge, cancelled).geometry();
    }

    /** 从已发布的几何采样材质； */
    public Page material(GeometryResult geometry, Precision precision,
            java.util.function.BooleanSupplier cancelled) throws IOException {
        return material(geometry, precision, true, cancelled);
    }

    public Page material(GeometryResult geometry, Precision precision, boolean shaderPack,
            java.util.function.BooleanSupplier cancelled) throws IOException {
        return material(geometry, precision, shaderPack, null, cancelled);
    }

    public Page material(GeometryResult geometry, Precision precision, boolean shaderPack,
            MaterialLayout previous, java.util.function.BooleanSupplier cancelled) throws IOException {
        return material(geometry, precision, shaderPack, previous, true, cancelled);
    }

    public Page layout(GeometryResult geometry, Precision precision, boolean shaderPack,
            java.util.function.BooleanSupplier cancelled) throws IOException {
        return material(geometry, precision, shaderPack, null, false, cancelled);
    }

    private Page material(GeometryResult geometry, Precision precision, boolean shaderPack,
            MaterialLayout previous, boolean evaluate, java.util.function.BooleanSupplier cancelled) throws IOException {
        if (geometry.resolution != precision.geometry())
            throw new IllegalArgumentException("材质请求与几何精度不匹配");
        Build build = new Build(geometry.cell, geometry.size, precision, 0, geometry.merge, cancelled);
        build.shaderPack = shaderPack;
        build.previousLayout = previous;
        build.materialCells = geometry.samples;
        for (Rectangle r : geometry.rectangles) {
            Rectangle copy = r.copy(); copy.sourceIndex = build.rectangles.size(); build.rectangles.add(copy);
        }
        build.resolveChannels();
        build.selectAtlasLayout();
        return build.emitPage(geometry, evaluate);
    }

    /** 只生成指定方向的实体截面；主体占用来自 geometry，不重复体素化。 */
    public GeometryResult cap(GeometryResult geometry, int direction,
            java.util.function.BooleanSupplier cancelled) throws IOException {
        if (direction < 0 || direction >= 6) throw new IllegalArgumentException("截面方向越界");
        Build build = new Build(geometry.cell, geometry.size,
                new Precision(geometry.resolution, .5F, .5F, .5F), 1 << direction,
                geometry.merge, cancelled);
        Workspace scratch = workspace(build.side);
        scratch.begin();
        for (int index : geometry.samples.indices.keySet()) scratch.stamp[index] = scratch.generation;
        try {
            if (geometry.resolution < 8)
                addCaps(source, source.query(build.bounds, geometry.resolution, cancelled), build.origin, build.side,
                        build.step, scratch, build.rectangles, 1 << direction, geometry.merge, cancelled);
            return new GeometryResult(geometry.cell, geometry.size, geometry.resolution,
                    geometry.merge, build.rectangles, geometry.samples, geometry.cells);
        } finally {
            retainWorkspace(scratch);
        }
    }

    /** 独立几何与来源帧快照；仅持有矩形、帧调色板和稀疏占用单元。 */
    public static final class GeometryResult {
        private final SceneLayout.Cell cell;
        private final int size, cells;
        private final float resolution;
        private final boolean merge;
        private final List<Rectangle> rectangles;
        private final CellSamples samples;

        private GeometryResult(SceneLayout.Cell cell, int size, float resolution, boolean merge,
                List<Rectangle> rectangles, CellSamples samples, int cells) {
            this.cell = cell;
            this.size = size;
            this.resolution = resolution;
            this.merge = merge;
            this.rectangles = List.copyOf(rectangles);
            this.samples = samples;
            this.cells = cells;
        }

        public long bytes() {
            long bytes = 128 + samples.bytes();
            for (Rectangle r : rectangles) bytes += 80L + (r.materials == null ? 0 : r.materials.length * 4L);
            return bytes;
        }

        public float resolution() { return resolution; }

        // 缓存只保存几何和来源身份。
        public void write(java.io.DataOutput out) throws IOException {
            out.writeInt(cell.x()); out.writeInt(cell.y()); out.writeInt(cell.z());
            out.writeInt(size); out.writeFloat(resolution); out.writeBoolean(merge); out.writeInt(cells);
            out.writeInt(samples.frames.length);
            for (Frame f : samples.frames) { out.writeInt(f.material); out.writeFloat(f.nx); out.writeFloat(f.ny); out.writeFloat(f.nz); out.writeBoolean(f.smooth != null); if (f.smooth != null) for (float value : f.smooth.values) out.writeFloat(value); }
            out.writeInt(samples.indices.size());
            for (var e : samples.indices.int2IntEntrySet()) { out.writeInt(e.getIntKey()); out.writeInt(e.getIntValue()); }
            out.writeInt(rectangles.size());
            for (Rectangle r : rectangles) {
                for (int value : new int[]{r.direction, r.plane, r.x, r.y, r.w, r.h, r.basis, r.capMaterial}) out.writeInt(value);
                out.writeBoolean(r.cap);
                out.writeInt(r.materials == null ? -1 : r.materials.length);
                if (r.materials != null) for (int value : r.materials) out.writeInt(value);
            }
        }

        public static GeometryResult read(java.io.DataInput in, SceneLayout.Cell expected) throws IOException {
            var cell = new SceneLayout.Cell(in.readInt(), in.readInt(), in.readInt());
            int size = in.readInt(); float resolution = in.readFloat(); boolean merge = in.readBoolean(); int cells = in.readInt();
            if (!cell.equals(expected) || size != 32 || resolution != .5F || !merge || cells < 0 || cells > 18 * 18 * 18)
                throw new IOException("基础几何缓存参数无效");
            int count = bounded(in.readInt(), 18 * 18 * 18);
            Frame[] frames = new Frame[count];
            for (int i = 0; i < count; i++) {
                int material = in.readInt(); float nx = in.readFloat(), ny = in.readFloat(), nz = in.readFloat();
                if (material < 0 || !Float.isFinite(nx + ny + nz)) throw new IOException("几何来源帧无效");
                NormalField smooth = null;
                if (in.readBoolean()) {
                    float[] values = new float[15];
                    for (int j = 0; j < values.length; j++) {
                        values[j] = in.readFloat();
                        if (!Float.isFinite(values[j])) throw new IOException("平滑法线场无效");
                    }
                    smooth = new NormalField(values);
                }
                frames[i] = new Frame(material, nx, ny, nz, smooth);
            }
            var indices = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(); indices.defaultReturnValue(-1);
            count = bounded(in.readInt(), 18 * 18 * 18);
            for (int i = 0; i < count; i++) {
                int index = in.readInt(), frame = in.readInt();
                if (index < 0 || index >= 18 * 18 * 18 || frame < 0 || frame >= frames.length || indices.put(index, frame) != -1)
                    throw new IOException("几何占用缓存无效");
            }
            count = bounded(in.readInt(), 6 * 18 * 18 * 18);
            var rectangles = new ArrayList<Rectangle>(count);
            for (int i = 0; i < count; i++) {
                int direction = in.readInt(), plane = in.readInt(), x = in.readInt(), y = in.readInt(), w = in.readInt(), h = in.readInt(), basis = in.readInt(), capMaterial = in.readInt();
                boolean cap = in.readBoolean(); int length = in.readInt(); int[] materials = null;
                if (direction < 0 || direction >= 6 || plane < 0 || plane >= 16 || x < 0 || y < 0 || w < 1 || h < 1 || x + w > 16 || y + h > 16 || basis < 0 || basis >= frames.length || cap)
                    throw new IOException("基础几何矩形无效");
                if (length != -1) {
                    if (length != w * h) throw new IOException("几何来源数组尺寸无效");
                    materials = new int[length];
                    for (int j = 0; j < length; j++) { materials[j] = in.readInt(); if (materials[j] < 0 || materials[j] >= frames.length) throw new IOException("几何来源索引无效"); }
                }
                Rectangle r = new Rectangle(direction, plane, x, y, w, h, basis, materials); r.capMaterial = capMaterial;
                rectangles.add(r);
            }
            return new GeometryResult(cell, size, resolution, merge, rectangles, new CellSamples(indices, frames), cells);
        }

        private static int bounded(int value, int maximum) throws IOException {
            if (value < 0 || value > maximum) throw new IOException("几何缓存数量越界");
            return value;
        }

        /** 返回指定目标包围盒内真实表面的最近点，返回值为距离平方；忽略跨砖封口。 */
        public double closest(double[] seed, float[] bounds, double[] output) {
            double best = Double.POSITIVE_INFINITY;
            double[] point = new double[3];
            for (Rectangle r : rectangles) {
                if (r.cap) continue;
                int axis = r.direction/2, u = (axis+1)%3, v = (axis+2)%3;
                point[axis] = cell.coordinate(axis)*(double)size+(r.plane+(r.direction&1))/(double)resolution;
                double u0 = cell.coordinate(u)*(double)size+r.x/(double)resolution;
                double v0 = cell.coordinate(v)*(double)size+r.y/(double)resolution;
                point[u] = Math.max(u0,Math.min(u0+r.w/(double)resolution,seed[u]));
                point[v] = Math.max(v0,Math.min(v0+r.h/(double)resolution,seed[v]));
                boolean valid = true;
                for (int a = 0; a < 3; a++) if (point[a] < bounds[a]-1/resolution || point[a] > bounds[a+3]+1/resolution) valid = false;
                if (!valid) continue;
                double distance = 0;
                for (int a = 0; a < 3; a++) { double d = point[a]-seed[a]; distance += d*d; }
                if (distance < best) { best = distance; System.arraycopy(point,0,output,0,3); }
            }
            return best;
        }

        /** 射线与真实矩形表面求交；参数和返回距离使用场景米制坐标。 */
        public double hit(double ox, double oy, double oz, double dx, double dy, double dz,
                double near, double far) {
            double[] origin = {ox, oy, oz}, ray = {dx, dy, dz};
            double closest = Double.POSITIVE_INFINITY;
            for (Rectangle r : rectangles) {
                if (r.cap) continue;
                int axis = r.direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
                if (Math.abs(ray[axis]) < 1e-12) continue;
                double plane = cell.coordinate(axis) * (double) size
                        + (r.plane + (r.direction & 1)) / (double) resolution;
                double t = (plane - origin[axis]) / ray[axis];
                if (t < near || t > far) continue;
                double pu = origin[u] + t * ray[u] - cell.coordinate(u) * (double) size;
                double pv = origin[v] + t * ray[v] - cell.coordinate(v) * (double) size;
                if (pu >= r.x / (double) resolution - 1e-7
                        && pu <= (r.x + r.w) / (double) resolution + 1e-7
                        && pv >= r.y / (double) resolution - 1e-7
                        && pv <= (r.y + r.h) / (double) resolution + 1e-7) closest = Math.min(closest, t);
            }
            return closest;
        }
    }

    private record Frame(int material, float nx, float ny, float nz, NormalField smooth) {}

    // 法线场随选中的来源三角形归属；保存未归一化的线性插值，顶点和材质在各自位置求值。
    private static final class NormalField {
        final float[] values;
        final int hash;
        NormalField(float[] values) { this.values = values; hash = Arrays.hashCode(values); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof NormalField field && Arrays.equals(values, field.values);
        }

        static NormalField from(float[] triangle, int start) {
            boolean constant = true;
            for (int a = 0; a < 3; a++)
                constant &= triangle[start + 3 + a] == triangle[start + 9 + a]
                        && triangle[start + 3 + a] == triangle[start + 15 + a];
            if (constant) return null;
            double[] e = new double[3], f = new double[3];
            double ee = 0, ef = 0, ff = 0;
            for (int a = 0; a < 3; a++) {
                e[a] = triangle[start + 6 + a] - triangle[start + a];
                f[a] = triangle[start + 12 + a] - triangle[start + a];
                ee += e[a] * e[a]; ef += e[a] * f[a]; ff += f[a] * f[a];
            }
            double determinant = ee * ff - ef * ef;
            if (determinant <= 1e-20) return null;
            float[] values = new float[15];
            System.arraycopy(triangle, start, values, 0, 6);
            for (int axis = 0; axis < 3; axis++) {
                double du = (ff * e[axis] - ef * f[axis]) / determinant;
                double dv = (ee * f[axis] - ef * e[axis]) / determinant;
                for (int a = 0; a < 3; a++)
                    values[6 + axis * 3 + a] = (float) (
                            (triangle[start + 9 + a] - triangle[start + 3 + a]) * du
                            + (triangle[start + 15 + a] - triangle[start + 3 + a]) * dv);
            }
            return new NormalField(values);
        }

        void normal(double x, double y, double z, float[] result) {
            double dx = x - values[0], dy = y - values[1], dz = z - values[2];
            double length = 0;
            for (int a = 0; a < 3; a++) {
                result[a] = (float) (values[3 + a] + dx * values[6 + a]
                        + dy * values[9 + a] + dz * values[12 + a]);
                length += result[a] * result[a];
            }
            if (length < 1e-20) { System.arraycopy(values, 3, result, 0, 3); return; }
            float scale = (float) (1 / Math.sqrt(length));
            for (int a = 0; a < 3; a++) result[a] *= scale;
        }
    }

    private static final class CellSamples {
        final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap indices;
        final Frame[] frames;
        CellSamples(it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap indices, Frame[] frames) { this.indices = indices; this.frames = frames; }
        CellSamples(Workspace cells) {
            indices = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(cells.count);
            indices.defaultReturnValue(-1);
            var palette = new Object2IntOpenHashMap<Frame>();
            palette.defaultReturnValue(-1);
            var values = new ArrayList<Frame>();
            for (int i = 0; i < cells.count; i++) {
                int index = cells.touched[i];
                Frame frame = new Frame(cells.material[index], cells.nx[index], cells.ny[index], cells.nz[index], cells.smooth[index]);
                int id = palette.getInt(frame);
                if (id < 0) { id = values.size(); palette.put(frame, id); values.add(frame); }
                indices.put(index, id);
            }
            frames = values.toArray(Frame[]::new);
        }
        long bytes() { return indices.size() * 16L + frames.length * 128L; }
        boolean same(int a, int b) {
            if (b < 0) return false;
            Frame x = frames[a], y = frames[b];
            return x.smooth != null ? x.smooth.equals(y.smooth)
                    : y.smooth == null && x.nx == y.nx && x.ny == y.ny && x.nz == y.nz;
        }
    }

    // 每次砖生成独占工作区，阶段间只传递同一份占用和矩形；发布的 Page 独立拥有输出数组。
    private final class Build {
        private MaterialLayout previousLayout;
        private final Precision precision;
        private final int capMask;
        private final boolean merge;
        private final java.util.function.BooleanSupplier cancelled;
        private final float geometryResolution, step;
        private final int side;
        private final int[] origin;
        private final double[] bounds;
        private Workspace cells;
        private CellSamples materialCells;
        private boolean shaderPack = true;
        private final float[] resolutions = new float[4];
        private final Map<SceneChannels.Material, Integer> materialIds = new LinkedHashMap<>();
        private boolean varyingNormal;
        private final SceneLayout.Cell cell;
        private final int pageSize;
        private List<SceneSurface.Mesh> meshes;
        private final List<Rectangle> rectangles = new ArrayList<>();
        private float textureResolution;
        private int size;

        Build(
                SceneLayout.Cell cell,
                int pageSize,
                Precision precision,
                int capMask,
                boolean merge,
                java.util.function.BooleanSupplier cancelled) {
            this.cell = cell;
            this.pageSize = pageSize;
            this.precision = precision;
            this.capMask = capMask;
            this.merge = merge;
            this.cancelled = cancelled;
            this.geometryResolution = precision.geometry();
            // 基础 LOD 按 32 米空间页合并；细节档使用 8 米砖，限制工作区边长。
            if (pageSize < 1
                    || pageSize > (geometryResolution == .5F ? 32 : 8)
                    || pageSize * geometryResolution % 1 != 0)
                throw new IllegalArgumentException("砖尺寸必须对齐几何网格");
            this.step = 1F / geometryResolution;
            this.side = (int) (pageSize * geometryResolution);
            this.origin = new int[] {cell.x() * side, cell.y() * side, cell.z() * side};
            this.bounds = new double[6];
            for (int a = 0; a < 3; a++) {
                bounds[a] = (origin[a] - 1) * step;
                bounds[a + 3] = (origin[a] + side + 1) * step;
            }
        }

        GeometryResult geometry() throws IOException {
            long started = System.nanoTime();
            cells = workspace(side);
            try {
                cells.begin();
                meshes = source.query(bounds, geometryResolution, cancelled);
                long queried = System.nanoTime();
                rasterize();
                long rasterized = System.nanoTime();
                classifyExposedFaces();
                long classified = System.nanoTime();
                mergeRectangles();
                long merged = System.nanoTime();
                cells.phases = new long[] {queried - started, rasterized - queried,
                        classified - rasterized, merged - classified, 0};
                CellSamples snapshot = new CellSamples(cells);
                List<Rectangle> frozen = new ArrayList<>(rectangles.size());
                for (Rectangle r : rectangles) {
                    int[] ids = r.materials == null ? null : r.materials.clone();
                    if (ids != null) for (int i = 0; i < ids.length; i++) ids[i] = snapshot.indices.get(ids[i]);
                    Rectangle copy = new Rectangle(r.direction, r.plane, r.x, r.y, r.w, r.h,
                            r.cap ? -1 : snapshot.indices.get(r.basis), ids);
                    copy.cap = r.cap;
                    copy.capMaterial = r.capMaterial;
                    frozen.add(copy);
                }
                return new GeometryResult(cell, pageSize, geometryResolution, merge, frozen,
                        snapshot, cells.count);
            } finally {
                retainWorkspace(cells);
            }
        }

        private void rasterize() throws IOException {
            for (var mesh : meshes)
                mesh.visit(
                        bounds,
                        index -> {
                            if (cancelled.getAsBoolean())
                                throw new java.util.concurrent.CancellationException();
                            raster(
                                    mesh.triangles,
                                    index * 19,
                                    origin,
                                    side,
                                    cells,
                                    step,
                                    cancelled);
                        });
        }

        private void classifyExposedFaces() {
            int queryCount = 0;
            for (int i = 0; i < cells.count; i++) {
                int cell = cells.touched[i];
                int[] p = cells.position(cell);
                if (p[0] < 0
                        || p[1] < 0
                        || p[2] < 0
                        || p[0] >= side
                        || p[1] >= side
                        || p[2] >= side) continue;
                for (int d = 0; d < 6; d++) {
                    int next = cell + cells.neighborStep(d);
                    if (cells.present(next) || cells.queried[next] == cells.generation) continue;
                    cells.queried[next] = cells.generation;
                    cells.queries[queryCount] = next;
                    int axis = d / 2;
                    p[axis] += (d & 1) == 0 ? -1 : 1;
                    for (int a = 0; a < 3; a++)
                        cells.points[queryCount * 3 + a] = (origin[a] + p[a] + .5) * step;
                    p[axis] -= (d & 1) == 0 ? -1 : 1;
                    queryCount++;
                }
            }
            source.inside(cells.points, queryCount, cells.answers, geometryResolution, cancelled);
            for (int i = 0; i < queryCount; i++) cells.solid[cells.queries[i]] = cells.answers[i];
            for (int i = 0; i < cells.count; i++) {
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                int cell = cells.touched[i];
                int[] p = cells.position(cell);
                if (p[0] < 0
                        || p[1] < 0
                        || p[2] < 0
                        || p[0] >= side
                        || p[1] >= side
                        || p[2] >= side) continue;
                for (int direction = 0; direction < 6; direction++) {
                    int axis = direction / 2, sign = (direction & 1) == 0 ? -1 : 1;
                    int neighbor = cell + cells.neighborStep(direction);
                    boolean solid = cells.present(neighbor) || cells.solid[neighbor];
                    if (solid) continue;
                    cells.slices[direction * side + p[axis]].add(cell);
                }
            }
        }

        private void mergeRectangles() {
            for (int slice = 0; slice < cells.slices.length; slice++) {
                int direction = slice / side, plane = slice % side;
                IntArrayList list = cells.slices[slice];
                if (list.isEmpty()) continue;
                int[] mask = cells.mask;
                Arrays.fill(mask, 0);
                int axis = direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
                for (int i = 0; i < list.size(); i++) {
                    int cell = list.getInt(i);
                    int[] p = cells.position(cell);
                    mask[p[u] + p[v] * side] = cell + 1;
                }
                for (int first = 0; first < mask.length; first++) {
                    if (mask[first] == 0) continue;
                    if (cancelled.getAsBoolean())
                        throw new java.util.concurrent.CancellationException();
                    int x = first % side, y = first / side;
                    int basis = mask[first] - 1;
                    int w = 1, h = 1;
                    if (merge) {
                        while (x + w < side && sameFrame(cells, basis, mask[first + w] - 1)) w++;
                        nextRow:
                        while (y + h < side) {
                            for (int dx = 0; dx < w; dx++)
                                if (!sameFrame(cells, basis, mask[first + dx + h * side] - 1))
                                    break nextRow;
                            h++;
                        }
                    }
                    int[] materials = new int[w * h];
                    for (int dy = 0; dy < h; dy++)
                        for (int dx = 0; dx < w; dx++) {
                            int at = first + dx + dy * side;
                            materials[dy * w + dx] = mask[at] - 1;
                            mask[at] = 0;
                        }
                    rectangles.add(new Rectangle(direction, plane, x, y, w, h, basis, materials));
                }
            }
            if (geometryResolution < 8 && capMask != 0)
                addCaps(
                        source,
                        meshes,
                        origin,
                        side,
                        step,
                        cells,
                        rectangles,
                        capMask,
                        merge,
                        cancelled);
        }

        private void resolveChannels() {
            int mask = 0;
            SceneChannels.Channel firstNormal = null;
            for (Frame frame : materialCells.frames) {
                var material = source.material(frame.material);
                materialIds.computeIfAbsent(material, ignored -> materialIds.size());
                mask |= material.variableMask(shaderPack);
                if (firstNormal == null) firstNormal = material.normal();
                else varyingNormal |= !firstNormal.equals(material.normal());
            }
            for (Rectangle r : rectangles) if (r.cap) {
                var material = source.material(r.capMaterial);
                materialIds.computeIfAbsent(material, ignored -> materialIds.size());
                mask |= material.variableMask(shaderPack);
                if (firstNormal == null) firstNormal = material.normal();
                else varyingNormal |= !firstNormal.equals(material.normal());
            }
            resolutions[0] = precision.color();
            if (firstNormal != null && !firstNormal.variable())
                varyingNormal |= firstNormal.x() != 0 || firstNormal.y() != 0 || firstNormal.z() != 1;
            for (int c = 1; c < 4; c++) resolutions[c] = (mask & (1 << c)) == 0 ? 0 : precision.material(c);
        }

        private int[] outputSizes(int layoutSize, float layout) {
            int color = (int) (layoutSize * Math.max(resolutions[0], layout) / layout);
            int normal = !shaderPack ? 0 : resolutions[1] != 0
                    ? (int) (layoutSize * Math.max(resolutions[1], layout) / layout) : varyingNormal ? color : 0;
            int specular = shaderPack ? (int) (layoutSize * Math.max(Math.max(resolutions[0], resolutions[2]), Math.max(resolutions[3], layout)) / layout) : 0;
            return new int[]{color, normal, specular, color};
        }

        private void selectAtlasLayout() throws IOException {
            float maximumResolution = precision.color(), minimumResolution = precision.color();
            for (float r : resolutions) if (r > 0) { maximumResolution = Math.max(maximumResolution, r); minimumResolution = Math.min(minimumResolution, r); }
            long minimumBytes = Long.MAX_VALUE;
            float selectedLayout = maximumResolution;
            // 所有通道共享 UV。布局从有限档位中按实际容量选择，低精度法线的
            // padding 纳入布局成本；较密存储格复用稀疏样本，材质求值次数维持原数量。
            for (float layout = maximumResolution;
                    layout >= minimumResolution;
                    layout *= .5F) {
                int trialSize = layout(rectangles, origin, geometryResolution, layout);
                long bytes = 0;
                int[] outputs = outputSizes(trialSize, layout);
                for (int channel = 0; channel < 4; channel++) {
                    int extent = outputs[channel];
                    bytes += (long) extent * extent * (channel == 3 ? 5 : 20) / 4;
                }
                if (bytes < minimumBytes) {
                    minimumBytes = bytes;
                    selectedLayout = layout;
                }
            }
            textureResolution = selectedLayout;
            size = layout(rectangles, origin, geometryResolution, textureResolution);
        }

        private Page emitPage(GeometryResult geometry, boolean evaluate) throws IOException {
            FloatArrayList uniqueSamples = new FloatArrayList();
            rectangles.sort(Comparator.comparingInt(r -> r.cap ? r.direction : -1));
            int[] capOffsets = new int[6], capTriangles = new int[6];
            int surfaceTriangles = 0;
            FloatArrayList vertices = new FloatArrayList();
            // NEAREST 空间采样和两级 mip 只需一圈边缘复制；分配区域按 2 对齐并填满，
            // 保证任意 2×2 mip footprint 只读取当前矩形的有效纹素。
            int[] channelSizes = new int[4], channelOffsets = new int[4];
            int texelCount = 0;
            for (int channel = 0; channel < 4; channel++) {
                if (resolutions[channel] == 0) { channelOffsets[channel] = -1; continue; }
                channelSizes[channel] =
                        (int)
                                (size
                                        * Math.max(resolutions[channel], textureResolution)
                                        / textureResolution);
                int shared = channel;
                for (int previous = 0; previous < channel; previous++)
                    if (resolutions[previous] == resolutions[channel]) {
                        shared = previous;
                        break;
                    }
                channelOffsets[channel] = shared == channel ? texelCount : channelOffsets[shared];
                if (shared == channel)
                    texelCount =
                            Math.addExact(
                                    texelCount,
                                    Math.multiplyExact(
                                            channelSizes[channel], channelSizes[channel]));
            }
            int[] texels = new int[evaluate ? texelCount : 0];
            java.util.Arrays.fill(texels, -1);
            var sampleIds = new Object2IntOpenHashMap<Sample>();
            sampleIds.defaultReturnValue(-1);
            for (Rectangle r : rectangles) {
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                if (r.cap) {
                    if (capTriangles[r.direction] == 0)
                        capOffsets[r.direction] = vertices.size() / 14;
                    capTriangles[r.direction] += 2;
                } else surfaceTriangles += 2;
                if (evaluate) sampleRectangle(r, uniqueSamples, sampleIds, channelSizes, channelOffsets, texels);
                appendVertices(r, vertices);
            }
            float[] samples = uniqueSamples.toFloatArray();
            int[] ranges = sortSamples(samples, texels);
            float[] constants = new float[materialIds.size() * 12];
            materialIds.forEach((m, id) -> {
                int at = id * 12;
                constants[at] = m.normal().x(); constants[at+1] = m.normal().y(); constants[at+2] = m.normal().z();
                constants[at+3] = m.variableMask(shaderPack);
                constants[at+4] = m.roughness().x(); constants[at+5] = m.metalness().x();
                constants[at+6] = m.emission().x(); constants[at+7] = m.emission().y(); constants[at+8] = m.emission().z();
            });
            boolean prebaked = evaluate && source.bakeMaterials(samples, cancelled);
            int[] layoutRectangles = new int[rectangles.size() * 6];
            for (Rectangle r : rectangles) {
                int at = r.sourceIndex * 6;
                layoutRectangles[at] = r.ax; layoutRectangles[at+1] = r.ay;
                layoutRectangles[at+2] = r.tx; layoutRectangles[at+3] = r.ty;
                layoutRectangles[at+4] = atlasExtent(r.pw); layoutRectangles[at+5] = atlasExtent(r.ph);
            }
            return new Page(
                    vertices.toFloatArray(),
                    samples,
                    texels,
                    size,
                    geometry.cells,
                    rectangles.size(),
                    prebaked,
                    precision,
                    channelSizes,
                    channelOffsets,
                    surfaceTriangles,
                    capOffsets,
                    capTriangles, shaderPack, constants, ranges, outputSizes(size, textureResolution),
                    new MaterialLayout(textureResolution, size, channelSizes[0], layoutRectangles), previousLayout != null);
        }

        private void sampleRectangle(
                Rectangle r,
                FloatArrayList uniqueSamples,
                Object2IntOpenHashMap<Sample> sampleIds,
                int[] channelSizes,
                int[] channelOffsets,
                int[] texels) {
            int axis = r.direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
            int[] q = new int[3];
            float[] normal = new float[3];
            for (int channel = 0; channel < 4; channel++) {
                if (resolutions[channel] == 0) continue;
                boolean shared = false;
                int channelMask = 0;
                for (int other = 0; other < 4; other++)
                    if (resolutions[other] == resolutions[channel]) {
                        shared |= other < channel;
                        channelMask |= other == 0 ? SceneChannels.COLOR | SceneChannels.EMISSION : 1 << other;
                    }
                if (shared) continue;
                float resolution = resolutions[channel];
                float storageResolution = Math.max(resolution, textureResolution);
                int ratio = (int) (storageResolution / textureResolution);
                int tx = (int) Math.floor(r.tu * ratio), ty = (int) Math.floor(r.tv * ratio);
                int pw = (int) Math.ceil((r.tu + r.tw) * ratio) - tx;
                int ph = (int) Math.ceil((r.tv + r.th) * ratio) - ty;
                // 求值只遍历通道自己的采样网格；图集存储格在下面单独映射。
                // 所有精度均为二次幂，storageResolution / resolution 为整数。
                int storagePerSample = (int) (storageResolution / resolution);
                int sampleX = Math.floorDiv(tx, storagePerSample);
                int sampleY = Math.floorDiv(ty, storagePerSample);
                int sampleWidth = Math.floorDiv(tx + pw - 1, storagePerSample) - sampleX + 1;
                int sampleHeight = Math.floorDiv(ty + ph - 1, storagePerSample) - sampleY + 1;
                int[] ids = new int[sampleWidth * sampleHeight];
                for (int y = 0; y < sampleHeight; y++)
                    for (int x = 0; x < sampleWidth; x++) {
                        if ((x & 255) == 0 && cancelled.getAsBoolean())
                            throw new java.util.concurrent.CancellationException();
                        q[axis] =
                                (int)
                                        Math.floor(
                                                (origin[axis] + r.plane + .5)
                                                        * resolution
                                                        / geometryResolution);
                        q[u] = sampleX + x;
                        q[v] = sampleY + y;
                        int cx =
                                Math.clamp(
                                        (int)
                                                Math.floor(
                                                        (q[u] + .5)
                                                                        * geometryResolution
                                                                        / resolution
                                                                - origin[u]
                                                                - r.x),
                                        0,
                                        r.w - 1);
                        int cy =
                                Math.clamp(
                                        (int)
                                                Math.floor(
                                                        (q[v] + .5)
                                                                        * geometryResolution
                                                                        / resolution
                                                                - origin[v]
                                                                - r.y),
                                        0,
                                        r.h - 1);
                        int material = r.cap ? -1 : r.materials[cy * r.w + cx];
                        if (!r.cap && resolution < geometryResolution) {
                            int px =
                                    (int) Math.floor((q[0] + .5) * geometryResolution / resolution)
                                            - origin[0];
                            int py =
                                    (int) Math.floor((q[1] + .5) * geometryResolution / resolution)
                                            - origin[1];
                            int pz =
                                    (int) Math.floor((q[2] + .5) * geometryResolution / resolution)
                                            - origin[2];
                            // 粗纹素中心可能落在 Halo 外；来源只取工作区内的占用单元。
                            if (px >= -1
                                    && py >= -1
                                    && pz >= -1
                                    && px <= side
                                    && py <= side
                                    && pz <= side) {
                                int width = side + 2;
                                int centered = materialCells.indices.get(((pz + 1) * width + py + 1) * width + px + 1);
                                if (materialCells.same(material, centered)) material = centered;
                            }
                        }
                        float nx =
                                r.cap
                                        ? (axis == 0 ? ((r.direction & 1) == 0 ? -1 : 1) : 0)
                                        : materialCells.frames[material].nx;
                        float ny =
                                r.cap
                                        ? (axis == 1 ? ((r.direction & 1) == 0 ? -1 : 1) : 0)
                                        : materialCells.frames[material].ny;
                        float nz =
                                r.cap
                                        ? (axis == 2 ? ((r.direction & 1) == 0 ? -1 : 1) : 0)
                                        : materialCells.frames[material].nz;
                        if (!r.cap && materialCells.frames[material].smooth != null) {
                            materialCells.frames[material].smooth.normal((q[0] + .5) / resolution,
                                    (q[1] + .5) / resolution, (q[2] + .5) / resolution, normal);
                            nx = normal[0]; ny = normal[1]; nz = normal[2];
                        }
                        int materialId = r.cap ? r.capMaterial : materialCells.frames[material].material;
                        var declaration = source.material(materialId);
                        int variableMask = channelMask & declaration.variableMask(shaderPack);
                        boolean reuseColor = previousLayout != null && (channelMask & 17) != 0;
                        if (reuseColor) variableMask &= ~17;
                        Sample key =
                                new Sample(q[0], q[1], q[2], materialId, nx, ny, nz, resolution);
                        int id = sampleIds.getInt(key);
                        if (id < 0) {
                            id = uniqueSamples.size() / SAMPLE_FLOATS;
                            sampleIds.put(key, id);
                            for (int k = 0; k < 3; k++)
                                uniqueSamples.add((q[k] + .5F) / resolution);
                            uniqueSamples.add(nx);
                            uniqueSamples.add(ny);
                            uniqueSamples.add(nz);
                            uniqueSamples.add(0);
                            uniqueSamples.add(materialId);
                            uniqueSamples.add(1 / resolution);
                            uniqueSamples.add(materialIds.get(declaration));
                            uniqueSamples.add(0); uniqueSamples.add(Float.intBitsToFloat(-1));
                        }
                        if (reuseColor) {
                            int at = r.sourceIndex * 6;
                            int[] previous = previousLayout.rectangles;
                            double ratioOld = (double) previousLayout.colorSize / previousLayout.size;
                            int px = (int)Math.floor((previous[at] + 1 - previous[at+2] + (q[u] + .5) / resolution * previousLayout.resolution) * ratioOld);
                            int py = (int)Math.floor((previous[at+1] + 1 - previous[at+3] + (q[v] + .5) / resolution * previousLayout.resolution) * ratioOld);
                            px = Math.clamp(px, (int)(previous[at] * ratioOld), (int)((previous[at] + previous[at+4]) * ratioOld) - 1);
                            py = Math.clamp(py, (int)(previous[at+1] * ratioOld), (int)((previous[at+1] + previous[at+5]) * ratioOld) - 1);
                            uniqueSamples.set(id * SAMPLE_FLOATS + 11, Float.intBitsToFloat(px | py << 16));
                        }
                        // 同一精度和来源共享求值；掩码使昂贵的法线邻域只在法线样本上执行。
                        uniqueSamples.set(
                                id * SAMPLE_FLOATS + 6, (int) uniqueSamples.getFloat(id * SAMPLE_FLOATS + 6) | variableMask);
                        ids[y * sampleWidth + x] = id;
                    }
                int width = channelSizes[channel];
                int padX = ratio + tx - r.tx * ratio, padY = ratio + ty - r.ty * ratio;
                for (int dy = 0; dy < atlasExtent(r.ph) * ratio; dy++)
                    for (int dx = 0; dx < atlasExtent(r.pw) * ratio; dx++) {
                        int cx = Math.clamp(dx - padX, 0, pw - 1),
                                cy = Math.clamp(dy - padY, 0, ph - 1);
                        texels[
                                        channelOffsets[channel]
                                                + (r.ay * ratio + dy) * width
                                                + r.ax * ratio
                                                + dx] =
                                ids[(Math.floorDiv(ty + cy, storagePerSample) - sampleY) * sampleWidth
                                        + Math.floorDiv(tx + cx, storagePerSample) - sampleX];
                    }
            }
        }

        private void appendVertices(Rectangle r, FloatArrayList vertices) {
            int axis = r.direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
            float[] n =
                    r.cap
                            ? new float[3]
                            : new float[] {materialCells.frames[r.basis].nx, materialCells.frames[r.basis].ny, materialCells.frames[r.basis].nz};
            if (r.cap) n[axis] = (r.direction & 1) == 0 ? -1 : 1;
            // cross(T,N) 是 Iris 的副切线；UV 方向独立于当前着色帧。
            int[] corners =
                    (r.direction & 1) == 1
                            ? new int[] {0, 1, 2, 3}
                            : new int[] {0, 3, 2, 1};
            for (int corner : corners) {
                int cu = (corner == 1 || corner == 2) ? 1 : 0, cv = corner >= 2 ? 1 : 0;
                float[] p = {origin[0] * step, origin[1] * step, origin[2] * step};
                p[axis] += (r.plane + ((r.direction & 1) == 1 ? 1 : 0)) * step;
                p[u] += (r.x + cu * r.w) * step;
                p[v] += (r.y + cv * r.h) * step;
                if (!r.cap && materialCells.frames[r.basis].smooth != null)
                    materialCells.frames[r.basis].smooth.normal(p[0], p[1], p[2], n);
                float[] tangent = tangent(n);
                for (float f : p) vertices.add(f);
                vertices.add((float) ((r.ax + 1 + r.tu - r.tx + cu * r.tw) / size));
                vertices.add((float) ((r.ay + 1 + r.tv - r.ty + cv * r.th) / size));
                for (float f : n) vertices.add(f);
                for (float f : tangent) vertices.add(f);
                vertices.add(1);
                vertices.add((float) ((r.ax + 1 + r.tu - r.tx + r.tw * .5) / size));
                vertices.add((float) ((r.ay + 1 + r.tv - r.ty + r.th * .5) / size));
            }
        }
    }

    private static int layout(
            List<Rectangle> rectangles, int[] origin, float geometry, float texture) {
        for (Rectangle r : rectangles) {
            int axis = r.direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
            r.tu = (origin[u] + r.x) * (double) texture / geometry;
            r.tv = (origin[v] + r.y) * (double) texture / geometry;
            r.tw = r.w * (double) texture / geometry;
            r.th = r.h * (double) texture / geometry;
            r.tx = (int) Math.floor(r.tu);
            r.ty = (int) Math.floor(r.tv);
            r.pw = (int) Math.ceil(r.tu + r.tw) - r.tx;
            r.ph = (int) Math.ceil(r.tv + r.th) - r.ty;
        }
        rectangles.sort(Comparator.comparingInt((Rectangle r) -> r.ph).reversed());
        int size = 2;
        while (!packAtlas(rectangles, size)) size = Math.multiplyExact(size, 2);
        return size;
    }

    private static void addCaps(
            SceneSurface source,
            List<SceneSurface.Mesh> meshes,
            int[] origin,
            int side,
            float step,
            Workspace cells,
            List<Rectangle> rectangles,
            int capMask,
            boolean merge,
            java.util.function.BooleanSupplier cancelled) {
        for (int direction = 0; direction < 6; direction++) {
            if ((capMask & 1 << direction) == 0) continue;
            int axis = direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
            int plane = (direction & 1) == 0 ? 0 : side - 1;
            int sign = (direction & 1) == 0 ? -1 : 1;
            int count = side * side;
            int queries =
                    capQueries(
                            source, meshes, origin, side, step, cells, axis, plane, sign, 0, 0,
                            side, side, 0, cancelled);
            source.inside(cells.points, queries, cells.answers, 1 / step, cancelled);
            int[] mask = cells.mask;
            for (int i = 0; i < count; i++) {
                int[] point = new int[3];
                point[axis] = plane;
                point[u] = i % side;
                point[v] = i / side;
                boolean inner =
                        cells.present(cells.index(point[0], point[1], point[2]))
                                || cells.answers[cells.queries[i * 2]];
                point[axis] += sign;
                boolean outer =
                        cells.present(cells.index(point[0], point[1], point[2]))
                                || cells.answers[cells.queries[i * 2 + 1]];
                // 外侧为空时已有普通暴露面；截面只补充沿砖边界切开的实体。
                mask[i] = inner && outer ? 1 : 0;
            }
            for (int i = 0; i < count; i++) {
                if (mask[i] == 0) continue;
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                int x = i % side, y = i / side, w = 1, h = 1;
                if (merge) {
                    while (x + w < side && mask[i + w] != 0) w++;
                    rows:
                    while (y + h < side) {
                        for (int dx = 0; dx < w; dx++) if (mask[i + dx + h * side] == 0) break rows;
                        h++;
                    }
                }
                for (int dy = 0; dy < h; dy++)
                    Arrays.fill(mask, i + dy * side, i + dy * side + w, 0);
                Rectangle rectangle = new Rectangle(direction, plane, x, y, w, h, -1, null);
                rectangle.cap = true;
                double[] center = {
                    (origin[0] + side * .5) * step,
                    (origin[1] + side * .5) * step,
                    (origin[2] + side * .5) * step
                };
                center[axis] = (origin[axis] + plane + .5) * step;
                center[u] = (origin[u] + x + w * .5) * step;
                center[v] = (origin[v] + y + h * .5) * step;
                rectangle.capMaterial = source.capMaterial(center);
                rectangles.add(rectangle);
            }
        }
    }

    private static int capQueries(
            SceneSurface source,
            List<SceneSurface.Mesh> meshes,
            int[] origin,
            int side,
            float step,
            Workspace cells,
            int axis,
            int plane,
            int sign,
            int x,
            int y,
            int width,
            int height,
            int count,
            java.util.function.BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        double[] bounds = new double[6];
        bounds[axis] = (origin[axis] + plane + .5 + Math.min(sign, 0)) * step;
        bounds[axis + 3] = (origin[axis] + plane + .5 + Math.max(sign, 0)) * step;
        bounds[u] = (origin[u] + x + .5) * step;
        bounds[u + 3] = (origin[u] + x + width - .5) * step;
        bounds[v] = (origin[v] + y + .5) * step;
        bounds[v + 3] = (origin[v] + y + height - .5) * step;
        boolean boundary = source.closureOverlaps(bounds, 1 / step);
        for (var mesh : meshes)
            if (mesh.tree.overlaps(bounds)) {
                boundary = true;
                break;
            }
        if (boundary && (width > 1 || height > 1)) {
            if (width >= height) {
                int half = width / 2;
                count =
                        capQueries(
                                source, meshes, origin, side, step, cells, axis, plane, sign, x, y,
                                half, height, count, cancelled);
                return capQueries(
                        source,
                        meshes,
                        origin,
                        side,
                        step,
                        cells,
                        axis,
                        plane,
                        sign,
                        x + half,
                        y,
                        width - half,
                        height,
                        count,
                        cancelled);
            }
            int half = height / 2;
            count =
                    capQueries(
                            source, meshes, origin, side, step, cells, axis, plane, sign, x, y,
                            width, half, count, cancelled);
            return capQueries(
                    source,
                    meshes,
                    origin,
                    side,
                    step,
                    cells,
                    axis,
                    plane,
                    sign,
                    x,
                    y + half,
                    width,
                    height - half,
                    count,
                    cancelled);
        }
        // 已求值曲面和占用封口与连通采样区域分离时，区域内体积归属恒定。
        // 先查询一个代表点；碰到曲面时再细分至格点，保守体素壳逐格叠加。
        for (int dy = 0; dy < height; dy++)
            for (int dx = 0; dx < width; dx++) {
                int at = ((y + dy) * side + x + dx) * 2;
                cells.queries[at] = count;
                cells.queries[at + 1] = count + (boundary ? 1 : 0);
            }
        for (int neighbor = 0; neighbor < (boundary ? 2 : 1); neighbor++) {
            int at = (count + neighbor) * 3;
            cells.points[at + axis] = (origin[axis] + plane + .5 + neighbor * sign) * step;
            cells.points[at + u] = (origin[u] + x + .5) * step;
            cells.points[at + v] = (origin[v] + y + .5) * step;
        }
        return count + (boundary ? 2 : 1);
    }

    private static float[] tangent(float[] n) {
        float x = Math.abs(n[1]) < .9F ? n[2] : 0,
                y = Math.abs(n[1]) < .9F ? 0 : -n[2],
                z = Math.abs(n[1]) < .9F ? -n[0] : n[1];
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[] {x / length, y / length, z / length};
    }

    private static boolean sameFrame(Workspace cells, int a, int b) {
        return b >= 0
                && cells.present(b)
                && (cells.smooth[a] != null ? cells.smooth[a].equals(cells.smooth[b])
                        : cells.smooth[b] == null && cells.nx[a] == cells.nx[b]
                                && cells.ny[a] == cells.ny[b] && cells.nz[a] == cells.nz[b]);
    }

    private static int atlasExtent(int length) {
        return (length + 3) & ~1;
    }

    private static boolean packAtlas(List<Rectangle> rectangles, int size) {
        int x = 0, y = 0, row = 0;
        for (Rectangle r : rectangles) {
            int w = atlasExtent(r.pw), h = atlasExtent(r.ph);
            if (w > size || h > size) return false;
            if (x + w > size) {
                x = 0;
                y += row;
                row = 0;
            }
            if (y + h > size) return false;
            r.ax = x;
            r.ay = y;
            x += w;
            row = Math.max(row, h);
        }
        return true;
    }

    private static void raster(
            float[] data,
            int t,
            int[] origin,
            int side,
            Workspace cells,
            float step,
            java.util.function.BooleanSupplier cancelled) {
        double[] a = new double[3], b = new double[3], c = new double[3], n = new double[3];
        for (int k = 0; k < 3; k++) {
            a[k] = data[t + k] / step - origin[k];
            b[k] = data[t + 6 + k] / step - origin[k];
            c[k] = data[t + 12 + k] / step - origin[k];
        }
        n[0] = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
        n[1] = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
        n[2] = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        int axis = Math.abs(n[1]) > Math.abs(n[0]) ? 1 : 0;
        if (Math.abs(n[2]) > Math.abs(n[axis])) axis = 2;
        if (Math.abs(n[axis]) < 1e-12) return;
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        int minU = Math.max(-1, (int) Math.floor(Math.min(a[u], Math.min(b[u], c[u]))));
        int maxU = Math.min(side, (int) Math.floor(Math.max(a[u], Math.max(b[u], c[u]))));
        int minV = Math.max(-1, (int) Math.floor(Math.min(a[v], Math.min(b[v], c[v]))));
        int maxV = Math.min(side, (int) Math.floor(Math.max(a[v], Math.max(b[v], c[v]))));
        double spread = (Math.abs(n[u]) + Math.abs(n[v])) / Math.abs(n[axis]) * .5;
        int[] q = new int[3];
        double[][] overlapPoints = new double[3][3];
        NormalField smooth = NormalField.from(data, t);
        float[] shadingNormal = new float[3];
        int candidates = 0;
        for (int y = minV; y <= maxV; y++)
            for (int x = minU; x <= maxU; x++) {
                if ((candidates++ & 255) == 0 && cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                double depth =
                        a[axis] - (n[u] * (x + .5 - a[u]) + n[v] * (y + .5 - a[v])) / n[axis];
                int lo = Math.max(-1, (int) Math.floor(depth - spread)),
                        hi = Math.min(side, (int) Math.floor(depth + spread));
                q[u] = x;
                q[v] = y;
                for (int z = lo; z <= hi; z++) {
                    q[axis] = z;
                    if (!overlap(a, b, c, q, overlapPoints)) continue;
                    double distance =
                            Math.abs(
                                    n[0] * (q[0] + .5 - a[0])
                                            + n[1] * (q[1] + .5 - a[1])
                                            + n[2] * (q[2] + .5 - a[2]));
                    distance /= Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                    int key = cells.index(q[0], q[1], q[2]);
                    boolean present = cells.present(key);
                    int material = (int) data[t + 18];
                    shadingNormal[0] = data[t + 3]; shadingNormal[1] = data[t + 4]; shadingNormal[2] = data[t + 5];
                    if (smooth != null) smooth.normal((origin[0] + q[0] + .5) * step,
                            (origin[1] + q[1] + .5) * step, (origin[2] + q[2] + .5) * step, shadingNormal);
                    boolean preferred =
                            present
                                    && (material < cells.material[key]
                                            || (material == cells.material[key]
                                                    && (shadingNormal[0] < cells.nx[key]
                                                            || (shadingNormal[0] == cells.nx[key]
                                                                    && (shadingNormal[1] < cells.ny[key]
                                                                            || (shadingNormal[1]
                                                                                            == cells.ny[
                                                                                                    key]
                                                                                    && shadingNormal[2]
                                                                                            < cells.nz[
                                                                                                    key]))))));
                    if (!present
                            || distance < cells.distance[key] - 1e-8
                            || (Math.abs(distance - cells.distance[key]) <= 1e-8 && preferred)) {
                        // 每个单元选定一个来源；六个面共享其材质身份和平滑法线场。
                        float nx = shadingNormal[0], ny = shadingNormal[1], nz = shadingNormal[2];
                        if (!present) {
                            cells.touched[cells.count++] = key;
                            cells.stamp[key] = cells.generation;
                        }
                        cells.material[key] = material;
                        cells.nx[key] = nx;
                        cells.ny[key] = ny;
                        cells.nz[key] = nz;
                        cells.distance[key] = distance;
                        cells.smooth[key] = smooth;
                    }
                }
            }
    }

    static boolean overlap(double[] a, double[] b, double[] c, int[] q) {
        return overlap(a, b, c, q, new double[3][3]);
    }

    private static boolean overlap(double[] a, double[] b, double[] c, int[] q, double[][] p) {
        for (int k = 0; k < 3; k++) {
            p[0][k] = a[k] - q[k] - .5;
            p[1][k] = b[k] - q[k] - .5;
            p[2][k] = c[k] - q[k] - .5;
        }
        for (int k = 0; k < 3; k++)
            if (Math.min(p[0][k], Math.min(p[1][k], p[2][k])) > .5
                    || Math.max(p[0][k], Math.max(p[1][k], p[2][k])) < -.5) return false;
        for (int e = 0; e < 3; e++) {
            double x = p[(e + 1) % 3][0] - p[e][0],
                    y = p[(e + 1) % 3][1] - p[e][1],
                    z = p[(e + 1) % 3][2] - p[e][2];
            if (separates(p, 0, z, -y) || separates(p, -z, 0, x) || separates(p, y, -x, 0))
                return false;
        }
        double ex = b[0] - a[0],
                ey = b[1] - a[1],
                ez = b[2] - a[2],
                fx = c[0] - a[0],
                fy = c[1] - a[1],
                fz = c[2] - a[2];
        return !separates(p, ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx);
    }

    private static boolean separates(double[][] p, double x, double y, double z) {
        double a = p[0][0] * x + p[0][1] * y + p[0][2] * z,
                b = p[1][0] * x + p[1][1] * y + p[1][2] * z,
                c = p[2][0] * x + p[2][1] * y + p[2][2] * z;
        double radius = .5 * (Math.abs(x) + Math.abs(y) + Math.abs(z));
        return Math.min(a, Math.min(b, c)) > radius + 1e-9
                || Math.max(a, Math.max(b, c)) < -radius - 1e-9;
    }

    // 每个生成线程独占 SceneVoxels 工作区；SceneSurface 只读查询与曲面成果在各线程共享。
    private static final class Workspace {
        final int side, width;
        final int[] stamp, material, touched, queried, queries, mask;
        final float[] nx, ny, nz;
        final NormalField[] smooth;
        final double[] distance, points;
        final boolean[] solid, answers;
        final IntArrayList[] slices;
        int generation, count;
        volatile long[] phases = new long[5];
        volatile long retainedBytes;

        Workspace(int side) {
            this.side = side;
            width = side + 2;
            int volume = width * width * width;
            stamp = new int[volume];
            material = new int[volume];
            touched = new int[volume];
            queried = new int[volume];
            queries = new int[volume];
            smooth = new NormalField[volume];
            nx = new float[volume];
            ny = new float[volume];
            nz = new float[volume];
            distance = new double[volume];
            points = new double[volume * 3];
            solid = new boolean[volume];
            answers = new boolean[volume];
            mask = new int[side * side];
            slices = new IntArrayList[side * 6];
            Arrays.setAll(slices, i -> new IntArrayList());
            retainedBytes = bytes();
        }

        void begin() {
            if (++generation == 0) {
                Arrays.fill(stamp, 0);
                Arrays.fill(queried, 0);
                generation = 1;
            }
            for (int i = 0; i < count; i++) smooth[touched[i]] = null;
            count = 0;
            for (var slice : slices) slice.clear();
        }

        int index(int x, int y, int z) {
            return x < -1 || y < -1 || z < -1 || x > side || y > side || z > side
                    ? -1
                    : ((z + 1) * width + y + 1) * width + x + 1;
        }

        int[] position(int index) {
            return new int[] {
                index % width - 1, index / width % width - 1, index / (width * width) - 1
            };
        }

        boolean present(int index) {
            return stamp[index] == generation;
        }

        int neighborStep(int direction) {
            int step = direction / 2 == 0 ? 1 : direction / 2 == 1 ? width : width * width;
            return (direction & 1) == 0 ? -step : step;
        }

        long bytes() {
            long bytes = stamp.length * 74L + mask.length * 4L;
            for (var slice : slices) bytes += slice.elements().length * 4L;
            return bytes;
        }
    }

    private record Sample(
            int x, int y, int z, int material, float nx, float ny, float nz, float resolution) {}

    private static final class Rectangle {
        // 几何范围按体素计算；纹理跨度按世界纹素计算，首纹素和像素数分别取整。
        final int direction, plane, x, y, w, h;
        final int basis;
        final int[] materials;
        boolean cap;
        int capMaterial;
        int ax, ay, tx, ty, pw, ph;
        int sourceIndex;
        double tu, tv, tw, th;

        Rectangle copy() {
            Rectangle result = new Rectangle(direction, plane, x, y, w, h, basis, materials);
            result.cap = cap;
            result.capMaterial = capMaterial;
            return result;
        }

        Rectangle(
                int direction, int plane, int x, int y, int w, int h, int basis, int[] materials) {
            this.direction = direction;
            this.plane = plane;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.basis = basis;
            this.materials = materials;
        }
    }

    public record Precision(float geometry, float color, float roughness, float normal, float metalness) {
        public Precision(float geometry, float color, float pbr, float normal) { this(geometry, color, pbr, normal, pbr); }
        public Precision {
            for (float value : new float[] {geometry, color, roughness, normal, metalness})
                if (!(value == .5F || value == 1 || value == 2 || value == 4 || value == 8))
                    throw new IllegalArgumentException("精度必须为 0.5、1、2、4、8");
        }

        public float pbr() { return Math.max(roughness, metalness); }

        public float material(int channel) {
            return switch (channel) {
                case 0 -> color;
                case 1 -> normal;
                case 2 -> roughness;
                case 3 -> metalness;
                default -> throw new IllegalArgumentException("材质通道越界");
            };
        }
    }

    // 索引按独立几何的矩形顺序排列；布局生命周期跟随 GPU 成果，用于保留颜色和 emission。
    public record MaterialLayout(float resolution, int size, int colorSize, int[] rectangles) {}

    public record Page(
            float[] vertices,
            float[] samples,
            int[] texels,
            int textureSize,
            int cells,
            int rectangles,
            boolean prebaked,
            Precision precision,
            int[] channelSizes,
            int[] channelOffsets,
            int surfaceTriangles,
            int[] capOffsets,
            int[] capTriangles,
            boolean shaderPack,
            float[] materialConstants,
            int[] sampleRanges,
            int[] outputSizes, MaterialLayout layout, boolean reusesColor) {
        public int outputSize(int channel) {
            return outputSizes[channel];
        }

        public int maximumSize() {
            return Math.max(outputSize(0), Math.max(outputSize(1), outputSize(2)));
        }

        public long textureBytes() {
            long bytes = 0;
            for (int channel = 0; channel < 4; channel++)
                bytes += (long) outputSize(channel) * outputSize(channel) * (channel == 3 ? 5 : 20) / 4;
            return bytes;
        }

        public long gpuBytes(int stride) {
            return (long) vertices.length / 14 * stride + textureBytes();
        }

        public long temporaryBytes() {
            return Math.max(16, (long)samples.length*4) + Math.max(16, (long)texels.length*4)
                    + Math.max(16, (long)samples.length/SAMPLE_FLOATS*(shaderPack ? 20 : 8))
                    + Math.max(16, (long)materialConstants.length*4);
        }

        public long uploadBytes(int stride) {
            return (long) vertices.length / 14 * stride
                    + (long) samples.length * 4
                    + (long) texels.length * 4;
        }
    }

    private static int[] sortSamples(float[] samples, int[] texels) {
        int count = samples.length / SAMPLE_FLOATS;
        int[] ranges = new int[33];
        for (int i = 0; i < count; i++) ranges[(int) samples[i * SAMPLE_FLOATS + 6] + 1]++;
        for (int i = 1; i < ranges.length; i++) ranges[i] += ranges[i-1];
        int[] cursor = ranges.clone(), remap = new int[count];
        float[] sorted = new float[samples.length];
        for (int i = 0; i < count; i++) {
            int to = cursor[(int) samples[i * SAMPLE_FLOATS + 6]]++;
            remap[i] = to;
            System.arraycopy(samples, i * SAMPLE_FLOATS, sorted, to * SAMPLE_FLOATS, SAMPLE_FLOATS);
        }

        for (int i = 0; i < texels.length; i++) if (texels[i] >= 0) texels[i] = remap[texels[i]];
        System.arraycopy(sorted, 0, samples, 0, samples.length);
        return ranges;
    }
}
