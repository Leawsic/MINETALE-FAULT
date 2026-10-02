package cn.jehorstudio.minetale.magic.collision;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * 服务端法术几何查询。调用方每 Tick 提交真实伤害范围。
 * 世界查询要求服务端线程，纯形状相交方法可独立使用。
 */
public final class MagicCollision {
    private static final double EPS = 1.0E-9;
    public static final double BLOCK_PRECISION = 1.0 / 64;

    private MagicCollision() {}

    /** 可扩展几何契约；bounds 必须保守包含整个体积，intersects 必须测试实际形状。 */
    public interface Volume {
        AABB bounds();
        boolean intersects(AABB box);
    }

    public record Sphere(Vec3 center, double radius) implements Volume {
        public Sphere { finite(center); positive(radius); }
        @Override public AABB bounds() { return new AABB(center, center).inflate(radius); }
        @Override public boolean intersects(AABB box) { return distanceSquared(center, box) <= radius * radius + EPS; }
    }

    /** 球心在一个 Tick 内从 start 扫到 end 的闭合体积，包含两端半球。 */
    public record SweptSphere(Vec3 start, Vec3 end, double radius) implements Volume {
        public SweptSphere { finite(start); finite(end); positive(radius); }
        @Override public AABB bounds() { return new AABB(start, end).inflate(radius); }
        @Override public boolean intersects(AABB box) {
            return segmentBoxDistanceSquared(start, end, box) <= radius * radius + EPS;
        }
    }

    /** 平端有限圆柱。direction 自动归一化，length=0 表示圆盘，用于求最早地形接触。 */
    public record Cylinder(Vec3 origin, Vec3 direction, double length, double radius) implements Volume {
        public Cylinder {
            finite(origin);
            finite(direction);
            positive(radius);
            if (!Double.isFinite(length) || length < 0 || direction.lengthSqr() < EPS * EPS) {
                throw new IllegalArgumentException("圆柱长度或方向非法");
            }
            direction = direction.normalize();
        }
        public Vec3 end() { return origin.add(direction.scale(length)); }
        public Cylinder withLength(double value) { return new Cylinder(origin, direction, value, radius); }
        @Override public AABB bounds() {
            Vec3 end = end();
            double x = radius * Math.sqrt(Math.max(0, 1 - direction.x * direction.x));
            double y = radius * Math.sqrt(Math.max(0, 1 - direction.y * direction.y));
            double z = radius * Math.sqrt(Math.max(0, 1 - direction.z * direction.z));
            return new AABB(Math.min(origin.x, end.x) - x, Math.min(origin.y, end.y) - y,
                    Math.min(origin.z, end.z) - z, Math.max(origin.x, end.x) + x,
                    Math.max(origin.y, end.y) + y, Math.max(origin.z, end.z) + z);
        }
        @Override public boolean intersects(AABB box) { return cylinderBox(this, box); }
    }

    /** 去重命中列表及窄筛候选次数；统计与客户端渲染粒子数量无关。 */
    public record Query(List<LivingEntity> hits, int candidates) {}

    /**
     * GB 客户端遮挡和服务端命中共用的阻挡形状。只有完整、不透光的实体方块挡束。
     * 水、树叶、玻璃和非完整方块可穿透；未加载区域与世界边界阻挡。
     */
    public static VoxelShape beamBlockShape(Level level, BlockPos pos) {
        // ClientLevel.hasChunk 恒为 true；必须询问 ChunkSource，才能把未加载区域视为阻挡。
        if (!level.isInWorldBounds(pos) || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || !level.getWorldBorder().isWithinBounds(pos)) return Shapes.block();
        var state = level.getBlockState(pos);
        return mayBlockBeam(state) && state.isCollisionShapeFullBlock(level, pos)
                ? Shapes.block() : Shapes.empty();
    }

    /**
     * @param state 已构建的方块状态
     * @return false 保证可透光；true 仍需由 beamBlockShape 检查世界位置及完整碰撞形状
     */
    public static boolean mayBlockBeam(BlockState state) {
        return state.isSolidRender() && state.getLightBlock() == 15;
    }

    /**
     * 对已与 active 相交的目标检查沿发射轴的受光路径
     * 采样点固定在目标盒上，增加半径只增加可用路径；retraction 为当前收口比例。
     * 使用最近轴线点和目标盒的 27 个固定点，极窄的局部露出受有限采样精度限制。
     */
    public static boolean beamReaches(ServerLevel level, Cylinder active, AABB target, float retraction) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("光束命中需要服务端线程");
        if (retraction <= 0) return false;
        Vec3 closest = closestSegmentPoint(active.origin, active.end(), target);
        Vec3 witness = new Vec3(Math.clamp(closest.x, target.minX, target.maxX),
                Math.clamp(closest.y, target.minY, target.maxY), Math.clamp(closest.z, target.minZ, target.maxZ));
        if (beamReachesPoint(level, active, witness, retraction)) return true;
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
            Vec3 point = new Vec3(Mth.lerp(x * 0.5, target.minX, target.maxX),
                    Mth.lerp(y * 0.5, target.minY, target.maxY), Mth.lerp(z * 0.5, target.minZ, target.maxZ));
            if (beamReachesPoint(level, active, point, retraction)) return true;
        }
        return false;
    }

    private static boolean beamReachesPoint(ServerLevel level, Cylinder beam, Vec3 point, float retraction) {
        Vec3 offset = point.subtract(beam.origin);
        double along = offset.dot(beam.direction);
        if (along < -EPS || along > beam.length + EPS) return false;
        Vec3 radial = offset.subtract(beam.direction.scale(along));
        if (radial.lengthSqr() > beam.radius * beam.radius + EPS) return false;
        Vec3 start = beam.origin.add(radial);
        // 各条路径从自己的障碍接触处收回，与客户端遮挡 Shader 的收口规则一致。
        Vec3 end = start.add(beam.direction.scale(Math.max(0, along) / retraction));
        return BlockGetter.traverseBlocks(start, end, level, (world, pos) -> {
            VoxelShape shape = beamBlockShape(world, pos);
            if (shape.isEmpty()) return null;
            AABB block = new AABB(pos);
            return block.contains(start) || block.clip(start, end).isPresent() ? Boolean.FALSE : null;
        }, world -> Boolean.TRUE);
    }

    /** 对每个体积使用原版空间粗筛，再窄筛并按实体 ID 去重 */
    public static Query query(ServerLevel level, Entity caster, List<? extends Volume> volumes,
                              Predicate<LivingEntity> filter) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("碰撞世界查询需要服务端线程");
        var hits = new LinkedHashMap<Integer, LivingEntity>();
        int candidates = 0;
        for (Volume volume : volumes) {
            for (Entity entity : level.getEntities(caster, volume.bounds().inflate(EPS),
                    e -> e instanceof LivingEntity living && living.isAlive() && !living.isSpectator())) {
                if (hits.containsKey(entity.getId())) continue;
                LivingEntity living = (LivingEntity) entity;
                candidates++;
                if (filter.test(living) && volume.intersects(living.getBoundingBox())) hits.put(entity.getId(), living);
            }
        }
        return new Query(List.copyOf(hits.values()), candidates);
    }

    /**
     * 找到考虑半径的首个方块接触，误差不超过 BLOCK_PRECISION，返回接触前的可见长度。
     * DDA 只遍历轴线邻近体素；未加载区块、世界边界及构建高度外视为阻挡。
     */
    public static double clipBlocks(ServerLevel level, Cylinder beam) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("方块查询需要服务端线程");
        if (beam.length > 128 || beam.radius > 4) throw new IllegalArgumentException("单次方块扫描范围超出 128 格/半径 4 格");
        Vec3 p = beam.origin;
        Vec3 d = beam.direction;
        int x = Mth.floor(p.x), y = Mth.floor(p.y), z = Mth.floor(p.z);
        int sx = d.x >= 0 ? 1 : -1, sy = d.y >= 0 ? 1 : -1, sz = d.z >= 0 ? 1 : -1;
        double tx = boundary(p.x, d.x, x), ty = boundary(p.y, d.y, y), tz = boundary(p.z, d.z, z);
        double dx = Math.abs(1 / d.x), dy = Math.abs(1 / d.y), dz = Math.abs(1 / d.z);
        int reach = (int) Math.ceil(beam.radius) + 1;
        var border = level.getWorldBorder();
        double limit = Math.min(beam.length, Math.min(
                borderLimit(p.x, d.x, beam.radius, border.getMinX(), border.getMaxX()),
                borderLimit(p.z, d.z, beam.radius, border.getMinZ(), border.getMaxZ())));
        if (limit <= EPS) return 0;
        double travelled = 0;
        LongOpenHashSet visited = new LongOpenHashSet();
        // 法术几何不继承施法者的潜行、站立高度和装备；特殊方块统一使用无实体的碰撞形状。
        CollisionContext context = CollisionContext.empty();
        AABB bounds = beam.bounds().inflate(EPS);
        while (travelled <= beam.length + EPS) {
            for (int ox = -reach; ox <= reach; ox++) {
                for (int oy = -reach; oy <= reach; oy++) {
                    for (int oz = -reach; oz <= reach; oz++) {
                        BlockPos pos = new BlockPos(x + ox, y + oy, z + oz);
                        if (!visited.add(pos.asLong())) continue;
                        AABB cell = new AABB(pos);
                        if (!bounds.intersects(cell)) continue;
                        if (!level.isInWorldBounds(pos)
                                || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                            limit = shorten(beam, cell, limit);
                        } else {
                            var shape = level.getBlockState(pos).getCollisionShape(level, pos, context);
                            for (AABB local : shape.toAabbs()) limit = shorten(beam, local.move(pos), limit);
                        }
                        if (limit <= EPS) return 0;
                    }
                }
            }
            double next = Math.min(tx, Math.min(ty, tz));
            // 已越过首个接触和半径邻域，后续体素不可能缩短光束。
            if (next > limit + beam.radius + 2) break;
            if (tx <= next + EPS) { x += sx; tx += dx; }
            if (ty <= next + EPS) { y += sy; ty += dy; }
            if (tz <= next + EPS) { z += sz; tz += dz; }
            travelled = next;
        }
        return limit;
    }

    private static double boundary(double p, double d, int cell) {
        return Math.abs(d) < EPS ? Double.POSITIVE_INFINITY : ((d > 0 ? cell + 1 : cell) - p) / d;
    }

    private static double borderLimit(double origin, double axis, double radius, double minimum, double maximum) {
        double radial = radius * Math.sqrt(Math.max(0, 1 - axis * axis));
        if (origin - radial < minimum || origin + radial > maximum) return 0;
        if (Math.abs(axis) < EPS) return Double.POSITIVE_INFINITY;
        return Math.max(0, axis > 0 ? (maximum - radial - origin) / axis : (minimum + radial - origin) / axis);
    }

    private static double shorten(Cylinder beam, AABB obstacle, double limit) {
        if (!beam.withLength(limit).intersects(obstacle)) return limit;
        if (beam.withLength(0).intersects(obstacle)) return 0;
        double low = 0, high = limit;
        while (high - low > BLOCK_PRECISION) {
            double middle = (low + high) * 0.5;
            if (beam.withLength(middle).intersects(obstacle)) high = middle;
            else low = middle;
        }
        return low;
    }

    // 依据 Eberly《Intersection of a Box and a Finite Cylinder》§3 的几何方法独立实现。
    // 先用端面裁剪盒，投影其顶点至法平面，再测原点到投影凸包的距离。
    private static boolean cylinderBox(Cylinder cylinder, AABB box) {
        Vec3 axis = cylinder.direction;
        Vec3 center = box.getCenter().subtract(cylinder.origin);
        double projected = center.dot(axis);
        double extent = Math.abs(axis.x) * box.getXsize() * 0.5
                + Math.abs(axis.y) * box.getYsize() * 0.5 + Math.abs(axis.z) * box.getZsize() * 0.5;
        if (projected + extent < -EPS || projected - extent > cylinder.length + EPS) return false;
        // 轴线穿过盒时必然相交；只用于布尔窄筛
        if (axisIntersectsBox(cylinder, box)) return true;
        double distanceSquared = segmentBoxDistanceSquared(cylinder.origin, cylinder.end(), box);
        double squaredRadius = cylinder.radius * cylinder.radius;
        if (distanceSquared > squaredRadius + EPS) return false;
        // 盒完全位于两端面内时，胶囊与平端圆柱的相交结果相同，无需端面裁剪及投影凸包。
        // 靠近侧壁或端面的数值边界保留原路径，尤其避免远世界坐标的消减误差改变切线判定。
        double margin = Math.max(EPS, 8 * Math.max(Math.ulp(cylinder.origin.x),
                Math.max(Math.ulp(cylinder.origin.y), Math.ulp(cylinder.origin.z))));
        if (projected - extent > margin && projected + extent < cylinder.length - margin
                && distanceSquared < squaredRadius - margin * Math.max(1, 2 * cylinder.radius)) return true;
        Vec3 right = axis.cross(Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
        Vec3 up = axis.cross(right);
        Vec3[] vertices = new Vec3[8];
        double[] along = new double[8];
        List<Point> points = new ArrayList<>(24);
        for (int i = 0; i < 8; i++) {
            Vec3 v = new Vec3((i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ).subtract(cylinder.origin);
            vertices[i] = v;
            along[i] = v.dot(axis);
            if (along[i] >= -EPS && along[i] <= cylinder.length + EPS) points.add(project(v, right, up));
        }
        for (int i = 0; i < 8; i++) {
            for (int bit : new int[]{1, 2, 4}) {
                int j = i ^ bit;
                if (j < i) continue;
                double delta = along[j] - along[i];
                if (Math.abs(delta) <= EPS) continue;
                for (double plane : new double[]{0, cylinder.length}) {
                    double t = (plane - along[i]) / delta;
                    if (t >= -EPS && t <= 1 + EPS) {
                        points.add(project(vertices[i].lerp(vertices[j], Math.clamp(t, 0, 1)), right, up));
                    }
                }
            }
        }
        if (points.isEmpty()) return false;
        for (Point point : points) if (point.x * point.x + point.y * point.y <= squaredRadius + EPS) return true;
        points.sort(Comparator.comparingDouble(Point::x).thenComparingDouble(Point::y));
        Point[] hull = new Point[points.size() * 2];
        int size = 0;
        for (Point point : points) {
            while (size >= 2 && cross(hull[size - 2], hull[size - 1], point) <= EPS) size--;
            hull[size++] = point;
        }
        int lower = size;
        for (int i = points.size() - 2; i >= 0; i--) {
            Point point = points.get(i);
            while (size > lower && cross(hull[size - 2], hull[size - 1], point) <= EPS) size--;
            hull[size++] = point;
        }
        if (size > 1) size--;
        if (size == 1) return false;
        boolean inside = size >= 3;
        Point origin = new Point(0, 0);
        for (int i = 0; i < size; i++) {
            Point a = hull[i], b = hull[(i + 1) % size];
            if (cross(a, b, origin) < -EPS) inside = false;
            double vx = b.x - a.x, vy = b.y - a.y;
            double denominator = vx * vx + vy * vy;
            double t = denominator <= EPS ? 0 : Math.clamp(-(a.x * vx + a.y * vy) / denominator, 0, 1);
            double px = a.x + t * vx, py = a.y + t * vy;
            if (px * px + py * py <= squaredRadius + EPS) return true;
        }
        return inside;
    }

    private static boolean axisIntersectsBox(Cylinder cylinder, AABB box) {
        double near = 0, far = cylinder.length;
        for (int coordinate = 0; coordinate < 3; coordinate++) {
            double origin = coordinate == 0 ? cylinder.origin.x : coordinate == 1 ? cylinder.origin.y : cylinder.origin.z;
            double direction = coordinate == 0 ? cylinder.direction.x : coordinate == 1 ? cylinder.direction.y : cylinder.direction.z;
            double minimum = coordinate == 0 ? box.minX : coordinate == 1 ? box.minY : box.minZ;
            double maximum = coordinate == 0 ? box.maxX : coordinate == 1 ? box.maxY : box.maxZ;
            if (direction == 0) {
                if (origin < minimum || origin > maximum) return false;
            } else {
                double first = (minimum - origin) / direction;
                double second = (maximum - origin) / direction;
                near = Math.max(near, Math.min(first, second));
                far = Math.min(far, Math.max(first, second));
                if (near > far) return false;
            }
        }
        return true;
    }

    private record Point(double x, double y) {}
    private static Point project(Vec3 v, Vec3 right, Vec3 up) { return new Point(v.dot(right), v.dot(up)); }
    private static double cross(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    // 线段到 AABB 的距离在各个盒面穿越参数之间为二次函数，逐区间求极小值。
    private static double segmentBoxDistanceSquared(Vec3 start, Vec3 end, AABB box) {
        return distanceSquared(closestSegmentPoint(start, end, box), box);
    }

    private static Vec3 closestSegmentPoint(Vec3 start, Vec3 end, AABB box) {
        double[] p = {start.x, start.y, start.z};
        double[] v = {end.x - start.x, end.y - start.y, end.z - start.z};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        double[] cuts = new double[8];
        cuts[0] = 0; cuts[1] = 1;
        int count = 2;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(v[axis]) < EPS) continue;
            for (double face : new double[]{min[axis], max[axis]}) {
                double t = (face - p[axis]) / v[axis];
                if (t > 0 && t < 1) cuts[count++] = t;
            }
        }
        Arrays.sort(cuts, 0, count);
        Vec3 closest = distanceSquared(start, box) <= distanceSquared(end, box) ? start : end;
        double best = distanceSquared(closest, box);
        for (int i = 1; i < count; i++) {
            double low = cuts[i - 1], high = cuts[i], middle = (low + high) * 0.5;
            double a = 0, b = 0;
            for (int axis = 0; axis < 3; axis++) {
                double value = p[axis] + v[axis] * middle;
                if (value >= min[axis] && value <= max[axis]) continue;
                double face = value < min[axis] ? min[axis] : max[axis];
                a += v[axis] * v[axis];
                b += v[axis] * (p[axis] - face);
            }
            double t = a <= EPS ? middle : Math.clamp(-b / a, low, high);
            Vec3 point = start.lerp(end, t);
            double squared = distanceSquared(point, box);
            if (squared < best) { best = squared; closest = point; }
        }
        return closest;
    }

    private static double distanceSquared(Vec3 p, AABB box) {
        double x = Math.max(Math.max(box.minX - p.x, p.x - box.maxX), 0);
        double y = Math.max(Math.max(box.minY - p.y, p.y - box.maxY), 0);
        double z = Math.max(Math.max(box.minZ - p.z, p.z - box.maxZ), 0);
        return x * x + y * y + z * z;
    }

    private static void finite(Vec3 p) {
        if (p == null || !Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)) {
            throw new IllegalArgumentException("坐标必须有限");
        }
    }
    private static void positive(double radius) {
        if (!Double.isFinite(radius) || radius <= 0) throw new IllegalArgumentException("半径必须为有限正数");
    }
}
