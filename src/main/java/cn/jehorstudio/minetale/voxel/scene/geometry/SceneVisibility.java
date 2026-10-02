package cn.jehorstudio.minetale.voxel.scene.geometry;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;

// 空页保留通路。六向连通关系来源限定为可靠遮挡，待确认空间继续允许可见性通过。
final class SceneVisibility {
    final int pageSize, side;
    final int[] origin, size;
    final long[] connections;
    final BitSet blockers;
    private final boolean unrestricted;
    private final int[] entered;
    private final byte[] frustumState;
    private final IntArrayFIFOQueue queue = new IntArrayFIFOQueue();

    SceneVisibility(int pageSize, int[] origin, int[] size, long[] connections, BitSet blockers) {
        this.pageSize = pageSize;
        this.side = pageSize / 2;
        this.origin = origin;
        this.size = size;
        this.connections = connections;
        this.blockers = blockers;
        unrestricted = Arrays.stream(connections).allMatch(mask -> mask == (1L << 36) - 1);
        entered = new int[connections.length];
        frustumState = new byte[connections.length];
    }

    static SceneVisibility build(
            SceneAsset.Page[] pages, int pageSize, SceneLowVolume low, SceneSurface surface)
            throws IOException {
        if (pageSize != 32) throw new IOException("静态连通图当前要求 32 米页");
        int[] origin = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
                end = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (var page : pages)
            for (int a = 0; a < 3; a++) {
                origin[a] = Math.min(origin[a], page.coordinate(a));
                end[a] = Math.max(end[a], page.coordinate(a));
            }
        int[] size = new int[3];
        for (int a = 0; a < 3; a++) size[a] = end[a] - origin[a] + 1;
        int count = capacity(size);
        long[] links = new long[count];
        BitSet blockers = new BitSet();
        SceneVisibility graph = new SceneVisibility(pageSize, origin, size, links, blockers);
        double[] box = new double[6];
        for (int page = 0; page < count; page++) {
            int[] p = graph.cell(page);
            for (int z = 0; z < 16; z++)
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++) {
                        box[0] = p[0] * 32.0 + x * 2;
                        box[1] = p[1] * 32.0 + y * 2;
                        box[2] = p[2] * 32.0 + z * 2;
                        for (int a = 0; a < 3; a++) box[a + 3] = box[a] + 2;
                        if (low.containsBox(box) && surface.certifiedInside(box))
                            blockers.set(page * 4096 + (z * 16 + y) * 16 + x);
                    }
            links[page] = connectivity(blockers, page * 4096, 16);
            if ((page & 511) == 0)
                System.out.println(
                        "visibility pages="
                                + page
                                + "/"
                                + count
                                + " blockers="
                                + blockers.cardinality());
        }
        return graph;
    }

    static long connectivity(BitSet blockers, int offset, int side) {
        int volume = side * side * side;
        BitSet visited = new BitSet(volume);
        int[] queue = new int[volume];
        long result = 0;
        for (int start = 0; start < volume; start++) {
            int x = start % side, y = start / side % side, z = start / (side * side);
            if (x != 0 && x != side - 1 && y != 0 && y != side - 1 && z != 0 && z != side - 1)
                continue;
            if (visited.get(start) || blockers.get(offset + start)) continue;
            int head = 0, tail = 0, faces = 0;
            queue[tail++] = start;
            visited.set(start);
            while (head < tail) {
                int at = queue[head++];
                int[] p = {at % side, at / side % side, at / (side * side)};
                for (int axis = 0; axis < 3; axis++)
                    for (int sign = 0; sign < 2; sign++) {
                        int direction = axis * 2 + sign;
                        if (p[axis] == (sign == 0 ? 0 : side - 1)) {
                            faces |= 1 << direction;
                            continue;
                        }
                        int step = axis == 0 ? 1 : axis == 1 ? side : side * side;
                        int neighbor = at + (sign == 0 ? -step : step);
                        if (!visited.get(neighbor) && !blockers.get(offset + neighbor)) {
                            visited.set(neighbor);
                            queue[tail++] = neighbor;
                        }
                    }
            }
            for (int a = 0; a < 6; a++)
                if ((faces & (1 << a)) != 0) result |= (long) faces << (a * 6);
        }
        return result;
    }

    BitSet visible(double x, double y, double z, SceneIndex.BoundsTest frustum) {
        // 全页六向互通时，该粒度的遮挡信息为空，直接使用空间树。
        if (unrestricted) return null;
        Arrays.fill(entered, 0);
        Arrays.fill(frustumState, (byte) 0);
        queue.clear();
        BitSet visible = new BitSet(connections.length);
        int px = (int) Math.floor(x / pageSize),
                py = (int) Math.floor(y / pageSize),
                pz = (int) Math.floor(z / pageSize);
        int start = index(px, py, pz);
        if (start >= 0) {
            int vx = (int) Math.floor((x - px * (double) pageSize) / 2),
                    vy = (int) Math.floor((y - py * (double) pageSize) / 2),
                    vz = (int) Math.floor((z - pz * (double) pageSize) / 2);
            if (blockers.get(start * side * side * side + (vz * side + vy) * side + vx))
                return null;
            visible.set(start);
            // 从相机页的所有开口保守出发，复用页级 flood fill 结果。
            for (int d = 0; d < 6; d++) enqueue(start, d);
            frustumState[start] = 1;
        } else {
            double[] camera = {x, y, z};
            for (int axis = 0; axis < 3; axis++) {
                int u = (axis + 1) % 3, v = (axis + 2) % 3;
                for (int sign = 0; sign < 2; sign++) {
                    double plane =
                            (origin[axis] + (sign == 0 ? 0 : size[axis])) * (double) pageSize;
                    if (sign == 0 ? camera[axis] >= plane : camera[axis] < plane) continue;
                    int[] p = origin.clone();
                    p[axis] += sign == 0 ? 0 : size[axis] - 1;
                    for (int b = 0; b < size[v]; b++)
                        for (int a = 0; a < size[u]; a++) {
                            p[u] = origin[u] + a;
                            p[v] = origin[v] + b;
                            int id = index(p[0], p[1], p[2]);
                            if (inFrustum(id, frustum)) enqueue(id, axis * 2 + sign);
                        }
                }
            }
        }
        while (!queue.isEmpty()) {
            int state = queue.dequeueInt(), page = state / 6, entry = state % 6;
            visible.set(page);
            int exits = (int) (connections[page] >>> (entry * 6)) & 63;
            int[] p = cell(page);
            for (int d = 0; d < 6; d++)
                if ((exits & (1 << d)) != 0) {
                    int axis = d / 2;
                    p[axis] += (d & 1) == 0 ? -1 : 1;
                    int next = index(p[0], p[1], p[2]);
                    p[axis] -= (d & 1) == 0 ? -1 : 1;
                    if (next >= 0 && inFrustum(next, frustum)) enqueue(next, d ^ 1);
                }
        }
        return visible;
    }

    private void enqueue(int page, int direction) {
        if ((entered[page] & (1 << direction)) == 0) {
            entered[page] |= 1 << direction;
            queue.enqueue(page * 6 + direction);
        }
    }

    private boolean inFrustum(int id, SceneIndex.BoundsTest frustum) {
        if (frustumState[id] == 0) {
            int[] p = cell(id);
            double x = p[0] * (double) pageSize,
                    y = p[1] * (double) pageSize,
                    z = p[2] * (double) pageSize;
            frustumState[id] =
                    (byte)
                            (frustum.intersects(x, y, z, x + pageSize, y + pageSize, z + pageSize)
                                    ? 1
                                    : 2);
        }
        return frustumState[id] == 1;
    }

    boolean contains(BitSet visible, int x, int y, int z) {
        int id = index(x, y, z);
        return id >= 0 && visible.get(id);
    }

    private int[] cell(int id) {
        return new int[] {
            id % size[0] + origin[0],
            id / size[0] % size[1] + origin[1],
            id / (size[0] * size[1]) + origin[2]
        };
    }

    private int index(int x, int y, int z) {
        x -= origin[0];
        y -= origin[1];
        z -= origin[2];
        return x < 0 || y < 0 || z < 0 || x >= size[0] || y >= size[1] || z >= size[2]
                ? -1
                : (z * size[1] + y) * size[0] + x;
    }

    long bytes() {
        return connections.length * 13L + blockers.toLongArray().length * 8L;
    }

    void write(DataOutput out) throws IOException {
        for (int v : origin) out.writeInt(v);
        for (int v : size) out.writeInt(v);
        for (long v : connections) out.writeLong(v);
        long[] words = blockers.toLongArray();
        out.writeInt(words.length);
        for (long word : words) out.writeLong(word);
    }

    static SceneVisibility read(DataInput in, int pageSize) throws IOException {
        if (pageSize != 32) throw new IOException("静态连通图页尺寸错误");
        int[] origin = new int[3], size = new int[3];
        for (int a = 0; a < 3; a++) {
            origin[a] = in.readInt();
            if (Math.abs((long) origin[a]) > 1_000_000) throw new IOException("连通图原点越界");
        }
        for (int a = 0; a < 3; a++) size[a] = in.readInt();
        int count = capacity(size);
        long[] links = new long[count];
        for (int i = 0; i < count; i++) {
            links[i] = in.readLong();
            if ((links[i] >>> 36) != 0) throw new IOException("连通掩码越界");
        }
        int n = SceneIndex.length(in, count * 64);
        long[] words = new long[n];
        for (int i = 0; i < n; i++) words[i] = in.readLong();
        return new SceneVisibility(pageSize, origin, size, links, BitSet.valueOf(words));
    }

    private static int capacity(int[] size) throws IOException {
        long count = 1;
        for (int n : size) {
            if (n < 1) throw new IOException("连通图维度越界");
            count *= n;
            // 每页占用 4096 个 BitSet 索引，索引必须能由非负 int 表示。
            if (count > Integer.MAX_VALUE / 4096) throw new IOException("连通图超出 BitSet 索引范围");
        }
        return (int) count;
    }
}
