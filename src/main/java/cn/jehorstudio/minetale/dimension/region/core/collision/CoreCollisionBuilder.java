package cn.jehorstudio.minetale.dimension.region.core.collision;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.region.core.CorePlacement;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneParts;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * 从场景资产构建 Core 碰撞占据栅格：主体按渲染光栅化的同一约定（SAT 重叠 + 主轴 spread 厚度）
 * 标记 0.5 米单元，部件按初始附着摆放（固定表面投影）标记最粗档网格。
 *
 * <p>标记约定与 {@code SceneVoxels.raster} 一致，使碰撞盒与渲染体素面共格；
 * 部件位置是 {@code SceneAttachments} 构造期的初始触点（fixed 表面命中或种子），
 * 运行时 High 表面微调在半个单元量级内，由碰撞的粗粒度吸收。
 */
public final class CoreCollisionBuilder {
    private static final float CELL = CoreCollisionGrid.CELL;

    private CoreCollisionBuilder() {}

    /** 构建统计，供指令与日志归因；snapped 为吸附到主体表面的部件实例数。 */
    public record Result(CoreCollisionGrid grid, long triangles, long snapped, long millis) {}

    public static Result build(SceneAsset asset, CorePlacement placement, BooleanSupplier cancelled)
            throws IOException {
        long started = System.nanoTime();
        CoreCollisionGrid grid = new CoreCollisionGrid(placement.x(), placement.y(), placement.z());
        List<double[]> fixedMesh = fixedTriangles(asset);
        // 主体 = 固定网格（全量渲染、轴对齐面）：面沿实体侧挤出薄壳，与可见面完全齐平；
        // 少量非轴对齐三角形与体素层（raw 页）落入 0.5m 占据格，占据格沿用渲染光栅化约定。
        Marking voxel = new Marking(grid, true);
        Marking partMarking = new Marking(grid, false);
        // 固定网格三角形没有页边界约束，兜底标记禁用 spread，避免大斜三角的楔形爆炸。
        long triangles = markFixedShells(grid, fixedMesh, partMarking,
                placement.x(), placement.y(), placement.z());
        Map<Long, List<double[]>> bodyPages = bodyTrianglesByPage(asset);
        for (List<double[]> pageTriangles : bodyPages.values())
            for (double[] triangle : pageTriangles)
                triangles += voxel.triangle(
                        triangle[0], triangle[1], triangle[2],
                        triangle[3], triangle[4], triangle[5],
                        triangle[6], triangle[7], triangle[8]);
        if (cancelled.getAsBoolean()) throw new CancellationException();
        long snappedParts = 0;
        if (asset.partsAvailable() && !cancelled.getAsBoolean()) {
            long[] stats = parts(asset, grid, partMarking, fixedMesh, cancelled);
            triangles += stats[0];
            snappedParts = stats[1];
        }
        return new Result(grid, triangles, snappedParts, (System.nanoTime() - started) / 1_000_000);
    }

    /** 固定网格三角形：轴对齐面生成实体侧薄壳，其余落回体素标记。返回处理的三角形数。 */
    private static long markFixedShells(
            CoreCollisionGrid grid, List<double[]> fixedMesh, Marking fallback, int ox, int oy, int oz) {
        long triangles = 0;
        for (double[] t : fixedMesh) {
            triangles++;
            int axis = -1;
            for (int a = 0; a < 3; a++)
                if (Math.max(t[a], Math.max(t[a + 3], t[a + 6]))
                        - Math.min(t[a], Math.min(t[a + 3], t[a + 6])) < 1e-4) {
                    axis = a;
                    break;
                }
            double normal = axis < 0 ? 0 : -t[9 + axis];
            // 非轴对齐或法线异常的三角形退回体素标记。
            if (axis < 0 || Math.abs(normal) < .5) {
                fallback.triangle(
                        t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8]);
                continue;
            }
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            double plane = t[axis];
            double uMin = Math.min(t[u], Math.min(t[u + 3], t[u + 6]));
            double uMax = Math.max(t[u], Math.max(t[u + 3], t[u + 6]));
            double vMin = Math.min(t[v], Math.min(t[v + 3], t[v + 6]));
            double vMax = Math.max(t[v], Math.max(t[v + 3], t[v + 6]));
            double[] min = {0, 0, 0}, max = {0, 0, 0};
            min[axis] = Math.min(plane, plane + normal * CoreCollisionGrid.SHELL);
            max[axis] = Math.max(plane, plane + normal * CoreCollisionGrid.SHELL);
            min[u] = uMin;
            max[u] = uMax;
            min[v] = vMin;
            max[v] = vMax;
            // 网格坐标是 scene-local；壳直接存世界坐标，补放置原点偏移。
            grid.addShell(new AABB(ox + min[0], oy + min[1], oz + min[2],
                    ox + max[0], oy + max[1], oz + max[2]));
        }
        return triangles;
    }

    /** 从模组资源读取 .mtscene；assets/ 在服务端不可经 ResourceManager 访问，统一走类路径。 */
    public static SceneAsset openAsset(ResourceLocation scene) throws IOException {
        String path = "/assets/" + scene.getNamespace() + "/scene/" + scene.getPath() + ".mtscene";
        Path snapshot = Files.createTempFile("minetale-core-scene-", ".mtscene");
        try (InputStream input = MineTale.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("场景资源不存在: " + path);
            Files.copy(input, snapshot, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(snapshot);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return SceneAsset.open(snapshot);
    }

    // ---- 部件 ----

    /** raw 三角形按 32 米页分桶；用于部件种子吸附到真实主体表面。 */
    private static Map<Long, List<double[]>> bodyTrianglesByPage(SceneAsset asset) throws IOException {
        Map<Long, List<double[]>> pages = new HashMap<>();
        for (SceneAsset.Page page : asset.pages()) {
            if (page.raw_triangles() == 0) continue;
            float[] raw = asset.read(page, true);
            List<double[]> triangles = new ArrayList<>(page.raw_triangles());
            for (int t = 0; t < raw.length; t += 24) {
                double[] triangle = {
                    raw[t], raw[t + 1], raw[t + 2],
                    raw[t + 8], raw[t + 9], raw[t + 10],
                    raw[t + 16], raw[t + 17], raw[t + 18]
                };
                triangles.add(triangle);
            }
            pages.put(pageKey(page.coordinate(0), page.coordinate(1), page.coordinate(2)), triangles);
        }
        return pages;
    }

    private static long pageKey(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    /** 固定网格三角形：9 个位置分量 + 首顶点法线 3 分量（pos3+uv2+normal3 布局）。 */
    private static List<double[]> fixedTriangles(SceneAsset asset) throws IOException {
        if (!asset.fixedAvailable()) return List.of();
        ByteBuffer in = ByteBuffer
                .wrap(asset.readSource("raw/fixed/geometry.bin"))
                .order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt() != 0x4d544647) throw new IOException("固定网格头无效");
        int batches = in.getInt(), total = 0;
        for (int i = 0; i < batches; i++) total = Math.addExact(total, in.getInt());
        if (in.remaining() != total * 32L) throw new IOException("固定网格长度无效");
        int base = 8 + batches * 4;
        List<double[]> triangles = new ArrayList<>(total / 3);
        for (int v = 0; v + 3 <= total; v += 3) {
            double[] triangle = new double[12];
            for (int c = 0; c < 3; c++)
                for (int a = 0; a < 3; a++)
                    triangle[c * 3 + a] = in.getFloat(base + (v + c) * 32 + a * 4);
            for (int a = 0; a < 3; a++) triangle[9 + a] = in.getFloat(base + v * 32 + (5 + a) * 4);
            triangles.add(triangle);
        }
        return triangles;
    }

    /**
     * 种子吸附到固定网格表面最近点（SceneAttachments 构造期同一裁决：目标越界或超半径返回 null）。
     * 固定网格与目标/种子同坐标系，是部件的实际附着面。
     */
    private static double[] nearestOnFixed(
            List<double[]> mesh, double[] seed, float[] bounds, double radius) {
        double best = radius * radius;
        double[] hit = null;
        for (double[] t : mesh) {
            double lower = 0;
            boolean outside = false;
            for (int a = 0; a < 3; a++) {
                double l = Math.min(t[a], Math.min(t[a + 3], t[a + 6]));
                double h = Math.max(t[a], Math.max(t[a + 3], t[a + 6]));
                if (bounds != null && (h < bounds[a] - 2.001 || l > bounds[a + 3] + 2.001)) {
                    outside = true;
                    break;
                }
                double d = Math.max(Math.max(l - seed[a], seed[a] - h), 0);
                lower += d * d;
            }
            if (outside || lower >= best) continue;
            Vector3d closest = closestPoint(
                    t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8],
                    seed[0], seed[1], seed[2]);
            double distance = closest.distanceSquared(seed[0], seed[1], seed[2]);
            if (distance >= best) continue;
            boolean beyond = false;
            if (bounds != null)
                for (int a = 0; a < 3; a++)
                    if (closest.get(a) < bounds[a] - 2.001 || closest.get(a) > bounds[a + 3] + 2.001) {
                        beyond = true;
                        break;
                    }
            if (beyond) continue;
            best = distance;
            hit = new double[] {closest.x, closest.y, closest.z};
        }
        return hit;
    }

    /** 返回 [标记数, 吸附实例数]。 */
    private static long[] parts(
            SceneAsset asset,
            CoreCollisionGrid grid,
            Marking marking,
            List<double[]> fixedMesh,
            BooleanSupplier cancelled)
            throws IOException {
        SceneParts parts = new SceneParts(asset);
        int count = parts.instances().size();
        double[][] seeds = new double[count][];
        float[][] searchBounds = new float[count][];
        for (int i = 0; i < count; i++) {
            SceneParts.Instance instance = parts.instances().get(i);
            SceneParts.Target target =
                    instance.target() >= 0 ? parts.targets().get(instance.target()) : null;
            double[] seed = new double[3];
            if (target != null) {
                float[] b = target.bounds();
                searchBounds[i] = b.clone();
                for (int a = 0; a < 3; a++) seed[a] = b[a] + instance.anchor()[a] * (b[a + 3] - b[a]);
            } else {
                searchBounds[i] = null;
                for (int a = 0; a < 3; a++) seed[a] = instance.anchor()[a];
            }
            seeds[i] = seed;
        }
        Map<Integer, SceneParts.Mesh> meshes = new HashMap<>();
        Matrix4f transform = new Matrix4f();
        Vector3f corner = new Vector3f();
        double[] x = new double[4], y = new double[4], z = new double[4];
        long triangles = 0, snapped = 0;
        for (int i = 0; i < count; i++) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            SceneParts.Instance instance = parts.instances().get(i);
            double[] contact = seeds[i];
            if (instance.target() >= 0) {
                double[] hit = nearestOnFixed(fixedMesh, seeds[i], searchBounds[i], instance.search_radius());
                if (hit != null) {
                    contact = hit;
                    snapped++;
                }
            }
            // 触点 + offset 后按 1/8 方块取整（Z 轴翻转保留原空间的平局方向）。
            float[] position = new float[3];
            for (int a = 0; a < 3; a++) {
                double value = contact[a] + instance.offset()[a];
                position[a] = (float) (a == 2 ? -Math.floor(-value * 8 + .5) / 8 : Math.floor(value * 8 + .5) / 8);
            }
            float[] linear = instance.linear();
            float[] base = {
                linear[0], linear[1], linear[2], position[0],
                linear[3], linear[4], linear[5], position[1],
                linear[6], linear[7], linear[8], position[2]
            };
            SceneParts.Mesh mesh = meshes.computeIfAbsent(instance.prototype(), prototype -> {
                try {
                    // 部件碰撞使用最细一档 LOD：贴合近景视觉；代价仅一次性构建耗时（约 +4s）。
                    return parts.readMesh(prototype, parts.prototypes().get(prototype).lods().size() - 1);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            for (float[] copy : parts.prototypes().get(instance.prototype()).copies()) {
                transform.set(affine(base)).mul(affine(copy));
                float[] vertices = mesh.vertices();
                for (int v = 0; v < vertices.length; v += 56) {
                    // 每四边形按渲染同一三角化标记两个三角形。
                    for (int c = 0; c < 4; c++) {
                        int o = v + c * 14;
                        corner.set(vertices[o], vertices[o + 1], vertices[o + 2]);
                        transform.transformPosition(corner);
                        x[c] = corner.x;
                        y[c] = corner.y;
                        z[c] = corner.z;
                    }
                    triangles += marking.triangle(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]);
                    triangles += marking.triangle(x[0], y[0], z[0], x[2], y[2], z[2], x[3], y[3], z[3]);
                }
            }
        }
        return new long[] {triangles, snapped};
    }

    private static Matrix4f affine(float[] a) {
        return new Matrix4f(
                a[0], a[4], a[8], 0,
                a[1], a[5], a[9], 0,
                a[2], a[6], a[10], 0,
                a[3], a[7], a[11], 1);
    }

    private static Vector3d closestPoint(
            double ax, double ay, double az, double bx, double by, double bz,
            double cx, double cy, double cz, double px, double py, double pz) {
        Vector3d a = new Vector3d(ax, ay, az),
                ab = new Vector3d(bx - ax, by - ay, bz - az),
                ac = new Vector3d(cx - ax, cy - ay, cz - az),
                ap = new Vector3d(px - ax, py - ay, pz - az);
        double d00 = ab.dot(ab), d01 = ab.dot(ac), d11 = ac.dot(ac);
        double d20 = ap.dot(ab), d21 = ap.dot(ac);
        double denom = d00 * d11 - d01 * d01;
        Vector3d closest = new Vector3d();
        double best = Double.POSITIVE_INFINITY;
        if (denom > 1e-20) {
            double u = (d11 * d20 - d01 * d21) / denom, v = (d00 * d21 - d01 * d20) / denom;
            if (u >= 0 && v >= 0 && u + v <= 1) {
                closest.set(a).fma(u, ab).fma(v, ac);
                best = closest.distanceSquared(px, py, pz);
            }
        }
        Vector3d[] corners = {a, new Vector3d(bx, by, bz), new Vector3d(cx, cy, cz)};
        for (int edge = 0; edge < 3; edge++) {
            Vector3d start = corners[edge],
                    delta = new Vector3d(corners[(edge + 1) % 3]).sub(start);
            double length = delta.lengthSquared();
            double t = length == 0
                    ? 0
                    : Math.max(
                            0,
                            Math.min(1, new Vector3d(px, py, pz).sub(start).dot(delta) / length));
            Vector3d q = new Vector3d(start).fma(t, delta);
            double d = q.distanceSquared(px, py, pz);
            if (d < best) {
                best = d;
                closest.set(q);
            }
        }
        return closest;
    }

    // ---- 标记器：SceneVoxels.raster 的标记约定（SAT；spread 仅主体封闭用），无材质与法线逻辑 ----

    private static final class Marking {
        private final CoreCollisionGrid grid;
        private final boolean spread;
        private final double[] a = new double[3], b = new double[3], c = new double[3], n = new double[3];
        private final double[][] p = new double[3][3];
        private final int[] q = new int[3];

        Marking(CoreCollisionGrid grid, boolean spread) {
            this.grid = grid;
            this.spread = spread;
        }

        int triangle(
                double ax, double ay, double az, double bx, double by, double bz,
                double cx, double cy, double cz) {
            a[0] = ax / CELL;
            a[1] = ay / CELL;
            a[2] = az / CELL;
            b[0] = bx / CELL;
            b[1] = by / CELL;
            b[2] = bz / CELL;
            c[0] = cx / CELL;
            c[1] = cy / CELL;
            c[2] = cz / CELL;
            n[0] = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
            n[1] = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
            n[2] = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
            int axis = Math.abs(n[1]) > Math.abs(n[0]) ? 1 : 0;
            if (Math.abs(n[2]) > Math.abs(n[axis])) axis = 2;
            if (Math.abs(n[axis]) < 1e-12) return 0;
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            int minU = (int) Math.floor(Math.min(a[u], Math.min(b[u], c[u]))),
                    maxU = (int) Math.floor(Math.max(a[u], Math.max(b[u], c[u])));
            int minV = (int) Math.floor(Math.min(a[v], Math.min(b[v], c[v]))),
                    maxV = (int) Math.floor(Math.max(a[v], Math.max(b[v], c[v])));
            double dilation = spread
                    ? (Math.abs(n[u]) + Math.abs(n[v])) / Math.abs(n[axis]) * .5
                    : 0;
            int marked = 0;
            for (int y = minV; y <= maxV; y++)
                for (int x = minU; x <= maxU; x++) {
                    double depth =
                            a[axis] - (n[u] * (x + .5 - a[u]) + n[v] * (y + .5 - a[v])) / n[axis];
                    int lo = (int) Math.floor(depth - dilation), hi = (int) Math.floor(depth + dilation);
                    q[u] = x;
                    q[v] = y;
                    for (int z = lo; z <= hi; z++) {
                        q[axis] = z;
                        if (overlap()) {
                            grid.markSceneCell(q[0], q[1], q[2]);
                            marked++;
                        }
                    }
                }
            return marked;
        }

        private boolean overlap() {
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
                if (separates(0, z, -y) || separates(-z, 0, x) || separates(y, -x, 0)) return false;
            }
            double ex = b[0] - a[0], ey = b[1] - a[1], ez = b[2] - a[2];
            double fx = c[0] - a[0], fy = c[1] - a[1], fz = c[2] - a[2];
            return !separates(0, fz, -fy) && !separates(-fz, 0, fx) && !separates(fy, -fx, 0)
                    && !separates(ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx);
        }

        private boolean separates(double x, double y, double z) {
            double a0 = p[0][0] * x + p[0][1] * y + p[0][2] * z;
            double a1 = p[1][0] * x + p[1][1] * y + p[1][2] * z;
            double a2 = p[2][0] * x + p[2][1] * y + p[2][2] * z;
            double radius = .5 * (Math.abs(x) + Math.abs(y) + Math.abs(z));
            return Math.min(a0, Math.min(a1, a2)) > radius + 1e-9
                    || Math.max(a0, Math.max(a1, a2)) < -radius - 1e-9;
        }
    }
}
