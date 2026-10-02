package cn.jehorstudio.minetale.voxel.scene.geometry;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntConsumer;

// 维护固定几何的紧凑查询树。离线索引直接还原，运行时曲面片按中位数划分。
final class SceneBvh {
    // 调用方复用查询点、距离函数和结果，逐纹素材质归属查询不分配临时对象。
    int nearest(double[] point, java.util.function.IntToDoubleFunction distance, double[] best) {
        best[0] = Double.POSITIVE_INFINITY;
        best[1] = -1;
        if (nodes != 0) nearestSurface(0, point, distance, best);
        return (int) best[1];
    }

    private void nearestSurface(int node, double[] point,
            java.util.function.IntToDoubleFunction distance, double[] best) {
        if (distanceSquared(bounds, node * 6, point) > best[0]) return;
        if (left[node] < 0) {
            for (int i = ~left[node]; i < right[node]; i++) {
                int id = order[i];
                double d = distance.applyAsDouble(id);
                if (d < best[0] || d == best[0] && (best[1] < 0 || id < best[1])) {
                    best[0] = d;
                    best[1] = id;
                }
            }
        } else {
            int a = left[node], b = right[node];
            if (distanceSquared(bounds, a * 6, point) > distanceSquared(bounds, b * 6, point)) {
                int swap = a; a = b; b = swap;
            }
            nearestSurface(a, point, distance, best);
            nearestSurface(b, point, distance, best);
        }
    }

    int nearest(double[] point, double[][] boxes) {
        int[] found = {-1};
        double[] best = {Double.POSITIVE_INFINITY};
        if (nodes != 0) nearest(0, point, boxes, found, best);
        return found[0];
    }

    static double distanceSquared(double[] box, int offset, double[] point) {
        double distance = 0;
        for (int a = 0; a < 3; a++) {
            double delta =
                    Math.max(
                            0,
                            Math.max(box[offset + a] - point[a], point[a] - box[offset + a + 3]));
            distance += delta * delta;
        }
        return distance;
    }

    private void nearest(int node, double[] point, double[][] boxes, int[] found, double[] best) {
        if (distanceSquared(bounds, node * 6, point) > best[0]) return;
        if (left[node] < 0) {
            for (int i = ~left[node]; i < right[node]; i++) {
                int id = order[i];
                double distance = distanceSquared(boxes[id], 0, point);
                if (distance < best[0] || distance == best[0] && (found[0] < 0 || id < found[0])) {
                    best[0] = distance;
                    found[0] = id;
                }
            }
        } else {
            int a = left[node], b = right[node];
            if (distanceSquared(bounds, a * 6, point) > distanceSquared(bounds, b * 6, point)) {
                int swap = a;
                a = b;
                b = swap;
            }
            nearest(a, point, boxes, found, best);
            nearest(b, point, boxes, found, best);
        }
    }

    private double[] bounds;
    private int[] left, right;
    private final int[] order;
    private int nodes;

    @FunctionalInterface
    interface BoxTest {
        boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1);
    }

    SceneBvh(double[][] boxes) {
        int capacity = Math.max(1, boxes.length * 2);
        bounds = new double[capacity * 6];
        left = new int[capacity];
        right = new int[capacity];
        order = new int[boxes.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        if (boxes.length > 0) build(boxes, 0, boxes.length);
        bounds = Arrays.copyOf(bounds, nodes * 6);
        left = Arrays.copyOf(left, nodes);
        right = Arrays.copyOf(right, nodes);
    }

    private SceneBvh(int count, int nodes) {
        order = new int[count];
        this.nodes = nodes;
        bounds = new double[nodes * 6];
        left = new int[nodes];
        right = new int[nodes];
    }

    private int build(double[][] boxes, int from, int to) {
        int node = nodes++, offset = node * 6;
        Arrays.fill(bounds, offset, offset + 3, Double.POSITIVE_INFINITY);
        Arrays.fill(bounds, offset + 3, offset + 6, Double.NEGATIVE_INFINITY);
        for (int i = from; i < to; i++)
            for (int a = 0; a < 3; a++) {
                bounds[offset + a] = Math.min(bounds[offset + a], boxes[order[i]][a]);
                bounds[offset + a + 3] = Math.max(bounds[offset + a + 3], boxes[order[i]][a + 3]);
            }
        if (to - from <= 8) {
            left[node] = ~from;
            right[node] = to;
        } else {
            int axis = 0;
            for (int a = 1; a < 3; a++)
                if (bounds[offset + a + 3] - bounds[offset + a]
                        > bounds[offset + axis + 3] - bounds[offset + axis]) axis = a;
            int middle = (from + to) >>> 1;
            select(boxes, from, to - 1, middle, axis);
            left[node] = build(boxes, from, middle);
            right[node] = build(boxes, middle, to);
        }
        return node;
    }

    private void select(double[][] boxes, int lo, int hi, int target, int axis) {
        while (lo < hi) {
            int pivot = order[(lo + hi) >>> 1], a = lo, b = hi;
            while (a <= b) {
                while (compare(boxes, order[a], pivot, axis) < 0) a++;
                while (compare(boxes, order[b], pivot, axis) > 0) b--;
                if (a <= b) {
                    int swap = order[a];
                    order[a++] = order[b];
                    order[b--] = swap;
                }
            }
            if (target <= b) hi = b;
            else if (target >= a) lo = a;
            else return;
        }
    }

    private static int compare(double[][] boxes, int a, int b, int axis) {
        int result =
                Double.compare(
                        boxes[a][axis] + boxes[a][axis + 3], boxes[b][axis] + boxes[b][axis + 3]);
        return result == 0 ? Integer.compare(a, b) : result;
    }

    void visit(BoxTest test, IntConsumer visitor) {
        if (nodes != 0) visit(0, test, visitor);
    }

    private void visit(int node, BoxTest test, IntConsumer visitor) {
        int b = node * 6;
        if (!test.intersects(
                bounds[b],
                bounds[b + 1],
                bounds[b + 2],
                bounds[b + 3],
                bounds[b + 4],
                bounds[b + 5])) return;
        if (left[node] < 0)
            for (int i = ~left[node]; i < right[node]; i++) visitor.accept(order[i]);
        else {
            visit(left[node], test, visitor);
            visit(right[node], test, visitor);
        }
    }

    void box(double[] box, IntConsumer visitor) {
        visit(
                (x0, y0, z0, x1, y1, z1) ->
                        x0 <= box[3]
                                && y0 <= box[4]
                                && z0 <= box[5]
                                && x1 >= box[0]
                                && y1 >= box[1]
                                && z1 >= box[2],
                visitor);
    }

    boolean overlaps(double[] box) {
        return nodes != 0 && overlaps(0, box);
    }

    private boolean overlaps(int node, double[] box) {
        for (int a = 0; a < 3; a++)
            if (bounds[node * 6 + a] > box[a + 3] || bounds[node * 6 + a + 3] < box[a])
                return false;
        return left[node] < 0 || overlaps(left[node], box) || overlaps(right[node], box);
    }

    void ray(double x, double y, double z, IntConsumer visitor) {
        if (nodes != 0) ray(0, x, y, z, visitor);
    }

    private void ray(int node, double x, double y, double z, IntConsumer visitor) {
        int b = node * 6;
        if (!rayIntersects(
                x,
                y,
                z,
                bounds[b],
                bounds[b + 1],
                bounds[b + 2],
                bounds[b + 3],
                bounds[b + 4],
                bounds[b + 5])) return;
        if (left[node] < 0)
            for (int i = ~left[node]; i < right[node]; i++) visitor.accept(order[i]);
        else {
            ray(left[node], x, y, z, visitor);
            ray(right[node], x, y, z, visitor);
        }
    }

    interface RayTest {
        boolean crosses(int primitive, double x, double y, double z);
    }

    // 每砖需要大量射线判定，直接返回计数；单次判定沿用调用方测试对象和当前 BVH 工作数据。
    int crossings(double x, double y, double z, RayTest test) {
        return nodes == 0 ? 0 : crossings(0, x, y, z, test);
    }

    private int crossings(int node, double x, double y, double z, RayTest test) {
        int b = node * 6;
        if (!rayIntersects(
                x,
                y,
                z,
                bounds[b],
                bounds[b + 1],
                bounds[b + 2],
                bounds[b + 3],
                bounds[b + 4],
                bounds[b + 5])) return 0;
        if (left[node] >= 0)
            return crossings(left[node], x, y, z, test) + crossings(right[node], x, y, z, test);
        int count = 0;
        for (int i = ~left[node]; i < right[node]; i++)
            if (test.crosses(order[i], x, y, z)) count++;
        return count;
    }

    static boolean rayIntersects(
            double x,
            double y,
            double z,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1) {
        double near =
                Math.max(
                        0,
                        Math.max(
                                x0 - x,
                                Math.max(
                                        (y0 - y) / .3713906763541037, (z0 - z) / .52999894000318)));
        double far =
                Math.min(
                        x1 - x, Math.min((y1 - y) / .3713906763541037, (z1 - z) / .52999894000318));
        return far >= near;
    }

    boolean contains(double x, double y, double z) {
        return nodes > 0
                && x >= bounds[0]
                && y >= bounds[1]
                && z >= bounds[2]
                && x <= bounds[3]
                && y <= bounds[4]
                && z <= bounds[5];
    }

    long bytes() {
        return bounds.length * 8L + (left.length + right.length + order.length) * 4L;
    }

    void write(DataOutput out) throws IOException {
        out.writeInt(order.length);
        out.writeInt(nodes);
        for (int value : order) out.writeInt(value);
        for (int node = 0; node < nodes; node++) {
            for (int a = 0; a < 6; a++) out.writeDouble(bounds[node * 6 + a]);
            out.writeInt(left[node]);
            out.writeInt(right[node]);
        }
    }

    static SceneBvh read(DataInput in, double[][] boxes) throws IOException {
        int count = in.readInt(), nodes = in.readInt();
        if (count != boxes.length || nodes < 0 || nodes > count * 2 || (count == 0) != (nodes == 0))
            throw new IOException("静态 BVH 长度错误");
        SceneBvh tree = new SceneBvh(count, nodes);
        BitSet ids = new BitSet(count);
        for (int i = 0; i < count; i++) {
            int value = tree.order[i] = in.readInt();
            if (value < 0 || value >= count || ids.get(value)) throw new IOException("静态 BVH 排列错误");
            ids.set(value);
        }
        for (int node = 0; node < nodes; node++) {
            for (int a = 0; a < 6; a++) {
                double value = tree.bounds[node * 6 + a] = in.readDouble();
                if (!Double.isFinite(value)) throw new IOException("静态 BVH 非有限边界");
            }
            tree.left[node] = in.readInt();
            tree.right[node] = in.readInt();
        }
        BitSet visited = new BitSet(nodes), covered = new BitSet(count);
        if (nodes > 0) tree.validate(0, boxes, visited, covered, 0);
        if (visited.cardinality() != nodes || covered.cardinality() != count)
            throw new IOException("静态 BVH 孤立记录");
        return tree;
    }

    private void validate(int node, double[][] boxes, BitSet visited, BitSet covered, int depth)
            throws IOException {
        if (node < 0 || node >= nodes || visited.get(node) || depth > 64)
            throw new IOException("静态 BVH 拓扑错误");
        visited.set(node);
        for (int a = 0; a < 3; a++)
            if (bounds[node * 6 + a] > bounds[node * 6 + a + 3])
                throw new IOException("静态 BVH 反向边界");
        if (left[node] < 0) {
            int from = ~left[node], to = right[node];
            if (from < 0 || to <= from || to > order.length || to - from > 8)
                throw new IOException("静态 BVH 叶范围错误");
            for (int i = from; i < to; i++) {
                if (covered.get(i)) throw new IOException("静态 BVH 重叠叶范围");
                covered.set(i);
                for (int a = 0; a < 3; a++)
                    if (boxes[order[i]][a] < bounds[node * 6 + a]
                            || boxes[order[i]][a + 3] > bounds[node * 6 + a + 3])
                        throw new IOException("静态 BVH 未包围来源");
            }
        } else
            for (int child : new int[] {left[node], right[node]}) {
                validate(child, boxes, visited, covered, depth + 1);
                for (int a = 0; a < 3; a++)
                    if (bounds[child * 6 + a] < bounds[node * 6 + a]
                            || bounds[child * 6 + a + 3] > bounds[node * 6 + a + 3])
                        throw new IOException("静态 BVH 未包围子树");
            }
    }
}
