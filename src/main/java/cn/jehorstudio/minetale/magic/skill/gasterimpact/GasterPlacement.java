package cn.jehorstudio.minetale.magic.skill.gasterimpact;

import cn.jehorstudio.minetale.magic.skill.MagicTargeting;
import cn.jehorstudio.minetale.magic.skill.Skill;
import cn.jehorstudio.minetale.magic.spell.gasterblaster.GasterBlaster;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// 一次释放只做有界候选搜索；占位由现存炮实体提供，不另建需要同步清理的占位表。
final class GasterPlacement {
    private GasterPlacement() {}

    static Vec3 choose(Entity caster, Skill.Aim aim, float scale) {
        ServerLevel level = (ServerLevel) caster.level();
        Vec3 placementCenter = aim.target() == null ? caster.getEyePosition() : aim.point();
        Vec3 forward = Vec3.directionFromRotation(0.0F, caster.getYRot());
        Vec3 side = new Vec3(forward.z, 0, -forward.x);
        double targetRadius = aim.target() == null ? 0 : aim.target().getBoundingBox().getSize() * 0.5;
        double near = Math.max(8 + 2.6 * scale, targetRadius + 1.6 * scale + 4);
        double lowPitchBias = Math.clamp((scale - 1) / 9.0, 0, 1);
        double radialWidth = 4 + 0.6 * scale;
        double lowPitchExtension = 4 * scale * lowPitchBias;
        double far = near + radialWidth + lowPitchExtension;
        List<GasterBlaster> neighbors = neighbors(level, placementCenter, far);
        List<GasterBlaster> group = neighbors.stream().filter(c -> c.aimsAt(aim.point(), aim.target())).toList();
        boolean visibleGroup = group.stream().anyMatch(c -> c.ownedBy(caster) && visibility(caster, c.position()) > 0.5);
        List<Candidate> candidates = new ArrayList<>();
        for (int sample = 0; sample < 48; sample++) {
            // 尺寸越大，越偏向低俯仰
            double height = Math.pow((sample + level.random.nextDouble()) / 48, 1 + 2 * lowPitchBias);
            double angle = level.random.nextDouble() * Math.PI * 2;
            double horizontal = Math.sqrt(1 - height * height);
            Vec3 outward = new Vec3(horizontal * Math.cos(angle), height, horizontal * Math.sin(angle));
            if (aim.target() == null) {
                // 自由瞄准在水平朝向的左右前方 30..60 度选址
                angle = Math.PI / 6 + level.random.nextDouble() * Math.PI / 6;
                height *= 0.35;
                horizontal = Math.sqrt(1 - height * height);
                outward = forward.scale(horizontal * Math.cos(angle))
                        .add(side.scale(horizontal * Math.sin(angle) * (sample % 2 == 0 ? 1 : -1)))
                        .add(0, height, 0);
            }
            double nearestDot = -1;
            for (GasterBlaster old : group) {
                nearestDot = Math.max(nearestDot, outward.dot(old.position().subtract(placementCenter).normalize()));
            }
            double spread = Math.clamp((1 - nearestDot) / 0.5, 0, 1);
            for (int radial = 0; radial < 2; radial++) {
                // 大炮低角度时向外寻找空间
                double distance = near + lowPitchExtension * (1 - height)
                        + radialWidth * (radial + level.random.nextDouble()) / 2;
                Vec3 position = placementCenter.add(outward.scale(distance));
                AABB space = GasterBlaster.occupiedBounds(position, aim.point().subtract(position).normalize(), scale);
                if (!validOrigin(level, position)) continue;
                double visible = viewAlignment(caster, position);
                double weight = (0.3 + 0.7 * spread) * (1 + (visibleGroup ? 0.5 : 3) * visible);
                candidates.add(new Candidate(position, space, weight));
            }
        }
        Vec3 best = null;
        int bestQuality = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < 24 && !candidates.isEmpty(); attempt++) {
            double total = candidates.stream().mapToDouble(Candidate::weight).sum();
            double draw = level.random.nextDouble() * total;
            int index = 0;
            while (index < candidates.size() - 1 && (draw -= candidates.get(index).weight) > 0) index++;
            Candidate candidate = candidates.remove(index);
            Vec3 direction = aim.point().subtract(candidate.position).normalize();
            AABB body = GasterBlaster.bodyBounds(candidate.position, direction, scale);
            int quality;
            if (clear(level, candidate.space) && separated(candidate.space, candidate.position, neighbors, true)
                    && outsideActors(candidate.space, caster, aim)) quality = 0;
            else if (clear(level, body) && separated(body, candidate.position, neighbors, false)
                    && outsideActors(body, caster, aim)) quality = 1;
            else if (clear(level, new AABB(candidate.position, candidate.position).inflate(0.125))) quality = 2;
            else quality = 3;
            if (quality < bestQuality) {
                best = candidate.position;
                bestQuality = quality;
            }
            if (quality == 0) return best;
        }
        if (bestQuality <= 2) return best;
        // 远处候选全未加载、超出边界或炮口全部埋住时，回退到发射者附近
        for (int step = 0; step < 10; step++) {
            Vec3 position = step == 9 ? caster.position() : step == 8 ? caster.getEyePosition()
                    : caster.getEyePosition().add(caster.getLookAngle().scale(2 + step)).add(0, 1, 0);
            if (!validOrigin(level, position)) continue;
            if (clear(level, new AABB(position, position).inflate(0.125))) return position;
            if (best == null) best = position;
        }
        return best;
    }

    private static boolean outsideActors(AABB bounds, Entity caster, Skill.Aim aim) {
        return !bounds.intersects(caster.getBoundingBox()) && (aim.target() == null || !bounds.intersects(aim.target().getBoundingBox()));
    }

    private static boolean validOrigin(ServerLevel level, Vec3 position) {
        BlockPos pos = BlockPos.containing(position);
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos) && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    static boolean clear(ServerLevel level, AABB bounds) {
        if (!available(level, bounds)) return false;
        return noBlockCollision(level, bounds) && level.getEntityCollisions(null, bounds).isEmpty();
    }

    private static boolean noBlockCollision(ServerLevel level, AABB bounds) {
        if (level.isDebug()) return level.noBlockCollision(null, bounds);
        // 与 BlockCollisions 的查询外扩和边缘规则一致，只跳过整个空气 section。
        int minX = Mth.floor(bounds.minX - 1.0E-7) - 1, maxX = Mth.floor(bounds.maxX + 1.0E-7) + 1;
        int minY = Mth.floor(bounds.minY - 1.0E-7) - 1, maxY = Mth.floor(bounds.maxY + 1.0E-7) + 1;
        int minZ = Mth.floor(bounds.minZ - 1.0E-7) - 1, maxZ = Mth.floor(bounds.maxZ + 1.0E-7) + 1;
        int firstSection = Math.max(level.getMinSectionY(), minY >> 4);
        int lastSection = Math.min(level.getMaxSectionY(), maxY >> 4);
        var context = CollisionContext.empty();
        var boxShape = Shapes.create(bounds);
        var pos = new BlockPos.MutableBlockPos();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
            var getter = level.getChunkForCollisions(cx, cz);
            if (getter == null) continue;
            if (!(getter instanceof LevelChunk chunk)) return level.noBlockCollision(null, bounds);
            for (int section = firstSection; section <= lastSection; section++) {
                if (chunk.getSection(level.getSectionIndexFromSectionY(section)).hasOnlyAir()) continue;
                for (int y = Math.max(minY, section << 4); y <= Math.min(maxY, (section << 4) + 15); y++) {
                    for (int z = Math.max(minZ, cz << 4); z <= Math.min(maxZ, (cz << 4) + 15); z++) {
                        for (int x = Math.max(minX, cx << 4); x <= Math.min(maxX, (cx << 4) + 15); x++) {
                            int edges = (x == minX || x == maxX ? 1 : 0) + (y == minY || y == maxY ? 1 : 0)
                                    + (z == minZ || z == maxZ ? 1 : 0);
                            if (edges == 3) continue;
                            pos.set(x, y, z);
                            var state = chunk.getBlockState(pos);
                            if (edges == 1 && !state.hasLargeCollisionShape() || edges == 2 && !state.is(Blocks.MOVING_PISTON)) continue;
                            var shape = context.getCollisionShape(state, level, pos);
                            if (shape == Shapes.block()) {
                                if (bounds.intersects(x, y, z, x + 1, y + 1, z + 1)) return false;
                            } else if (!shape.isEmpty() && Shapes.joinIsNotEmpty(shape.move(pos), boxShape, BooleanOp.AND)) return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static List<GasterBlaster> neighbors(ServerLevel level, Vec3 center, double radius) {
        // 配置允许最大 16 倍，包含远处炮的完整出场包络
        return level.getEntitiesOfClass(GasterBlaster.class, new AABB(center, center).inflate(radius + 128));
    }

    private static boolean separated(AABB space, Vec3 position, List<GasterBlaster> neighbors, boolean animation) {
        for (GasterBlaster old : neighbors) {
            AABB occupied = animation ? old.occupiedBounds() : GasterBlaster.bodyBounds(old.position(), old.direction(), old.scale());
            if (space.intersects(occupied) || position.distanceToSqr(old.position()) < 9) return false;
        }
        return true;
    }

    private static double viewAlignment(Entity caster, Vec3 position) {
        Vec3 offset = position.subtract(caster.getEyePosition());
        return Math.clamp((caster.getLookAngle().dot(offset.normalize()) - 0.5) / 0.4, 0, 1);
    }

    private static double visibility(Entity caster, Vec3 position) {
        double alignment = viewAlignment(caster, position);
        return alignment > 0 && MagicTargeting.visible(caster, position) ? alignment : 0;
    }

    private static boolean available(ServerLevel level, AABB bounds) {
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        if (!level.isInWorldBounds(min) || !level.isInWorldBounds(max)
                || !level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max)) return false;
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) if (!level.hasChunk(x, z)) return false;
        }
        return true;
    }

    private record Candidate(Vec3 position, AABB space, double weight) {}
}
