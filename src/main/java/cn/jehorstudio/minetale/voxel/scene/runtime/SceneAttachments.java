package cn.jehorstudio.minetale.voxel.scene.runtime;

import cn.jehorstudio.minetale.voxel.scene.asset.SceneAsset;
import cn.jehorstudio.minetale.voxel.scene.asset.SceneParts;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneLayout;
import cn.jehorstudio.minetale.voxel.scene.geometry.SceneVoxels;

import org.joml.Vector3d;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 每个种子保存各候选砖的最近点；High 发布时与几何同时切换，固定表面始终参与比较。
final class SceneAttachments {
    record Hit(double distance, double x, double y, double z) {}

    record Cell(int[] instances, Hit[] hits) {}

    private final SceneParts parts;
    private final double[][] seeds;
    private final Map<SceneLayout.Cell, Cell> low = new HashMap<>();
    private final Hit[] fixed;
    private Map<SceneLayout.Cell, Cell> displayed = Map.of();
    private final float[][] positions;
    private int unmatched;
    private final double[] radii;
    private final float[][] searchBounds;

    SceneAttachments(SceneAsset asset, SceneLayout layout, SceneParts parts) throws IOException {
        this.parts = parts;
        int count = parts.instances().size();
        seeds = new double[count][3];
        fixed = new Hit[count];
        positions = new float[count][3];
        radii = new double[count];
        searchBounds = new float[count][];
        for (int i = 0; i < count; i++) {
            var instance = parts.instances().get(i);
            float[] a = instance.anchor();
            radii[i] = instance.search_radius();
            searchBounds[i] =
                    instance.target() < 0
                            ? null
                            : parts.targets().get(instance.target()).bounds().clone();
            for (int axis = 0; axis < 3; axis++) {
                seeds[i][axis] = a[axis];
                if (instance.target() >= 0) {
                    float[] b = parts.targets().get(instance.target()).bounds();
                    seeds[i][axis] = b[axis] + a[axis] * (b[axis + 3] - b[axis]);
                }
            }
        }
        buildLow(layout);
        if (asset.fixedAvailable()) {
            ByteBuffer in =
                    ByteBuffer.wrap(asset.readSource("raw/fixed/geometry.bin"))
                            .order(ByteOrder.LITTLE_ENDIAN);
            if (in.getInt() != 0x4d544647) throw new IOException("固定附着表面头无效");
            int batches = in.getInt(), total = 0;
            for (int i = 0; i < batches; i++) total = Math.addExact(total, in.getInt());
            if (in.remaining() != total * 32L) throw new IOException("固定附着表面长度无效");
            float[] data = new float[total * 8];
            in.asFloatBuffer().get(data);
            for (int i = 0; i < count; i++)
                if (parts.instances().get(i).target() >= 0)
                    for (int v = 0; v < total; v += 3)
                        fixed[i] = triangle(i, fixed[i], data, 8, v, v + 1, v + 2);
        }
        update(Map.of(), true);
    }

    private void buildLow(SceneLayout layout) {
        for (var brick : layout.bricks) {
            List<Integer> selected = new ArrayList<>();
            double[] bounds = {
                brick.cell().x() * (double) layout.size,
                brick.cell().y() * (double) layout.size,
                brick.cell().z() * (double) layout.size
            };
            for (int i = 0; i < seeds.length; i++) {
                if (parts.instances().get(i).target() < 0) continue;
                double distance = 0;
                for (int a = 0; a < 3; a++) {
                    double d =
                            Math.max(
                                    Math.max(
                                            bounds[a] - seeds[i][a],
                                            seeds[i][a] - bounds[a] - layout.size),
                                    0);
                    distance += d * d;
                }
                double r = parts.instances().get(i).search_radius();
                if (distance < r * r) selected.add(i);
            }
            if (selected.isEmpty()) continue;
            int[] ids = selected.stream().mapToInt(Integer::intValue).toArray();
            Hit[] hits = new Hit[ids.length];
            for (int j = 0; j < ids.length; j++) {
                int i = ids[j];
                hits[j] = nearest(i, null, brick, layout);
            }
            low.put(brick.cell(), new Cell(ids, hits));
        }
        // High 可以在原 Low 为空的邻砖形成表面；候选目录按种子的搜索球补齐这些砖。
        Map<SceneLayout.Cell, List<Integer>> empty = new HashMap<>();
        for (int i = 0; i < seeds.length; i++) {
            if (parts.instances().get(i).target() < 0) continue;
            double[] seed = seeds[i];
            double radius = radii[i];
            int x0 = (int) Math.floor((seed[0] - radius) / layout.size),
                    x1 = (int) Math.floor((seed[0] + radius) / layout.size);
            int y0 = (int) Math.floor((seed[1] - radius) / layout.size),
                    y1 = (int) Math.floor((seed[1] + radius) / layout.size);
            int z0 = (int) Math.floor((seed[2] - radius) / layout.size),
                    z1 = (int) Math.floor((seed[2] + radius) / layout.size);
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) {
                        SceneLayout.Cell cell = new SceneLayout.Cell(x, y, z);
                        if (!low.containsKey(cell))
                            empty.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(i);
                    }
        }
        empty.forEach(
                (cell, ids) ->
                        low.put(
                                cell,
                                new Cell(
                                        ids.stream().mapToInt(Integer::intValue).toArray(),
                                        new Hit[ids.size()])));
    }

    private Hit nearest(int instance, Hit best, SceneLayout.Brick brick, SceneLayout layout) {
        if (layout.indices == null) {
            for (int v = 0; v < brick.vertices().length / 14; v += 4) {
                best = triangle(instance, best, brick.vertices(), 14, v, v + 1, v + 2);
                best = triangle(instance, best, brick.vertices(), 14, v, v + 2, v + 3);
            }
        } else
            for (int t = brick.firstIndex(); t < brick.firstIndex() + brick.triangles() * 3; t += 3)
                best =
                        triangle(
                                instance,
                                best,
                                brick.vertices(),
                                14,
                                layout.indices[t] - brick.offset(),
                                layout.indices[t + 1] - brick.offset(),
                                layout.indices[t + 2] - brick.offset());
        return best;
    }

    Cell prepare(SceneLayout.Cell cell, SceneVoxels.GeometryResult geometry) {
        Cell source = low.get(cell);
        if (source == null) return null;
        Hit[] hits = new Hit[source.instances.length];
        double[] output = new double[3];
        for (int j = 0; j < hits.length; j++) {
            int i = source.instances[j];
            var instance = parts.instances().get(i);
            double distance;
            float[] normal = instance.attachment_normal();
            if (normal == null) {
                distance = geometry.closest(seeds[i], searchBounds[i], output);
            } else {
                double[] seed = seeds[i];
                double forward = geometry.hit(seed[0], seed[1], seed[2],
                        normal[0], normal[1], normal[2], 0, radii[i]);
                double backward = geometry.hit(seed[0], seed[1], seed[2],
                        -normal[0], -normal[1], -normal[2], 0, radii[i]);
                double t = forward <= backward ? forward : -backward;
                distance = t * t;
                if (!Double.isFinite(distance)) continue;
                for (int axis = 0; axis < 3; axis++) {
                    output[axis] = seed[axis] + normal[axis] * t;
                    if (output[axis] < searchBounds[i][axis] - 2.001
                            || output[axis] > searchBounds[i][axis + 3] + 2.001)
                        distance = Double.POSITIVE_INFINITY;
                }
            }
            if (distance < instance.search_radius() * instance.search_radius())
                hits[j] = new Hit(distance, output[0], output[1], output[2]);
        }
        return new Cell(source.instances, hits);
    }

    boolean update(Map<SceneLayout.Cell, Cell> active, boolean force) {
        if (!force && displayed.equals(active)) return false;
        displayed = Map.copyOf(active);
        Hit[] best = fixed.clone();
        for (var entry : low.entrySet()) {
            Cell cell = active.getOrDefault(entry.getKey(), entry.getValue());
            for (int j = 0; j < cell.instances.length; j++) {
                int i = cell.instances[j];
                Hit hit = cell.hits[j];
                if (hit != null && (best[i] == null || hit.distance < best[i].distance))
                    best[i] = hit;
            }
        }
        unmatched = 0;
        for (int i = 0; i < best.length; i++) {
            Hit hit = best[i];
            var instance = parts.instances().get(i);
            if (hit == null && instance.target() >= 0) unmatched++;
            // raw 的种子与偏移共同保存作者位置；无有效接触时沿用该位置。
            double[] contact = hit == null ? seeds[i] : new double[] {hit.x, hit.y, hit.z};
            for (int a = 0; a < 3; a++) {
                double value = contact[a] + instance.offset()[a];
                // Z 轴翻转后保留 Blender half-up 在原坐标空间的平局方向。
                positions[i][a] =
                        (float)
                                (a == 2
                                        ? -Math.floor(-value * 8 + .5) / 8
                                        : Math.floor(value * 8 + .5) / 8);
            }
        }
        return true;
    }

    String stats() {
        return " authoredFallback=" + unmatched;
    }

    float[] position(int instance) {
        return positions[instance];
    }

    private Hit triangle(
            int instance, Hit previous, float[] data, int stride, int ia, int ib, int ic) {
        double[] p = seeds[instance];
        float[] bounds = searchBounds[instance];
        double radius = radii[instance];
        double limit = previous == null ? radius * radius : previous.distance;
        ia *= stride;
        ib *= stride;
        ic *= stride;
        double lower = 0;
        for (int a = 0; a < 3; a++) {
            double lo = Math.min(data[ia + a], Math.min(data[ib + a], data[ic + a])),
                    hi = Math.max(data[ia + a], Math.max(data[ib + a], data[ic + a]));
            if (Double.isFinite(radius) && (hi < bounds[a] - 2.001 || lo > bounds[a + 3] + 2.001))
                return previous;
            double d = Math.max(Math.max(lo - p[a], p[a] - hi), 0);
            lower += d * d;
        }
        if (lower >= limit) return previous;
        Vector3d a = new Vector3d(data[ia], data[ia + 1], data[ia + 2]),
                b = new Vector3d(data[ib], data[ib + 1], data[ib + 2]),
                c = new Vector3d(data[ic], data[ic + 1], data[ic + 2]);
        Vector3d ab = new Vector3d(b).sub(a),
                ac = new Vector3d(c).sub(a),
                ap = new Vector3d(p).sub(a);
        float[] direction = parts.instances().get(instance).attachment_normal();
        if (direction != null) {
            // 来源法线确定附着轴，接触点沿该轴随体素轮廓移动。
            Vector3d n = new Vector3d(ab).cross(ac);
            double divisor = n.x * direction[0] + n.y * direction[1] + n.z * direction[2];
            if (Math.abs(divisor) < 1e-12) return previous;
            double t = -n.dot(ap) / divisor;
            if (t * t >= limit) return previous;
            Vector3d q = new Vector3d(p).add(direction[0] * t, direction[1] * t, direction[2] * t);
            Vector3d aq = new Vector3d(q).sub(a);
            double aa = ab.dot(ab), bb = ab.dot(ac), cc = ac.dot(ac);
            double denominator = aa * cc - bb * bb;
            if (denominator <= 1e-20) return previous;
            double u = (cc * aq.dot(ab) - bb * aq.dot(ac)) / denominator;
            double v = (aa * aq.dot(ac) - bb * aq.dot(ab)) / denominator;
            if (u < -1e-7 || v < -1e-7 || u + v > 1 + 1e-7) return previous;
            for (int axis = 0; axis < 3; axis++)
                if (q.get(axis) < bounds[axis] - 2.001 || q.get(axis) > bounds[axis + 3] + 2.001)
                    return previous;
            return new Hit(t * t, q.x, q.y, q.z);
        }
        double d00 = ab.dot(ab),
                d01 = ab.dot(ac),
                d11 = ac.dot(ac),
                d20 = ap.dot(ab),
                d21 = ap.dot(ac),
                denom = d00 * d11 - d01 * d01;
        Vector3d closest = new Vector3d();
        double best = Double.POSITIVE_INFINITY;
        if (denom > 1e-20) {
            double u = (d11 * d20 - d01 * d21) / denom, v = (d00 * d21 - d01 * d20) / denom;
            if (u >= 0 && v >= 0 && u + v <= 1) {
                closest.set(a).fma(u, ab).fma(v, ac);
                best = closest.distanceSquared(p[0], p[1], p[2]);
            }
        }
        Vector3d[] corners = {a, b, c};
        for (int edge = 0; edge < 3; edge++) {
            Vector3d start = corners[edge],
                    delta = new Vector3d(corners[(edge + 1) % 3]).sub(start);
            double length = delta.lengthSquared(),
                    t =
                            length == 0
                                    ? 0
                                    : Math.max(
                                            0,
                                            Math.min(
                                                    1,
                                                    new Vector3d(p).sub(start).dot(delta)
                                                            / length));
            Vector3d q = new Vector3d(start).fma(t, delta);
            double d = q.distanceSquared(p[0], p[1], p[2]);
            if (d < best) {
                best = d;
                closest.set(q);
            }
        }
        for (int axis = 0; axis < 3; axis++)
            if (Double.isFinite(radius)
                    && (closest.get(axis) < bounds[axis] - 2.001
                            || closest.get(axis) > bounds[axis + 3] + 2.001)) return previous;
        return best < limit ? new Hit(best, closest.x, closest.y, closest.z) : previous;
    }
}
