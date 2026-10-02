package cn.jehorstudio.minetale.magic.skill;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// 初次选择目标要求准星可见；已建立的锁定意向由 MagicCasting 维护，不随遮挡失效。
public final class MagicTargeting {
    private MagicTargeting() {}

    /**
     * 查询准星范围内最近的可选实体，供中键锁定和技能使用
     * 方块遮挡会截断查询，服务端仅访问已加载区块。须在所在 Level 的线程调用。
     * @param caster 提供眼部位置和视线方向的实体
     * @param range 有限且非负的查询距离，单位为格
     * @return 命中的存活、可拾取、非旁观 LivingEntity（不含自身）；未命中时返回 null
     * @throws IllegalArgumentException 距离不是有限非负值
     */
    public static Entity pointedEntity(Entity caster, double range) {
        if (!Double.isFinite(range) || range < 0) throw new IllegalArgumentException("查询距离必须为有限非负值");
        Vec3 eye = caster.getEyePosition();
        Vec3 end = eye.add(caster.getLookAngle().scale(range));
        Vec3 point = clip(caster, eye, end);
        double nearest = eye.distanceToSqr(point);
        Entity target = null;
        for (Entity candidate : caster.level().getEntities(caster, new AABB(eye, point).inflate(1),
                entity -> eligible(caster, entity))) {
            AABB box = candidate.getBoundingBox().inflate(0.15);
            Vec3 hit = box.contains(eye) ? eye : box.clip(eye, point).orElse(null);
            if (hit != null && eye.distanceToSqr(hit) < nearest) {
                nearest = eye.distanceToSqr(hit);
                target = candidate;
            }
        }
        return target;
    }

    private static boolean eligible(Entity caster, Entity target) {
        return target != caster && target instanceof LivingEntity && target.isAlive()
                && !target.isSpectator() && target.isPickable();
    }

    /** 查询服务端已加载区域内到世界坐标的方块视线 */
    public static boolean visible(Entity caster, Vec3 point) {
        Vec3 eye = caster.getEyePosition();
        return clip(caster, eye, point).distanceToSqr(point) < 1.0E-6;
    }

    private static Vec3 clip(Entity caster, Vec3 from, Vec3 requestedEnd) {
        Vec3 end = requestedEnd;
        if (caster.level() instanceof ServerLevel level) {
            // 原版 clip 会读取沿途方块；先限制到已加载区块，远距离瞄准不能触发区块生成。
            Vec3 unloaded = BlockGetter.traverseBlocks(from, end, level, (world, pos) -> {
                if (world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
                AABB cell = new AABB(pos);
                return cell.contains(from) ? from : cell.clip(from, requestedEnd).orElse(null);
            }, world -> null);
            if (unloaded != null) {
                if (unloaded.distanceToSqr(from) < 1.0E-8) return from;
                end = unloaded.subtract(requestedEnd.subtract(from).normalize().scale(1.0E-4));
            }
        }
        return caster.level().clip(new ClipContext(from, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, caster)).getLocation();
    }
}
