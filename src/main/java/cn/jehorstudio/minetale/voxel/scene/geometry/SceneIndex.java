package cn.jehorstudio.minetale.voxel.scene.geometry;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.IntConsumer;

// 空间索引拥有页、砖和来源关联；截面来自导入资产或当前生成的基础 LOD。
public final class SceneIndex {
    static final int MAGIC = 0x4d545349, VERSION = 2;
    private int encodingVersion = VERSION;
    final int pageSize, brickSize;
    final SceneAsset.Page[] pages;
    final Brick[] bricks;
    final int[][] pageBricks;
    final SceneBvh pageTree;
    final SceneLowVolume low;
    final byte[] sourceDigest, lowDigest;
    final SceneVisibility visibility;
    private volatile byte[] preparedSurface;
    private final Map<SceneLayout.Cell, Integer> brickIds = new HashMap<>();
    private final SceneAsset asset;
    private int cachedCapGroup = -1;
    private volatile byte[] cachedCaps;
    private SceneSeams generatedSeams;
    public static SceneIndex generated(
            SceneAsset asset,
            SceneLayout layout,
            SceneSurface surface,
            SceneSeams seams,
            java.util.function.BooleanSupplier cancelled)
            throws IOException {
        var sources = surface.associations(layout.size, cancelled);
        var lows = new HashMap<SceneLayout.Cell, SceneLayout.Brick>();
        for (var brick : layout.bricks) {
            if (lows.putIfAbsent(brick.cell(), brick) != null)
                throw new IOException("基础 LOD 的砖覆盖重复: " + brick.cell());
            sources.putIfAbsent(brick.cell(), new int[0]);
        }
        var pages = new ArrayList<>(asset.pages());
        var parents = new HashMap<SceneLayout.Cell, Integer>();
        for (var page : pages)
            parents.put(
                    new SceneLayout.Cell(
                            page.coordinate(0), page.coordinate(1), page.coordinate(2)),
                    page.id());
        var ordered = new ArrayList<>(sources.keySet());
        ordered.sort(
                Comparator.comparingInt(SceneLayout.Cell::z)
                        .thenComparingInt(SceneLayout.Cell::y)
                        .thenComparingInt(SceneLayout.Cell::x));
        Brick[] bricks = new Brick[ordered.size()];
        var ids = new ArrayList<it.unimi.dsi.fastutil.ints.IntArrayList>();
        for (int i = 0; i < pages.size(); i++)
            ids.add(new it.unimi.dsi.fastutil.ints.IntArrayList());
        for (int i = 0; i < bricks.length; i++) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            var cell = ordered.get(i);
            var parentCell =
                    new SceneLayout.Cell(
                            Math.floorDiv(cell.x() * layout.size, asset.pageSize()),
                            Math.floorDiv(cell.y() * layout.size, asset.pageSize()),
                            Math.floorDiv(cell.z() * layout.size, asset.pageSize()));
            Integer parent = parents.get(parentCell);
            if (parent == null) {
                parent = pages.size();
                parents.put(parentCell, parent);
                pages.add(
                        new SceneAsset.Page(
                                parent,
                                new int[] {parentCell.x(), parentCell.y(), parentCell.z()},
                                0,
                                0));
                ids.add(new it.unimi.dsi.fastutil.ints.IntArrayList());
            }
            var low = lows.get(cell);
            bricks[i] =
                    new Brick(
                            cell,
                            parent,
                            low == null ? 0 : low.offset(),
                            low == null ? 0 : low.triangles(),
                            sources.get(cell),
                            0,
                            new byte[32]);
            ids.get(parent).add(i);
        }
        var pageArray = pages.toArray(SceneAsset.Page[]::new);
        var result =
                new SceneIndex(
                        asset,
                        layout.size,
                        pageArray,
                        bricks,
                        ids.stream()
                                .map(it.unimi.dsi.fastutil.ints.IntArrayList::toIntArray)
                                .toArray(int[][]::new),
                        new SceneBvh(pageBounds(pageArray, asset.pageSize())),
                        null,
                        new byte[32],
                        new byte[32],
                        null,
                        null);
        result.generatedSeams = seams;
        surface.staticIndex(result);
        return result;
    }

    SceneIndex(
            SceneAsset asset,
            int brickSize,
            SceneAsset.Page[] pages,
            Brick[] bricks,
            int[][] pageBricks,
            SceneBvh pageTree,
            SceneLowVolume low,
            byte[] sourceDigest,
            byte[] lowDigest,
            byte[] preparedSurface,
            SceneVisibility visibility) {
        this.asset = asset;
        this.pageSize = asset.pageSize();
        this.brickSize = brickSize;
        this.pages = pages;
        this.bricks = bricks;
        this.pageBricks = pageBricks;
        this.pageTree = pageTree;
        this.low = low;
        this.sourceDigest = sourceDigest;
        this.lowDigest = lowDigest;
        this.preparedSurface = preparedSurface;
        this.visibility = visibility;
        for (int i = 0; i < bricks.length; i++) brickIds.put(bricks[i].cell, i);
    }

    public record Brick(
            SceneLayout.Cell cell,
            int parent,
            int lowOffset,
            int lowTriangles,
            int[] sources,
            int capBytes,
            byte[] capDigest) {}

    @FunctionalInterface
    public interface BoundsTest {
        boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1);
    }

    public SceneAsset.Page[] pages() {
        return pages;
    }

    public Brick[] bricks() {
        return bricks;
    }

    public int[] pageBricks(int page) {
        return pageBricks[page];
    }

    float sourceHalo() { return SceneVoxels.STEP; }

    public void visiblePages(
            double x,
            double y,
            double z,
            BoundsTest frustum,
            boolean culling,
            boolean occlusion,
            IntConsumer visit) {
        BitSet reachable =
                culling && occlusion && visibility != null
                        ? visibility.visible(x, y, z, frustum)
                        : null;
        pageTree.visit(
                (x0, y0, z0, x1, y1, z1) -> !culling || frustum.intersects(x0, y0, z0, x1, y1, z1),
                page -> {
                    SceneAsset.Page p = pages[page];
                    double x0 = (double) p.coordinate(0) * pageSize,
                            y0 = (double) p.coordinate(1) * pageSize,
                            z0 = (double) p.coordinate(2) * pageSize;
                    if ((!culling
                                    || frustum.intersects(
                                            x0,
                                            y0,
                                            z0,
                                            x0 + pageSize,
                                            y0 + pageSize,
                                            z0 + pageSize))
                            && (reachable == null
                                    || visibility.contains(
                                            reachable,
                                            p.coordinate(0),
                                            p.coordinate(1),
                                            p.coordinate(2)))) visit.accept(page);
                });
    }

    public SceneSurface surface() throws IOException {
        if (preparedSurface == null) throw new IllegalStateException("静态曲面只交接一次");
        try (var input = new DataInputStream(new ByteArrayInputStream(preparedSurface))) {
            SceneSurface surface = SceneSurface.read(asset, input);
            if (input.available() != 0) throw new IOException("静态曲面含尾随数据");
            surface.staticIndex(this);
            preparedSurface = null;
            return surface;
        }
    }

    int[] sources(SceneLayout.Cell cell) {
        Integer id = brickIds.get(cell);
        return id == null ? new int[0] : bricks[id].sources;
    }

    public SceneSeams.Edges caps(SceneLayout.Cell cell) throws IOException {
        return caps(cell, () -> false);
    }

    public SceneSeams.Edges caps(
            SceneLayout.Cell cell, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        if (generatedSeams != null) return generatedSeams.lowCaps(cell, brickSize, cancelled);
        Integer id = brickIds.get(cell);
        if (id == null || bricks[id].capBytes == 0)
            return new SceneSeams.Edges(new float[0], new int[6], new int[6]);
        Brick brick = bricks[id];
        byte[] bytes = readCaps(id);
        if (!MessageDigest.isEqual(sha256(bytes), brick.capDigest))
            throw new IOException("静态截面指纹不符: " + cell);
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int[] offsets = new int[6], triangles = new int[6];
            int count = 0;
            for (int d = 0; d < 6; d++) {
                offsets[d] = count * 2;
                triangles[d] = in.readInt();
                if (triangles[d] < 0 || triangles[d] % 2 != 0) throw new IOException("静态截面面数错误");
                count = Math.addExact(count, triangles[d]);
            }
            int floats = encodingVersion == 1 ? 42 : 28;
            if (bytes.length != 24L + count * (long) floats * 4) throw new IOException("静态截面长度错误");
            float[] vertices = new float[count * floats];
            for (int i = 0; i < vertices.length; i++) {
                vertices[i] = in.readFloat();
                if (!Float.isFinite(vertices[i])) throw new IOException("静态截面非有限顶点");
            }
            if (encodingVersion == 1) {
                float[] packed = new float[count * 28];
                for (int r = 0; r < count / 2; r++) {
                    // 旧截面负向绕序为 021/032，正向为 012/023。
                    boolean positive = true;
                    for (int k = 0; k < 14; k++) positive &= vertices[r * 84 + 28 + k] == vertices[r * 84 + 56 + k];
                    int[] corners = positive ? new int[]{0, 14, 28, 70} : new int[]{0, 56, 14, 28};
                    for (int c = 0; c < 4; c++) System.arraycopy(vertices, r * 84 + corners[c], packed, r * 56 + c * 14, 14);
                }
                vertices = packed;
            }
            return new SceneSeams.Edges(vertices, offsets, triangles);
        }
    }

    private synchronized byte[] readCaps(int id) throws IOException {
        int size = asset.capGroupSize();
        int group = id / size, first = group * size, offset = 0, length = 0;
        for (int i = first; i < Math.min(first + size, bricks.length); i++) {
            if (i < id) offset += bricks[i].capBytes;
            length = Math.addExact(length, bricks[i].capBytes);
        }
        if (cachedCapGroup != group) {
            byte[] bytes = asset.readPackedSource("static/caps/groups/" + group + ".bin.xz");
            if (bytes.length != length) throw new IOException("静态截面分组长度不符");
            // 只缓存当前分组；跨砖查询复用解压结果，资产卸载时同步释放。
            cachedCaps = bytes;
            cachedCapGroup = group;
        }
        return Arrays.copyOfRange(cachedCaps, offset, offset + bricks[id].capBytes);
    }

    static byte[] encodeCaps(SceneSeams.Edges edges) throws IOException {
        if (edges.vertices().length == 0) return new byte[0];
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            for (int n : edges.triangles()) out.writeInt(n);
            for (float v : edges.vertices()) out.writeFloat(v);
        }
        return bytes.toByteArray();
    }

    public long bytes() {
        long total =
                pageTree.bytes()
                        + (low == null ? 0 : low.bytes())
                        + (preparedSurface == null ? 0 : preparedSurface.length)
                        + (cachedCaps == null ? 0 : cachedCaps.length);
        for (Brick b : bricks) total += 68L + b.sources.length * 4L;
        for (int[] ids : pageBricks) total += ids.length * 4L + 12;
        return total
                + (visibility == null ? 0 : visibility.bytes())
                + (generatedSeams == null ? 0 : generatedSeams.bytes());
    }

    byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(pageSize);
            out.writeInt(brickSize);
            out.write(sourceDigest);
            out.write(lowDigest);
            out.writeInt(preparedSurface.length);
            out.write(preparedSurface);
            out.writeDouble(low.step);
            low.write(out);
            out.writeInt(pages.length);
            for (SceneAsset.Page page : pages) {
                for (int a = 0; a < 3; a++) out.writeInt(page.coordinate(a));
                out.writeInt(page.low_triangles());
                out.writeInt(page.raw_triangles());
            }
            out.writeInt(bricks.length);
            for (Brick b : bricks) {
                out.writeInt(b.cell.x());
                out.writeInt(b.cell.y());
                out.writeInt(b.cell.z());
                out.writeInt(b.parent);
                out.writeInt(b.lowOffset);
                out.writeInt(b.lowTriangles);
                out.writeInt(b.sources.length);
                for (int source : b.sources) out.writeInt(source);
                out.writeInt(b.capBytes);
                out.write(b.capDigest);
            }
            pageTree.write(out);
            out.writeBoolean(visibility != null);
            if (visibility != null) visibility.write(out);
        }
        return bytes.toByteArray();
    }

    public static SceneIndex read(SceneAsset asset, SceneLayout layout) throws IOException {
        if (!asset.staticIndexAvailable()) return null;
        byte[] bytes = asset.readStaticIndex();
        if (!MessageDigest.isEqual(sha256(bytes), asset.staticIndexDigest()))
            throw new IOException("静态索引指纹不符");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("静态索引标识错误");
            int version = in.readInt();
            if ((version != 1 && version != VERSION)
                    || in.readInt() != asset.pageSize()
                    || in.readInt() != layout.size) throw new IOException("静态索引版本或空间单位不匹配");
            byte[] source = new byte[32], lowHash = new byte[32];
            in.readFully(source);
            in.readFully(lowHash);
            if (!MessageDigest.isEqual(source, sha256(asset.readSource("raw/source.json")))
                    || !MessageDigest.isEqual(lowHash, asset.geometryDigest()))
                throw new IOException("静态索引来源已变化");
            int length = length(in, Integer.MAX_VALUE);
            byte[] prepared = new byte[length];
            in.readFully(prepared);
            SceneLowVolume low = SceneLowVolume.read(in, in.readDouble());
            SceneAsset.Page[] pages = new SceneAsset.Page[length(in, Integer.MAX_VALUE)];
            if (pages.length < asset.pages().size()) throw new IOException("静态页目录不完整");
            Set<SceneLayout.Cell> pageCells = new HashSet<>();
            for (int i = 0; i < pages.length; i++) {
                SceneLayout.Cell cell = readCell(in);
                int lowCount = in.readInt(), rawCount = in.readInt();
                if (!pageCells.add(cell) || lowCount < 0 || rawCount < 0)
                    throw new IOException("静态页记录无效");
                pages[i] =
                        new SceneAsset.Page(
                                i, new int[] {cell.x(), cell.y(), cell.z()}, lowCount, rawCount);
                if (i < asset.pages().size()) {
                    SceneAsset.Page original = asset.pages().get(i);
                    if (!Arrays.equals(original.cell(), pages[i].cell())
                            || original.low_triangles() != lowCount
                            || original.raw_triangles() != rawCount)
                        throw new IOException("静态页来源不符");
                } else if (lowCount != 0 || rawCount != 0) throw new IOException("附加页含未声明几何");
            }
            Map<SceneLayout.Cell, SceneLayout.Brick> lowBricks = new HashMap<>();
            for (var b : layout.bricks) lowBricks.put(b.cell(), b);
            Brick[] bricks = new Brick[length(in, Integer.MAX_VALUE)];
            var ids = new it.unimi.dsi.fastutil.ints.IntArrayList[pages.length];
            Arrays.setAll(ids, i -> new it.unimi.dsi.fastutil.ints.IntArrayList());
            Set<SceneLayout.Cell> cells = new HashSet<>();
            for (int i = 0; i < bricks.length; i++) {
                SceneLayout.Cell cell = readCell(in);
                int parent = in.readInt(), offset = in.readInt(), triangles = in.readInt();
                if (parent < 0 || parent >= pages.length || !cells.add(cell))
                    throw new IOException("静态砖目录无效");
                for (int a = 0; a < 3; a++)
                    if (Math.floorDiv(cell.coordinate(a) * layout.size, asset.pageSize())
                            != pages[parent].coordinate(a)) throw new IOException("静态砖超出父页");
                var original = lowBricks.remove(cell);
                if (original == null
                        ? offset != 0 || triangles != 0
                        : offset != (version == 1 ? original.firstIndex() : original.offset())
                                || triangles != original.triangles())
                    throw new IOException("静态砖 Low 范围不符");
                int[] sources = new int[length(in, Integer.MAX_VALUE)];
                for (int j = 0; j < sources.length; j++) {
                    sources[j] = in.readInt();
                    if (sources[j] < 0 || j > 0 && sources[j] <= sources[j - 1])
                        throw new IOException("静态来源索引无序");
                }
                int capBytes = in.readInt();
                byte[] capDigest = new byte[32];
                in.readFully(capDigest);
                if (capBytes < 0) throw new IOException("静态截面大小越界");
                bricks[i] =
                        new Brick(cell, parent, original == null ? 0 : original.offset(), triangles, sources, capBytes, capDigest);
                ids[parent].add(i);
            }
            if (!lowBricks.isEmpty()) throw new IOException("静态索引漏掉 Low 砖");
            SceneBvh tree = SceneBvh.read(in, pageBounds(pages, asset.pageSize()));
            SceneVisibility visibility =
                    in.readBoolean() ? SceneVisibility.read(in, asset.pageSize()) : null;
            if (in.available() != 0) throw new IOException("静态索引含尾随数据");
            int[][] pageBricks = new int[pages.length][];
            for (int i = 0; i < pages.length; i++) pageBricks[i] = ids[i].toIntArray();
            SceneIndex result = new SceneIndex(
                    asset,
                    layout.size,
                    pages,
                    bricks,
                    pageBricks,
                    tree,
                    low,
                    source,
                    lowHash,
                    prepared,
                    visibility);
            result.encodingVersion = version;
            return result;
        } catch (EOFException truncated) {
            throw new IOException("静态索引被截断", truncated);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("静态索引无效", malformed);
        }
    }

    static double[][] pageBounds(SceneAsset.Page[] pages, int size) {
        double[][] bounds = new double[pages.length][6];
        for (int i = 0; i < pages.length; i++)
            for (int a = 0; a < 3; a++) {
                bounds[i][a] = (double) pages[i].coordinate(a) * size;
                bounds[i][a + 3] = bounds[i][a] + size;
            }
        return bounds;
    }

    private static SceneLayout.Cell readCell(DataInput in) throws IOException {
        int x = in.readInt(), y = in.readInt(), z = in.readInt();
        if (Math.abs((long) x) > 4_000_000
                || Math.abs((long) y) > 4_000_000
                || Math.abs((long) z) > 4_000_000) throw new IOException("静态格点越界");
        return new SceneLayout.Cell(x, y, z);
    }

    static int length(DataInput in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) throw new IOException("静态数组长度越界");
        return count;
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
