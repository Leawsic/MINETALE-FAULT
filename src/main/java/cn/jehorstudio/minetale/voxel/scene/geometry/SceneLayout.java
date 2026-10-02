package cn.jehorstudio.minetale.voxel.scene.geometry;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 发布包的空间页负责 IO；运行时沿细节砖边界裁切 Low，使每块可以独立接管覆盖。
public final class SceneLayout {
    public final int size;
    public final List<Brick> bricks;
    public final List<Brick> uploads;
    public final int[] parentOffsets, parentTriangles;
    public final int triangles;
    public final int vertexCount;
    // 全部为矩形时直接共用顺序矩形索引；任意三角形 Low 保留独立索引。
    public final int[] indices;

    public SceneLayout(SceneAsset asset, float[][] low) {
        int extent = Math.min(8, asset.pageSize());
        while (asset.pageSize() % extent != 0) extent--;
        size = extent;
        parentOffsets = new int[low.length];
        parentTriangles = new int[low.length];
        List<Brick> result = new ArrayList<>();
        List<Brick> uploadData = new ArrayList<>();
        int offset = 0;
        IntArrayList allIndices = new IntArrayList();
        boolean rectanglesOnly = true;
        for (var page : asset.pages()) {
            parentOffsets[page.id()] = offset;
            // High 就绪前的远页直接绘制原 Low，稳定帧沿用完整页三角形。
            float[] original = low[page.id()];
            parentTriangles[page.id()] = original.length / 42;
            if (original.length > 0) {
                Brick brick = pack(null, page, offset, original, allIndices);
                rectanglesOnly &= brick.vertices.length / 14 * 3 == brick.triangles() * 6;
                uploadData.add(brick);
                offset = Math.addExact(offset, brick.vertices.length / 14);
            }
        }
        for (var page : asset.pages()) {
            Map<Cell, FloatArrayList> pieces = partition(low[page.id()], size);
            for (var entry : pieces.entrySet()) {
                float[] vertices = entry.getValue().toFloatArray();
                Brick brick = pack(entry.getKey(), page, offset, vertices, allIndices);
                rectanglesOnly &= brick.vertices.length / 14 * 3 == brick.triangles() * 6;
                result.add(brick);
                uploadData.add(brick);
                offset = Math.addExact(offset, brick.vertices.length / 14);
            }
            low[page.id()] = null;
        }
        bricks = List.copyOf(result);
        uploads = List.copyOf(uploadData);
        triangles = allIndices.size() / 3;
        vertexCount = offset;
        indices = rectanglesOnly ? null : allIndices.toIntArray();
    }

    private static Brick pack(Cell cell, SceneAsset.Page page, int offset, float[] source, IntArrayList indices) {
        FloatArrayList vertices = new FloatArrayList();
        int firstIndex = indices.size();
        for (int start = 0; start < source.length;) {
            int base = offset + vertices.size() / 14;
            if (pairedRectangle(source, start)) {
                for (int corner : new int[]{0, 14, 28, 70}) vertices.addElements(vertices.size(), source, start + corner, 14);
                for (int corner : new int[]{0, 1, 2, 0, 2, 3}) indices.add(base + corner);
                start += 84;
            } else {
                vertices.addElements(vertices.size(), source, start, 42);
                for (int corner = 0; corner < 3; corner++) indices.add(base + corner);
                start += 42;
            }
        }
        return new Brick(cell, page, offset, vertices.toFloatArray(), firstIndex, (indices.size() - firstIndex) / 3);
    }

    static Map<Cell, FloatArrayList> partition(float[] vertices, int size) {
        return partition(vertices, size, null);
    }

    private static Map<Cell, FloatArrayList> partition(float[] vertices, int size, Cell target) {
        Map<Cell, FloatArrayList> result = new LinkedHashMap<>();
        for (int start = 0; start < vertices.length; ) {
            boolean rectangle = pairedRectangle(vertices, start);
            int[] corners = rectangle ? new int[] {0, 14, 28, 70} : new int[] {0, 14, 28};
            int[] lo = new int[3], hi = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                for (int corner : corners) {
                    min = Math.min(min, vertices[start + corner + axis]);
                    max = Math.max(max, vertices[start + corner + axis]);
                }
                lo[axis] = (int) Math.floor(min / size);
                // 零厚度平面归属单侧；跨砖三角形仅保留面积有效的邻片。
                hi[axis] = max == min ? lo[axis] : (int) Math.ceil(max / size) - 1;
                if (target != null) {
                    lo[axis] = Math.max(lo[axis], target.coordinate(axis));
                    hi[axis] = Math.min(hi[axis], target.coordinate(axis));
                }
            }
            for (int z = lo[2]; z <= hi[2]; z++)
                for (int y = lo[1]; y <= hi[1]; y++)
                    for (int x = lo[0]; x <= hi[0]; x++) {
                        Cell cell = new Cell(x, y, z);
                        List<float[]> polygon = new ArrayList<>(corners.length);
                        for (int corner : corners)
                            polygon.add(
                                    java.util.Arrays.copyOfRange(
                                            vertices, start + corner, start + corner + 14));
                        for (int axis = 0; axis < 3 && !polygon.isEmpty(); axis++) {
                            float min = cell.coordinate(axis) * size;
                            polygon = clip(polygon, axis, min, true);
                            polygon = clip(polygon, axis, min + size, false);
                        }
                        if (polygon.size() < 3) continue;
                        if (rectangle) {
                            // POM 从矩形角点和中心重建 UV；先裁四边面再三角化，UV 顶点沿矩形数据生成。
                            float minU = Float.POSITIVE_INFINITY,
                                    minV = Float.POSITIVE_INFINITY,
                                    maxU = Float.NEGATIVE_INFINITY,
                                    maxV = Float.NEGATIVE_INFINITY;
                            for (float[] v : polygon) {
                                minU = Math.min(minU, v[3]);
                                maxU = Math.max(maxU, v[3]);
                                minV = Math.min(minV, v[4]);
                                maxV = Math.max(maxV, v[4]);
                            }
                            for (float[] v : polygon) {
                                v[12] = (minU + maxU) * .5F;
                                v[13] = (minV + maxV) * .5F;
                            }
                        }
                        FloatArrayList output =
                                result.computeIfAbsent(cell, ignored -> new FloatArrayList());
                        for (int v = 1; v + 1 < polygon.size(); v++) {
                            float[] a = polygon.getFirst(),
                                    b = polygon.get(v),
                                    c = polygon.get(v + 1);
                            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                            double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
                            double nx = uy * vz - uz * vy,
                                    ny = uz * vx - ux * vz,
                                    nz = ux * vy - uy * vx;
                            if (nx * nx + ny * ny + nz * nz <= 1e-20) continue;
                            output.addElements(output.size(), a);
                            output.addElements(output.size(), b);
                            output.addElements(output.size(), c);
                        }
                    }
            start += rectangle ? 84 : 42;
        }
        result.values().removeIf(FloatArrayList::isEmpty);
        return result;
    }

    static boolean pairedRectangle(float[] vertices, int start) {
        if (start + 84 > vertices.length) return false;
        for (int k = 0; k < 14; k++)
            if (vertices[start + k] != vertices[start + 42 + k]
                    || vertices[start + 28 + k] != vertices[start + 56 + k]) return false;
        for (int k = 5; k < 14; k++)
            if (vertices[start + k] != vertices[start + 70 + k]) return false;
        // 矩形使用 012/023 三角形对；其余几何按三角形范围裁切。
        int mask = 0;
        for (int offset : new int[] {0, 14, 28, 70}) {
            int v = start + offset;
            mask |=
                    1
                            << ((vertices[v + 3] > vertices[start + 12] ? 1 : 0)
                                    | (vertices[v + 4] > vertices[start + 13] ? 2 : 0));
        }
        if (mask != 15) return false;
        int dimensions = 0, positions = 0;
        for (int axis = 0; axis < 5; axis++) {
            float lo = Math.min(vertices[start + axis], vertices[start + 28 + axis]);
            float hi = Math.max(vertices[start + axis], vertices[start + 28 + axis]);
            if (axis < 3 && lo != hi) dimensions++;
            if (axis >= 3 && lo == hi) return false;
            for (int offset : new int[] {14, 70})
                if (vertices[start + offset + axis] != lo && vertices[start + offset + axis] != hi)
                    return false;
        }
        if (dimensions != 2) return false;
        for (int offset : new int[] {0, 14, 28, 70}) {
            int corner = 0;
            for (int axis = 0; axis < 3; axis++)
                if (vertices[start + offset + axis]
                        > Math.min(vertices[start + axis], vertices[start + 28 + axis]))
                    corner |= 1 << axis;
            positions |= 1 << corner;
        }
        return Integer.bitCount(positions) == 4;
    }

    private static List<float[]> clip(List<float[]> input, int axis, float edge, boolean lower) {
        List<float[]> output = new ArrayList<>();
        if (input.isEmpty()) return output;
        float[] previous = input.getLast();
        boolean previousInside = lower ? previous[axis] >= edge : previous[axis] <= edge;
        for (float[] current : input) {
            boolean inside = lower ? current[axis] >= edge : current[axis] <= edge;
            if (inside != previousInside) {
                double fraction =
                        (edge - previous[axis]) / (double) (current[axis] - previous[axis]);
                float[] intersection = new float[14];
                for (int k = 0; k < 14; k++)
                    intersection[k] = (float) (previous[k] + fraction * (current[k] - previous[k]));
                intersection[axis] = edge;
                output.add(intersection);
            }
            if (inside) output.add(current);
            previous = current;
            previousInside = inside;
        }
        return output;
    }

    public record Cell(int x, int y, int z) {
        public int coordinate(int axis) {
            return axis == 0 ? x : axis == 1 ? y : z;
        }

        public Cell neighbor(int direction) {
            int axis = direction / 2, sign = (direction & 1) == 0 ? -1 : 1;
            return new Cell(
                    x + (axis == 0 ? sign : 0),
                    y + (axis == 1 ? sign : 0),
                    z + (axis == 2 ? sign : 0));
        }
    }

    public record Brick(Cell cell, SceneAsset.Page parent, int offset, float[] vertices, int firstIndex, int triangles) {}
}
