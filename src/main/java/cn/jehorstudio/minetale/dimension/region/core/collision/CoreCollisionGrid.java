package cn.jehorstudio.minetale.dimension.region.core.collision;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * Core 放置的方块碰撞索引，两类表达并存：
 *
 * <ul>
 *   <li>壳盒：轴对齐网格面沿实体侧挤出的薄盒（0.1m），与可见面完全齐平，主体固定网格使用；</li>
 *   <li>占据格：0.5 米单元的稀疏 32³ section 位图，与渲染体素同格，部件与体素层使用。</li>
 * </ul>
 *
 * <p>构建在后台线程完成后发布，此后只读，不再修改。
 * 单元坐标按放置原点（方块整坐标）换算到 scene-local 空间；查询接受世界坐标。
 */
public final class CoreCollisionGrid {
    public static final float CELL = 0.5F;
    /** 轴对齐面壳的挤出厚度。 */
    public static final double SHELL = 0.1;
    private static final int SECTION_BITS = 5, SECTION_SIZE = 32, SECTION_MASK = 31;

    private final int originX, originY, originZ;
    private final Long2ObjectMap<BitSet> sections = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<List<AABB>> shellSections = new Long2ObjectOpenHashMap<>();
    private final List<AABB> shells = new ArrayList<>();
    private final int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    private final int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    private double shellMinX = Double.POSITIVE_INFINITY, shellMinY = Double.POSITIVE_INFINITY,
            shellMinZ = Double.POSITIVE_INFINITY;
    private double shellMaxX = Double.NEGATIVE_INFINITY, shellMaxY = Double.NEGATIVE_INFINITY,
            shellMaxZ = Double.NEGATIVE_INFINITY;
    private long cells;

    CoreCollisionGrid(int originX, int originY, int originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public long cells() {
        return cells;
    }

    public int shells() {
        return shells.size();
    }

    public int sections() {
        return sections.size();
    }

    /** 诊断描述：占据量与 scene-local 包围区间（世界坐标）。 */
    public String describe() {
        if (cells == 0 && shells.isEmpty()) return "cells=0 shells=0";
        StringBuilder text = new StringBuilder("cells=").append(cells).append(" shells=").append(shells.size());
        if (cells > 0) text.append(String.format(
                Locale.ROOT,
                " cells-world [%.1f %.1f %.1f]-[%.1f %.1f %.1f]",
                originX + lo[0] * (double) CELL,
                originY + lo[1] * (double) CELL,
                originZ + lo[2] * (double) CELL,
                originX + (hi[0] + 1) * (double) CELL,
                originY + (hi[1] + 1) * (double) CELL,
                originZ + (hi[2] + 1) * (double) CELL));
        if (!shells.isEmpty()) text.append(String.format(
                Locale.ROOT,
                " shells-world [%.1f %.1f %.1f]-[%.1f %.1f %.1f]",
                shellMinX, shellMinY, shellMinZ, shellMaxX, shellMaxY, shellMaxZ));
        return text.toString();
    }

    // ---- 构建期写入（builder 线程独占） ----

    void markSceneCell(int x, int y, int z) {
        long key = sectionKey(x >> SECTION_BITS, y >> SECTION_BITS, z >> SECTION_BITS);
        BitSet bits = sections.get(key);
        if (bits == null) sections.put(key, bits = new BitSet(SECTION_SIZE * SECTION_SIZE * SECTION_SIZE));
        int bit = localBit(x, y, z);
        if (!bits.get(bit)) {
            bits.set(bit);
            cells++;
            int[] p = {x, y, z};
            for (int a = 0; a < 3; a++) {
                if (p[a] < lo[a]) lo[a] = p[a];
                if (p[a] > hi[a]) hi[a] = p[a];
            }
        }
    }

    /** 添加轴对齐面壳（世界坐标薄盒），按覆盖的 cell section 分桶供查询。 */
    void addShell(AABB box) {
        shells.add(box);
        shellMinX = Math.min(shellMinX, box.minX);
        shellMinY = Math.min(shellMinY, box.minY);
        shellMinZ = Math.min(shellMinZ, box.minZ);
        shellMaxX = Math.max(shellMaxX, box.maxX);
        shellMaxY = Math.max(shellMaxY, box.maxY);
        shellMaxZ = Math.max(shellMaxZ, box.maxZ);
        int x0 = worldSection(box.minX, 0), x1 = worldSection(box.maxX, 0);
        int y0 = worldSection(box.minY, 1), y1 = worldSection(box.maxY, 1);
        int z0 = worldSection(box.minZ, 2), z1 = worldSection(box.maxZ, 2);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    long key = sectionKey(x, y, z);
                    List<AABB> bucket = shellSections.get(key);
                    if (bucket == null) shellSections.put(key, bucket = new ArrayList<>(4));
                    bucket.add(box);
                }
    }

    // ---- 查询（只读，任意线程） ----

    private boolean solidCell(int x, int y, int z) {
        BitSet bits = sections.get(
                sectionKey(x >> SECTION_BITS, y >> SECTION_BITS, z >> SECTION_BITS));
        return bits != null && bits.get(localBit(x, y, z));
    }

    /** 包围盒整体粗测；不相交时调用方可以直接跳过所有逐项查询。 */
    public boolean intersectsWorld(AABB box) {
        if (cells > 0 && cellRangeIntersects(box)) return true;
        return !shells.isEmpty()
                && box.intersects(shellMinX, shellMinY, shellMinZ, shellMaxX, shellMaxY, shellMaxZ);
    }

    /** scene-local 坐标占据查询（诊断与探针用）。 */
    public boolean solidScene(double x, double y, double z) {
        return solidCell(
                (int) Math.floor(x / CELL), (int) Math.floor(y / CELL), (int) Math.floor(z / CELL));
    }

    /**
     * 收集碰撞盒：占据格按 Y/Z 行沿 X 合并连续段；壳盒原样输出。
     *
     * @return 命中数量；0 表示没有碰撞
     */
    public int collectWorld(AABB box, List<AABB> shapes) {
        int found = 0;
        if (cells > 0 && cellRangeIntersects(box)) {
            int x0 = Math.max(cellOf(box.min(Direction.Axis.X), 0), lo[0]);
            int x1 = Math.min(cellOf(box.max(Direction.Axis.X), 0), hi[0]);
            int y0 = Math.max(cellOf(box.min(Direction.Axis.Y), 1), lo[1]);
            int y1 = Math.min(cellOf(box.max(Direction.Axis.Y), 1), hi[1]);
            int z0 = Math.max(cellOf(box.min(Direction.Axis.Z), 2), lo[2]);
            int z1 = Math.min(cellOf(box.max(Direction.Axis.Z), 2), hi[2]);
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++) {
                    int run = Integer.MIN_VALUE;
                    for (int x = x0; x <= x1 + 1; x++) {
                        boolean solid = x <= x1 && solidCell(x, y, z);
                        if (solid && run == Integer.MIN_VALUE) run = x;
                        else if (!solid && run != Integer.MIN_VALUE) {
                            shapes.add(sceneCellBox(run, x - 1, y, z));
                            found++;
                            run = Integer.MIN_VALUE;
                        }
                    }
                }
        }
        if (!shells.isEmpty()) found += collectShells(box, shapes);
        return found;
    }

    private int collectShells(AABB box, List<AABB> shapes) {
        int x0 = worldSection(box.minX, 0), x1 = worldSection(box.maxX, 0);
        int y0 = worldSection(box.minY, 1), y1 = worldSection(box.maxY, 1);
        int z0 = worldSection(box.minZ, 2), z1 = worldSection(box.maxZ, 2);
        int found = 0;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    List<AABB> bucket = shellSections.get(sectionKey(x, y, z));
                    if (bucket == null) continue;
                    for (AABB shell : bucket)
                        // 跨多 section 的壳会重复入桶；输出去重。
                        if (shell.intersects(box) && !shapes.contains(shell)) {
                            shapes.add(shell);
                            found++;
                        }
                }
        return found;
    }

    /**
     * 实体碰撞注入的服务端/客户端共用收口：命中时把碰撞盒包装成 VoxelShape 并入
     * original，无命中原样返回。两端 mixin 各自完成等级/世界匹配后调用本方法。
     */
    public List<VoxelShape> appendShapes(AABB box, List<VoxelShape> original) {
        if (!intersectsWorld(box)) return original;
        List<AABB> found = new ArrayList<>();
        collectWorld(box, found);
        if (found.isEmpty()) return original;
        List<VoxelShape> withCore = new ArrayList<>(original.size() + found.size());
        withCore.addAll(original);
        for (AABB aabb : found) withCore.add(Shapes.create(aabb));
        return withCore;
    }

    /** 射线命中：命中盒、入射面（朝向射线来侧）与从射线起点计的世界距离。 */
    public record Hit(AABB box, Direction face, double distance) {}

    private static Direction entryFace(int axis, boolean stepPositive) {
        if (axis == 0) return stepPositive ? Direction.WEST : Direction.EAST;
        if (axis == 1) return stepPositive ? Direction.DOWN : Direction.UP;
        return stepPositive ? Direction.NORTH : Direction.SOUTH;
    }

    /**
     * 沿世界坐标射线求首个命中：占据格做单元 DDA，壳盒做线性 slab 测试，取最近。
     *
     * @param dx 单位方向
     */
    public Hit raycastWorld(double px, double py, double pz, double dx, double dy, double dz, double maxDistance) {
        Hit best = null;
        if (cells > 0) best = raycastCells(px, py, pz, dx, dy, dz, maxDistance);
        if (!shells.isEmpty()) {
            double limit = best == null ? maxDistance : best.distance();
            double[] point = new double[3];
            int[] entryAxis = new int[1];
            for (AABB shell : shells) {
                double t = rayBox(px, py, pz, dx, dy, dz, shell, limit, point, entryAxis);
                if (t >= 0) {
                    limit = t;
                    boolean positive = entryAxis[0] == 0 ? dx > 0 : entryAxis[0] == 1 ? dy > 0 : dz > 0;
                    best = new Hit(shellTile(shell, point), entryFace(entryAxis[0], positive), t);
                }
            }
        }
        return best;
    }

    /**
     * 面壳经贪婪合并可能横跨数百米；选择框取命中点所在的 1×1m 局部格块（薄轴沿用壳的厚度），
     * 而不是整个合并面。
     */
    private static AABB shellTile(AABB shell, double[] point) {
        double ex = shell.maxX - shell.minX, ey = shell.maxY - shell.minY, ez = shell.maxZ - shell.minZ;
        int axis = ex <= ey && ex <= ez ? 0 : ey <= ez ? 1 : 2;
        double[] min = {shell.minX, shell.minY, shell.minZ}, max = {shell.maxX, shell.maxY, shell.maxZ};
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        min[u] = Math.floor(point[u]);
        max[u] = min[u] + 1;
        min[v] = Math.floor(point[v]);
        max[v] = min[v] + 1;
        return new AABB(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private Hit raycastCells(double px, double py, double pz, double dx, double dy, double dz, double maxDistance) {
        // 单元空间：1 单位 = 1 格；Amanatides-Woo 逐步推进。travelled 以米计（方向已归一化）。
        double fx = (px - originX) / CELL, fy = (py - originY) / CELL, fz = (pz - originZ) / CELL;
        int x = (int) Math.floor(fx), y = (int) Math.floor(fy), z = (int) Math.floor(fz);
        double sx = dx / CELL, sy = dy / CELL, sz = dz / CELL;
        if (sx == 0 && sy == 0 && sz == 0) return null;
        int stepX = sx > 0 ? 1 : -1, stepY = sy > 0 ? 1 : -1, stepZ = sz > 0 ? 1 : -1;
        double tMaxX = boundary(fx, sx, stepX), tMaxY = boundary(fy, sy, stepY), tMaxZ = boundary(fz, sz, stepZ);
        double tDeltaX = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / sx);
        double tDeltaY = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / sy);
        double tDeltaZ = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / sz);
        double travelled = 0;
        Direction face = Direction.UP;
        // 守卫值远超最大距离内的单元跨数，仅防非法方向死循环。
        for (int guard = 0; guard < 8192; guard++) {
            if (travelled > maxDistance) return null;
            if (solidCell(x, y, z)) return new Hit(sceneCellBox(x, x, y, z), face, travelled);
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                travelled = tMaxX;
                tMaxX += tDeltaX;
                x += stepX;
                face = entryFace(0, stepX > 0);
            } else if (tMaxY < tMaxZ) {
                travelled = tMaxY;
                tMaxY += tDeltaY;
                y += stepY;
                face = entryFace(1, stepY > 0);
            } else {
                travelled = tMaxZ;
                tMaxZ += tDeltaZ;
                z += stepZ;
                face = entryFace(2, stepZ > 0);
            }
        }
        return null;
    }

    /** slab 测试；命中时把入点与入射轴写入 point/axis，返回入射距离；起点在盒内或未命中返回 -1。 */
    private static double rayBox(
            double px, double py, double pz, double dx, double dy, double dz,
            AABB box, double limit, double[] point, int[] axis) {
        double tMin = 0, tMax = limit;
        int entry = -1;
        double[] origin = {px, py, pz}, direction = {dx, dy, dz};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        for (int a = 0; a < 3; a++) {
            if (direction[a] == 0) {
                if (origin[a] < min[a] || origin[a] > max[a]) return -1;
                continue;
            }
            double t1 = (min[a] - origin[a]) / direction[a], t2 = (max[a] - origin[a]) / direction[a];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tMin) {
                tMin = t1;
                entry = a;
            }
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        if (tMin <= 1e-6 || entry < 0) return -1;
        axis[0] = entry;
        for (int a = 0; a < 3; a++) point[a] = origin[a] + tMin * direction[a];
        return tMin;
    }

    private static double boundary(double f, double s, int step) {
        if (s == 0) return Double.POSITIVE_INFINITY;
        double next = step > 0 ? Math.floor(f) + 1 : Math.floor(f);
        return (next - f) / s;
    }

    private boolean cellRangeIntersects(AABB box) {
        return cellOf(box.max(Direction.Axis.X), 0) >= lo[0]
                && cellOf(box.min(Direction.Axis.X), 0) <= hi[0]
                && cellOf(box.max(Direction.Axis.Y), 1) >= lo[1]
                && cellOf(box.min(Direction.Axis.Y), 1) <= hi[1]
                && cellOf(box.max(Direction.Axis.Z), 2) >= lo[2]
                && cellOf(box.min(Direction.Axis.Z), 2) <= hi[2];
    }

    private AABB sceneCellBox(int x0, int x1, int y, int z) {
        return new AABB(
                originX + x0 * (double) CELL,
                originY + y * (double) CELL,
                originZ + z * (double) CELL,
                originX + (x1 + 1) * (double) CELL,
                originY + (y + 1) * (double) CELL,
                originZ + (z + 1) * (double) CELL);
    }

    private int cellOf(double world, int axis) {
        double origin = axis == 0 ? originX : axis == 1 ? originY : originZ;
        return (int) Math.floor((world - origin) / CELL);
    }

    private int worldSection(double world, int axis) {
        double origin = axis == 0 ? originX : axis == 1 ? originY : originZ;
        return (int) Math.floor((world - origin) / ((double) SECTION_SIZE * CELL));
    }

    // 21 位带偏置编码可逆（±1M section 覆盖 ±16M 单元），供存档还原。
    private static long sectionKey(int x, int y, int z) {
        return ((long) (x + 0x100000) << 42) | ((long) (y + 0x100000) << 21) | (long) (z + 0x100000);
    }

    private static int localBit(int x, int y, int z) {
        return ((x & SECTION_MASK) << SECTION_BITS | (y & SECTION_MASK)) << SECTION_BITS | (z & SECTION_MASK);
    }

    private static final int STORE_MAGIC = 0x4d544347, STORE_VERSION = 1;

    /** 序列化占据格与面壳；服务端写入地图数据、客户端写本地缓存共用该格式。 */
    public void write(DataOutput out) throws IOException {
        out.writeInt(STORE_MAGIC);
        out.writeInt(STORE_VERSION);
        out.writeInt(originX);
        out.writeInt(originY);
        out.writeInt(originZ);
        out.writeLong(cells);
        out.writeInt(sections.size());
        for (var entry : sections.long2ObjectEntrySet()) {
            out.writeLong(entry.getLongKey());
            byte[] bits = entry.getValue().toByteArray();
            out.writeInt(bits.length);
            out.write(bits);
        }
        out.writeInt(shells.size());
        for (AABB shell : shells) {
            out.writeDouble(shell.minX);
            out.writeDouble(shell.minY);
            out.writeDouble(shell.minZ);
            out.writeDouble(shell.maxX);
            out.writeDouble(shell.maxY);
            out.writeDouble(shell.maxZ);
        }
    }

    /** 还原 write 的内容；原点与放置不符或格式异常抛 IOException，由调用方回退重建。 */
    public static CoreCollisionGrid read(DataInput in, int expectedX, int expectedY, int expectedZ) throws IOException {
        if (in.readInt() != STORE_MAGIC || in.readInt() != STORE_VERSION)
            throw new IOException("Core 碰撞存档版本无效");
        int ox = in.readInt(), oy = in.readInt(), oz = in.readInt();
        if (ox != expectedX || oy != expectedY || oz != expectedZ)
            throw new IOException("Core 碰撞存档原点不匹配");
        long cellCount = in.readLong();
        int sectionCount = in.readInt();
        if (cellCount < 0 || cellCount > 1L << 33 || sectionCount < 0 || sectionCount > 1 << 20)
            throw new IOException("Core 碰撞存档规模越界");
        CoreCollisionGrid grid = new CoreCollisionGrid(ox, oy, oz);
        for (int i = 0; i < sectionCount; i++) {
            long key = in.readLong();
            int length = in.readInt();
            // 32³ section = 32768 位，.toByteArray 至多 4096 字节。
            if (length <= 0 || length > 4096) throw new IOException("Core 碰撞存档 section 越界");
            byte[] bits = new byte[length];
            in.readFully(bits);
            int sx = (int) (key >> 42) - 0x100000,
                    sy = (int) ((key >> 21) & 0x1FFFFF) - 0x100000,
                    sz = (int) (key & 0x1FFFFF) - 0x100000;
            if (key >>> 42 != sx + 0x100000
                    || (key >> 21 & 0x1FFFFF) != sy + 0x100000
                    || (key & 0x1FFFFF) != sz + 0x100000)
                throw new IOException("Core 碰撞存档 section 键越界");
            var set = BitSet.valueOf(bits);
            for (int bit = set.nextSetBit(0); bit >= 0; bit = set.nextSetBit(bit + 1)) {
                int x = (sx << SECTION_BITS) + (bit >> 10),
                        y = (sy << SECTION_BITS) + ((bit >> SECTION_BITS) & SECTION_MASK),
                        z = (sz << SECTION_BITS) + (bit & SECTION_MASK);
                grid.markSceneCell(x, y, z);
            }
        }
        int shellCount = in.readInt();
        if (shellCount < 0 || shellCount > 1 << 20) throw new IOException("Core 碰撞存档壳数越界");
        for (int i = 0; i < shellCount; i++)
            grid.addShell(new AABB(
                    in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readDouble(), in.readDouble(), in.readDouble()));
        if (grid.cells != cellCount) throw new IOException("Core 碰撞存档计数不符");
        return grid;
    }
}
