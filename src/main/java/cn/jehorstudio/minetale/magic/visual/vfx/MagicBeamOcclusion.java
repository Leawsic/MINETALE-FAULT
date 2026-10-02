package cn.jehorstudio.minetale.magic.visual.vfx;

import cn.jehorstudio.minetale.magic.collision.MagicCollision;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// 一束光拥有一张沿发射轴取样的遮挡图；相机深度只能遮住眼前的墙，不能阻止墙后的光继续传播。
// 由 MagicBeamRenderer 释放；每个世界 Tick 至多重建一次，多个渲染 Pass 共用
final class MagicBeamOcclusion implements AutoCloseable {
    private final DynamicTexture texture;
    private final int size;
    private long tick = Long.MIN_VALUE;
    private final Long2ObjectOpenHashMap<SectionStamp> sections = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<VoxelShape> dynamicShapes = new Long2ObjectOpenHashMap<>();
    private double borderMinX, borderMaxX, borderMinZ, borderMaxZ;
    private float previousExtent;
    private long revision;
    private float minimumStop;

    private record SectionStamp(LevelChunkSection section, long revision) {
        static long revision(LevelChunkSection section) {
            return section == null ? 0 : ((BeamTerrainRevision) section.getStates()).minetale$beamRevision();
        }
    }
    private Level previousLevel;
    private Vec3 previousOrigin;
    private Vec3 previousAxis;
    private float previousRange;

    MagicBeamOcclusion(float extent) {
        size = Math.clamp((int) Math.ceil(extent * 2 / 0.25F), 16, 64);
        texture = new DynamicTexture("Magic beam obstruction", size, size, false);
    }

    DynamicTexture texture() { return texture; }
    int size() { return size; }
    int cell(int x, int y) { return texture.getPixels().getPixel(x, y); }
    long revision() { return revision; }
    float minimumStop() { return minimumStop; }

    void update(Level level, Vec3 origin, Vec3 axis, float extent, float range) {
        boolean sameGeometry = previousLevel == level && origin.equals(previousOrigin)
                && axis.equals(previousAxis) && range == previousRange && extent == previousExtent;
        if (sameGeometry && tick == level.getGameTime()) return;
        tick = level.getGameTime();
        // 只复查路径经过的 section 身份与写入版本；未加载的 section 也记录，加载后立即失效。
        // 动态形状没有原版 BlockState 静态碰撞缓存，逐 Tick 求实际形状。
        if (sameGeometry && unchanged(level)) {
            return;
        }
        previousLevel = level;
        previousOrigin = origin;
        previousAxis = axis;
        previousRange = range;
        previousExtent = extent;
        if (clearEnvelope(level, origin, axis, extent, range)) {
            var pixels = texture.getPixels();
            int depth = Math.clamp((int) Math.floor(range * 512), 0, 0xFFFFFF);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels.setPixel(x, y, depth);
            minimumStop = depth / 512.0F;
            revision++;
            texture.upload();
            return;
        }
        var shapes = new Long2ObjectOpenHashMap<VoxelShape>();
        Vec3 right = axis.cross(Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
        Vec3 up = axis.cross(right);
        var pixels = texture.getPixels();
        minimumStop = range;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            double u = ((x + 0.5) / size * 2 - 1) * extent;
            double v = ((y + 0.5) / size * 2 - 1) * extent;
            Vec3 start = origin.add(right.scale(u)).add(up.scale(v));
            BlockHitResult hit = trace(level, start, axis, range, shapes);
            double distance = hit == null ? range : Math.max(0, hit.getLocation().subtract(start).dot(axis));
            // RGB 保存以 1/512 格为单位的 24 位距离，A 保存方块表面的法线轴，0 表示没有表面。
            int depth = Math.clamp((int) Math.floor(distance * 512), 0, 0xFFFFFF);
            int normal = hit == null ? 0 : switch (hit.getDirection().getAxis()) { case X -> 1; case Y -> 2; case Z -> 3; };
            pixels.setPixel(x, y, (normal << 24) | depth);
            double correction = 0;
            if (normal > 0) {
                net.minecraft.core.Direction.Axis coordinate = switch (normal) {
                    case 1 -> net.minecraft.core.Direction.Axis.X;
                    case 2 -> net.minecraft.core.Direction.Axis.Y;
                    default -> net.minecraft.core.Direction.Axis.Z;
                };
                double a = axis.get(coordinate);
                if (Math.abs(a) > 0.00001) {
                    correction = (Math.abs(right.get(coordinate)) + Math.abs(up.get(coordinate))) * extent / size / Math.abs(a);
                }
            }
            minimumStop = Math.min(minimumStop, (float) Math.max(0, depth / 512.0 - correction));
        }
        captureDependencies(level, shapes);
        revision++;
        texture.upload();
    }

    private boolean clearEnvelope(Level level, Vec3 origin, Vec3 axis, float extent, float range) {
        // DebugLevel 的显示方块不来自 section palette，保留原版逐格路径。
        if (level.isDebug()) return false;
        // 包含方形采样面和原版 DDA 的端点外扩；任何未知或潜在阻挡都回退逐格扫描。
        AABB box = new AABB(origin, origin.add(axis.scale(range))).inflate(extent * Math.sqrt(2) + range * 1.0E-7 + 1.0E-5);
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        var border = level.getWorldBorder();
        if (!level.isInWorldBounds(min) || !level.isInWorldBounds(max)
                || !border.isWithinBounds(min) || !border.isWithinBounds(max)) return false;
        sections.clear();
        dynamicShapes.clear();
        // 按 16 格短段覆盖采样范围，避免长斜束的大 AABB 把无关空间按平方/立方纳入缓存。
        int parts = Math.max(1, (int) Math.ceil(range / 16));
        Vec3 from = origin;
        for (int part = 1; part <= parts; part++) {
            Vec3 to = origin.add(axis.scale(range * (double) part / parts));
            AABB segment = new AABB(from, to).inflate(extent * Math.sqrt(2) + range * 1.0E-7 + 1.0E-5);
            BlockPos segmentMin = BlockPos.containing(segment.minX, segment.minY, segment.minZ);
            BlockPos segmentMax = BlockPos.containing(segment.maxX, segment.maxY, segment.maxZ);
            for (int x = segmentMin.getX() >> 4; x <= segmentMax.getX() >> 4; x++)
                for (int y = segmentMin.getY() >> 4; y <= segmentMax.getY() >> 4; y++)
                    for (int z = segmentMin.getZ() >> 4; z <= segmentMax.getZ() >> 4; z++) {
                        long key = SectionPos.asLong(x, y, z);
                        if (sections.containsKey(key)) continue;
                        LevelChunkSection section = section(level, key);
                        if (section == null || section.maybeHas(MagicCollision::mayBlockBeam)) return false;
                        sections.put(key, new SectionStamp(section, SectionStamp.revision(section)));
                    }
            from = to;
        }
        borderMinX = border.getMinX(); borderMaxX = border.getMaxX();
        borderMinZ = border.getMinZ(); borderMaxZ = border.getMaxZ();
        return true;
    }

    private boolean unchanged(Level level) {
        var border = level.getWorldBorder();
        if (border.getMinX() != borderMinX || border.getMaxX() != borderMaxX
                || border.getMinZ() != borderMinZ || border.getMaxZ() != borderMaxZ) return false;
        for (var entry : sections.long2ObjectEntrySet()) {
            LevelChunkSection current = section(level, entry.getLongKey());
            SectionStamp previous = entry.getValue();
            if (current != previous.section || SectionStamp.revision(current) != previous.revision) return false;
        }
        var pos = new BlockPos.MutableBlockPos();
        for (var entry : dynamicShapes.long2ObjectEntrySet()) {
            pos.set(entry.getLongKey());
            VoxelShape current = shape(level, pos), previous = entry.getValue();
            if (current != previous && !current.toAabbs().equals(previous.toAabbs())) return false;
        }
        return true;
    }

    private void captureDependencies(Level level, Long2ObjectOpenHashMap<VoxelShape> shapes) {
        sections.clear();
        dynamicShapes.clear();
        var border = level.getWorldBorder();
        borderMinX = border.getMinX(); borderMaxX = border.getMaxX();
        borderMinZ = border.getMinZ(); borderMaxZ = border.getMaxZ();
        var pos = new BlockPos.MutableBlockPos();
        for (var entry : shapes.long2ObjectEntrySet()) {
            pos.set(entry.getLongKey());
            long key = SectionPos.asLong(pos);
            SectionStamp stamp = sections.get(key);
            if (stamp == null) {
                LevelChunkSection section = section(level, key);
                stamp = new SectionStamp(section, SectionStamp.revision(section));
                sections.put(key, stamp);
            }
            if (level.isDebug() || stamp.section != null && stamp.section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)
                    .getBlock().hasDynamicShape()) dynamicShapes.put(entry.getLongKey(), entry.getValue());
        }
    }

    private static LevelChunkSection section(Level level, long key) {
        int y = SectionPos.y(key) << 4;
        if (level.isOutsideBuildHeight(y)) return null;
        var chunk = level.getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false);
        return chunk == null ? null : chunk.getSection(level.getSectionIndex(y));
    }

    private static VoxelShape shape(Level level, BlockPos pos) {
        return MagicCollision.beamBlockShape(level, pos);
    }

    static BlockHitResult trace(Level level, Vec3 start, Vec3 axis, float range,
                                Long2ObjectOpenHashMap<VoxelShape> shapes) {
        Vec3 end = start.add(axis.scale(range));
        return BlockGetter.traverseBlocks(start, end, level, (world, pos) -> {
            VoxelShape shape = shapes.get(pos.asLong());
            if (shape == null) {
                // 客户端与命中共用穿透规则，未加载区域挡光
                shape = shape(world, pos);
                shapes.put(pos.asLong(), shape);
            }
            return shape.clip(start, end, pos);
        }, world -> null);
    }

    @Override public void close() {
        texture.close();
    }
}
