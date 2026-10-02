package cn.jehorstudio.minetale.voxel.scene.geometry;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

// 将 Low 视为实心。另一种表示接管砖后，周边截面只由剩余 Low 的体积决定。
public final class SceneSeams {
    private final int pageSize;
    // 每面 19 float：三角形位置 9、几何法线 3、UV 范围 4、纹理世界跨度 2、矩形标志 1。
    // 紧凑只读数组保留 Low 射线信息；上传后释放完整 Low 顶点，直接复用资产解压结果。
    private final float[] faces;
    private final Map<Column, IntArrayList> columns = new HashMap<>();
    private final Map<SceneLayout.Cell, IntArrayList> pages = new HashMap<>();

    /** 在指定页的真实 Low 表面求首交点；调用方限制到尚未被细节接管的砖区间。 */
    public double hit(SceneLayout.Cell page, double ox, double oy, double oz,
            double dx, double dy, double dz, double near, double far) {
        IntArrayList selected = pages.get(page);
        if (selected == null) return Double.POSITIVE_INFINITY;
        double[] origin = {ox, oy, oz}, ray = {dx, dy, dz};
        double closest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < selected.size(); i++) {
            int f = selected.getInt(i);
            double denominator = faces[f + 9] * dx + faces[f + 10] * dy + faces[f + 11] * dz;
            if (Math.abs(denominator) < 1e-12) continue;
            double t = ((faces[f] - ox) * faces[f + 9]
                    + (faces[f + 1] - oy) * faces[f + 10]
                    + (faces[f + 2] - oz) * faces[f + 11]) / denominator;
            if (t < near - 1e-7 || t > far + 1e-7 || t >= closest) continue;
            int axis = Math.abs(faces[f + 9]) >= Math.abs(faces[f + 10]) ? 0 : 1;
            if (Math.abs(faces[f + 11]) > Math.abs(faces[f + 9 + axis])) axis = 2;
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            double pu = origin[u] + ray[u] * t, pv = origin[v] + ray[v] * t;
            if (faces[f + 18] != 0) {
                if (pu < Math.min(faces[f + u], Math.min(faces[f + 3 + u], faces[f + 6 + u])) - 1e-7
                        || pu > Math.max(faces[f + u], Math.max(faces[f + 3 + u], faces[f + 6 + u])) + 1e-7
                        || pv < Math.min(faces[f + v], Math.min(faces[f + 3 + v], faces[f + 6 + v])) - 1e-7
                        || pv > Math.max(faces[f + v], Math.max(faces[f + 3 + v], faces[f + 6 + v])) + 1e-7) continue;
            } else {
                double au = faces[f + 3 + u] - faces[f + u], av = faces[f + 3 + v] - faces[f + v];
                double bu = faces[f + 6 + u] - faces[f + u], bv = faces[f + 6 + v] - faces[f + v];
                double determinant = au * bv - av * bu;
                double a = ((pu - faces[f + u]) * bv - (pv - faces[f + v]) * bu) / determinant;
                double b = (au * (pv - faces[f + v]) - av * (pu - faces[f + u])) / determinant;
                if (a < -1e-7 || b < -1e-7 || a + b > 1 + 1e-7) continue;
            }
            closest = t;
        }
        return closest;
    }

    public String cacheKey(int brickSize) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var bytes = java.nio.ByteBuffer.allocate(4096);
            bytes.putInt(pageSize).putInt(brickSize);
            for (float value : faces) {
                if (bytes.remaining() < 4) {
                    digest.update(bytes.array(), 0, bytes.position());
                    bytes.clear();
                }
                bytes.putFloat(value);
            }
            digest.update(bytes.array(), 0, bytes.position());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public long bytes() {
        long bytes = (long) faces.length * 4;
        for (var values : columns.values()) bytes += (long) values.elements().length * 4;
        for (var values : pages.values()) bytes += (long) values.elements().length * 4;
        return bytes;
    }

    public SceneSeams(float[][] low, int pageSize) {
        this.pageSize = pageSize;
        FloatArrayList data = new FloatArrayList();
        for (float[] vertices : low) {
            for (int start = 0; start < vertices.length; ) {
                boolean rectangle = SceneLayout.pairedRectangle(vertices, start);
                int offset = data.size();
                for (int i = 0; i < 3; i++)
                    for (int axis = 0; axis < 3; axis++) data.add(vertices[start + i * 14 + axis]);
                float[] normal = new float[3];
                for (int axis = 0; axis < 3; axis++) {
                    int u = (axis + 1) % 3, v = (axis + 2) % 3;
                    normal[axis] =
                            (vertices[start + 14 + u] - vertices[start + u])
                                            * (vertices[start + 28 + v] - vertices[start + v])
                                    - (vertices[start + 14 + v] - vertices[start + v])
                                            * (vertices[start + 28 + u] - vertices[start + u]);
                    data.add(normal[axis]);
                }
                for (int uv = 3; uv < 5; uv++) {
                    data.add(
                            Math.min(
                                    vertices[start + uv],
                                    Math.min(
                                            vertices[start + 14 + uv], vertices[start + 28 + uv])));
                    data.add(
                            Math.max(
                                    vertices[start + uv],
                                    Math.max(
                                            vertices[start + 14 + uv], vertices[start + 28 + uv])));
                }
                for (int uv = 3; uv < 5; uv++) {
                    float span = 1;
                    for (int i = 0; i < 3; i++) {
                        int a = start + i * 14, b = start + (i + 1) % 3 * 14;
                        if (vertices[a + 7 - uv] != vertices[b + 7 - uv]
                                || vertices[a + uv] == vertices[b + uv]) continue;
                        double length = 0;
                        for (int axis = 0; axis < 3; axis++)
                            length += Math.pow(vertices[a + axis] - vertices[b + axis], 2);
                        span = (float) Math.sqrt(length);
                    }
                    data.add(span);
                }
                data.add(rectangle ? 1 : 0);
                for (int axis = 0; axis < 3; axis++) {
                    if (Math.abs(normal[axis]) < 1e-12) continue;
                    int u = (axis + 1) % 3, v = (axis + 2) % 3;
                    int cu =
                            (int)
                                    Math.floor(
                                            Math.min(
                                                            vertices[start + u],
                                                            Math.min(
                                                                    vertices[start + 14 + u],
                                                                    vertices[start + 28 + u]))
                                                    / pageSize);
                    int cv =
                            (int)
                                    Math.floor(
                                            Math.min(
                                                            vertices[start + v],
                                                            Math.min(
                                                                    vertices[start + 14 + v],
                                                                    vertices[start + 28 + v]))
                                                    / pageSize);
                    columns.computeIfAbsent(new Column(axis, cu, cv), ignored -> new IntArrayList())
                            .add(offset);
                }
                int[] cell = new int[3];
                for (int axis = 0; axis < 3; axis++)
                    cell[axis] =
                            (int)
                                    Math.floor(
                                            (vertices[start + axis]
                                                            + vertices[start + 14 + axis]
                                                            + vertices[start + 28 + axis])
                                                    / (3.0 * pageSize));
                pages.computeIfAbsent(
                                new SceneLayout.Cell(cell[0], cell[1], cell[2]),
                                ignored -> new IntArrayList())
                        .add(offset);
                start += rectangle ? 84 : 42;
            }
        }
        faces = data.toFloatArray();
    }

    private byte[] lowBoundary(
            SceneLayout.Cell high,
            int size,
            int direction,
            float[] us,
            float[] vs,
            BooleanSupplier cancelled) {
        int axis = direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
        int width = us.length - 1, height = vs.length - 1;
        float plane = (high.coordinate(axis) + (direction & 1)) * size;
        // 判断紧邻分界面的 Low；查询保留位于该平面附近的小数坐标表面。
        double sample =
                Math.nextAfter(
                        (double) plane,
                        (direction & 1) == 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
        byte[] result = new byte[width * height];
        double[] nearest = new double[result.length];
        double[] previous = new double[result.length];
        java.util.Arrays.fill(nearest, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(previous, Double.NEGATIVE_INFINITY);
        IntArrayList list =
                columns.get(
                        new Column(
                                axis,
                                Math.floorDiv(high.coordinate(u) * size, pageSize),
                                Math.floorDiv(high.coordinate(v) * size, pageSize)));
        if (list == null) return result;
        for (int index = 0; index < list.size(); index++) {
            if ((index & 255) == 0 && cancelled.getAsBoolean())
                throw new java.util.concurrent.CancellationException();
            int f = list.getInt(index);
            int minU = cutIndex(us, min(f, u), false), maxU = cutIndex(us, max(f, u), true);
            int minV = cutIndex(vs, min(f, v), false), maxV = cutIndex(vs, max(f, v), true);
            boolean owned =
                    (direction & 1) != 0
                            && faces[f + 9 + axis] < 0
                            && min(f, axis) == plane
                            && max(f, axis) == plane;
            double ex = faces[f + 3 + u] - faces[f + u], ey = faces[f + 3 + v] - faces[f + v];
            double fx = faces[f + 6 + u] - faces[f + u], fy = faces[f + 6 + v] - faces[f + v];
            double determinant = ex * fy - ey * fx;
            for (int y = minV; y < maxV; y++)
                for (int x = minU; x < maxU; x++) {
                    double pu = ((double) us[x] + us[x + 1]) * .5 - faces[f + u];
                    double pv = ((double) vs[y] + vs[y + 1]) * .5 - faces[f + v];
                    if (pu + faces[f + u] < min(f, u)
                            || pu + faces[f + u] >= max(f, u)
                            || pv + faces[f + v] < min(f, v)
                            || pv + faces[f + v] >= max(f, v)) continue;
                    double a = (pu * fy - pv * fx) / determinant,
                            b = (ex * pv - ey * pu) / determinant;
                    if (faces[f + 18] == 0 && (a < -1e-8 || b < -1e-8 || a + b > 1 + 1e-8))
                        continue;
                    double depth =
                            faces[f + axis]
                                    + a * (faces[f + 3 + axis] - faces[f + axis])
                                    + b * (faces[f + 6 + axis] - faces[f + axis]);
                    int cell = y * width + x;
                    if (owned) result[cell] |= 2;
                    // 单侧面片只在入面和出面之间表达实体；采样点必须位于这一区间。
                    if (depth < sample) {
                        if (depth > previous[cell]) {
                            previous[cell] = depth;
                            result[cell] =
                                    (byte)
                                            ((result[cell] & ~4)
                                                    | (faces[f + 9 + axis] < 0 ? 4 : 0));
                        }
                        continue;
                    }
                    if (depth >= nearest[cell]) continue;
                    nearest[cell] = depth;
                    result[cell] = (byte) ((result[cell] & ~1) | (faces[f + 9 + axis] > 0 ? 1 : 0));
                }
        }
        return result;
    }

    private static int cutIndex(float[] cuts, float value, boolean upper) {
        int index = java.util.Arrays.binarySearch(cuts, value);
        return Math.clamp(index >= 0 ? index : -index - (upper ? 1 : 2), 0, cuts.length - 1);
    }

    private float[] cuts(SceneLayout.Cell high, int size, int direction, int dimension) {
        float[] grid = {high.coordinate(dimension) * size, (high.coordinate(dimension) + 1) * size};
        FloatArrayList values = new FloatArrayList(grid);
        int axis = direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
        IntArrayList list =
                columns.get(
                        new Column(
                                axis,
                                Math.floorDiv(high.coordinate(u) * size, pageSize),
                                Math.floorDiv(high.coordinate(v) * size, pageSize)));
        if (list != null)
            for (int i = 0; i < list.size(); i++) {
                int face = list.getInt(i);
                if (max(face, u) <= high.coordinate(u) * size
                        || min(face, u) >= (high.coordinate(u) + 1) * size
                        || max(face, v) <= high.coordinate(v) * size
                        || min(face, v) >= (high.coordinate(v) + 1) * size) continue;
                for (float edge : new float[] {min(face, dimension), max(face, dimension)})
                    if (edge > grid[0] && edge < grid[grid.length - 1]) values.add(edge);
            }
        float[] sorted = values.toFloatArray();
        java.util.Arrays.sort(sorted);
        int count = 0;
        for (float value : sorted)
            if (count == 0 || value != sorted[count - 1]) sorted[count++] = value;
        return java.util.Arrays.copyOf(sorted, count);
    }

    public Edges lowCaps(SceneLayout.Cell high, int size, BooleanSupplier cancelled) {
        return lowCaps(high, size, cancelled, null);
    }

    Edges lowCaps(
            SceneLayout.Cell high, int size, BooleanSupplier cancelled, SceneLowVolume volume) {
        FloatArrayList vertices = new FloatArrayList();
        int[] offsets = new int[6], triangles = new int[6];
        for (int direction = 0; direction < 6; direction++) {
            offsets[direction] = vertices.size() / 14;
            int axis = direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
            // 截面沿 Low 的实际轮廓分割，来源只依赖 Low 几何。
            float[] us =
                    volume == null
                            ? cuts(high, size, direction, u)
                            : volume.cuts(
                                    u, high.coordinate(u) * size, (high.coordinate(u) + 1) * size);
            float[] vs =
                    volume == null
                            ? cuts(high, size, direction, v)
                            : volume.cuts(
                                    v, high.coordinate(v) * size, (high.coordinate(v) + 1) * size);
            int width = us.length - 1, height = vs.length - 1;
            byte[] low = lowBoundary(high, size, direction, us, vs, cancelled);
            byte[] mask = new byte[width * height];
            // 邻区即使拥有 voxel 实体，Low 的完整截面保持存在；共面原始 Low 已覆盖时复用原面。
            for (int i = 0; i < mask.length; i++) {
                if (volume == null) {
                    if ((low[i] & 7) == 5) mask[i] = 1;
                } else {
                    double[] point = new double[3];
                    point[axis] = (double) (high.coordinate(axis) + (direction & 1)) * size;
                    point[u] = ((double) us[i % width] + us[i % width + 1]) * .5;
                    point[v] = ((double) vs[i / width] + vs[i / width + 1]) * .5;
                    if ((low[i] & 2) == 0 && volume.beside(point, axis, (direction & 1) != 0))
                        mask[i] = 1;
                }
            }
            for (int y = 0; y < height; y++) {
                if (cancelled.getAsBoolean())
                    throw new java.util.concurrent.CancellationException();
                for (int x = 0; x < width; x++) {
                    byte value = mask[y * width + x];
                    if (value == 0) continue;
                    int w = 1, h = 1;
                    while (x + w < width && mask[y * width + x + w] == value) w++;
                    rows:
                    while (y + h < height) {
                        for (int dx = 0; dx < w; dx++)
                            if (mask[(y + h) * width + x + dx] != value) break rows;
                        h++;
                    }
                    for (int dy = 0; dy < h; dy++)
                        java.util.Arrays.fill(
                                mask, (y + dy) * width + x, (y + dy) * width + x + w, (byte) 0);
                    quad(
                            vertices,
                            high,
                            size,
                            direction,
                            us[x],
                            vs[y],
                            us[x + w] - us[x],
                            vs[y + h] - vs[y]);
                }
            }
            triangles[direction] = (vertices.size() / 14 - offsets[direction]) / 2;
        }
        return new Edges(vertices.toFloatArray(), offsets, triangles);
    }

    private void quad(
            FloatArrayList out,
            SceneLayout.Cell cell,
            int size,
            int direction,
            float x,
            float y,
            float width,
            float height) {
        int axis = direction / 2, u = (axis + 1) % 3, v = (axis + 2) % 3;
        int sign = (direction & 1) == 0 ? 1 : -1;
        float[] origin = {cell.x() * size, cell.y() * size, cell.z() * size};
        origin[axis] += (direction & 1) * size;
        origin[u] = x;
        origin[v] = y;
        float[] center = origin.clone();
        center[u] += width * .5F;
        center[v] += height * .5F;
        int face = closestFace(center);
        // 沿用最近 Low 面的程序化 LabPBR 区域；侧面独立建立正交帧和 POM 矩形中心。
        float minU = face < 0 ? 0 : faces[face + 12], minV = face < 0 ? 0 : faces[face + 14];
        float spanU =
                face < 0 ? 1 : (faces[face + 13] - minU) * Math.min(1, width / faces[face + 16]);
        float spanV =
                face < 0 ? 1 : (faces[face + 15] - minV) * Math.min(1, height / faces[face + 17]);
        spanU = Math.max(Math.ulp(minU) * 4, spanU);
        spanV = Math.max(Math.ulp(minV) * 4, spanV);
        int[] order = sign > 0 ? new int[] {0, 1, 2, 3} : new int[] {0, 3, 2, 1};
        for (int corner : order) {
            int cu = corner == 1 || corner == 2 ? 1 : 0, cv = corner >= 2 ? 1 : 0;
            for (int a = 0; a < 3; a++)
                out.add(origin[a] + (a == u ? cu * width : a == v ? cv * height : 0));
            out.add(minU + cu * spanU);
            out.add(minV + cv * spanV);
            for (int a = 0; a < 3; a++) out.add(a == axis ? sign : 0);
            for (int a = 0; a < 3; a++) out.add(a == u ? 1 : 0);
            out.add(-sign);
            out.add(minU + spanU * .5F);
            out.add(minV + spanV * .5F);
        }
    }

    private int closestFace(float[] point) {
        int cx = (int) Math.floor(point[0] / pageSize),
                cy = (int) Math.floor(point[1] / pageSize),
                cz = (int) Math.floor(point[2] / pageSize);
        int best = faces.length == 0 ? -1 : 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int z = cz - 1; z <= cz + 1; z++)
            for (int y = cy - 1; y <= cy + 1; y++)
                for (int x = cx - 1; x <= cx + 1; x++) {
                    IntArrayList candidates = pages.get(new SceneLayout.Cell(x, y, z));
                    if (candidates == null) continue;
                    for (int i = 0; i < candidates.size(); i++) {
                        int face = candidates.getInt(i);
                        double squared = 0;
                        for (int axis = 0; axis < 3; axis++) {
                            double delta =
                                    Math.max(
                                            0,
                                            Math.max(
                                                    min(face, axis) - point[axis],
                                                    point[axis] - max(face, axis)));
                            squared += delta * delta;
                        }
                        if (squared < distance) {
                            distance = squared;
                            best = face;
                        }
                    }
                }
        return best;
    }

    private float min(int face, int axis) {
        return Math.min(
                faces[face + axis], Math.min(faces[face + 3 + axis], faces[face + 6 + axis]));
    }

    private float max(int face, int axis) {
        return Math.max(
                faces[face + axis], Math.max(faces[face + 3 + axis], faces[face + 6 + axis]));
    }

    public record Edges(float[] vertices, int[] offsets, int[] triangles) {
        public long gpuBytes(int stride) {
            return (long) vertices.length / 14 * stride;
        }
    }

    private record Column(int axis, int u, int v) {}
}
