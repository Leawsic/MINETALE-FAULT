package cn.jehorstudio.minetale.magic.skill.gasterimpact;

import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.MagicConfig;
import cn.jehorstudio.minetale.magic.skill.Skill;
import cn.jehorstudio.minetale.magic.skill.MagicCasting;
import cn.jehorstudio.minetale.magic.skill.MagicTargeting;
import net.minecraft.world.entity.Entity;
import cn.jehorstudio.minetale.magic.spell.gasterblaster.GasterBlaster;
import net.minecraft.world.phys.Vec3;

public final class GasterImpact implements Skill {
    private static final double FREE_AIM_DISTANCE = 120;

    @Override
    public Definition definition() {
        return new Definition(Magic.GASTER_IMPACT, Magic.GASTER_SCHOOL, Magic.GASTER_BLASTER, Magic.id("attack"),
                MagicConfig.ACTIVATION_COST,
                MagicConfig.POWER);
    }

    @Override
    public Cast createCast() {
        int maximumTicks = MagicConfig.CHARGE_TICKS;
        return new Cast() {
            @Override public Step tick(Context context) {
                return context.elapsedTicks() >= maximumTicks ? Step.RELEASE : Step.CONTINUE;
            }
            @Override public float progress(Context context) {
                return Math.clamp((float) context.elapsedTicks() / maximumTicks, 0, 1);
            }
            @Override public Result release(Context context) {
                Aim aim = resolveAim(context.caster());
                GasterBlaster.Parameters parameters = parameters(context.caster(), progress(context));
                Vec3 position = GasterPlacement.choose(context.caster(), aim, parameters.scale());
                return position != null && GasterBlaster.summon(context.caster(),
                        new GasterBlaster.Target(aim.point(), aim.target()), position, parameters) != null
                        ? Result.SUCCESS : Result.NO_POSITION;
            }
        };
    }

    // 锁定优先、准星实体次之、固定距离兜底
    private static Aim resolveAim(Entity caster) {
        Entity locked = MagicCasting.lockedTarget(caster);
        if (locked != null) return new Aim(locked.getBoundingBox().getCenter(), locked);
        Entity pointed = MagicTargeting.pointedEntity(caster, MagicConfig.LOCK_RANGE);
        return pointed != null ? new Aim(pointed.getBoundingBox().getCenter(), pointed)
                : new Aim(caster.getEyePosition().add(caster.getLookAngle().scale(FREE_AIM_DISTANCE)), null);
    }

    /**
     * 将伽斯特冲击的 0..1 蓄力换算为 GB 参数。读取调用时的服务端配置
     * 射程在释放时取模拟距离乘以 16；客户端表现长度独立取有效渲染视距。
     * @param caster 提供服务端模拟距离的施法者
     * @param charge 有限的 0..1 蓄力比例
     * @return 本次法术使用的固定参数
     * @throws IllegalArgumentException 施法者不在服务端 Level，或比例超出范围
     */
    public static GasterBlaster.Parameters parameters(Entity caster, float charge) {
        if (!(caster.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            throw new IllegalArgumentException("GB 参数需要服务端施法者");
        }
        if (!Float.isFinite(charge) || charge < 0 || charge > 1) {
            throw new IllegalArgumentException("蓄力比例必须在 0..1 内");
        }
        return new GasterBlaster.Parameters(
                (float) (MagicConfig.MIN_SCALE + charge * (MagicConfig.MAX_SCALE - MagicConfig.MIN_SCALE)),
                (float) (MagicConfig.MIN_RADIUS + charge * (MagicConfig.MAX_RADIUS - MagicConfig.MIN_RADIUS)),
                level.getServer().getPlayerList().getSimulationDistance() * 16.0,
                (float) (MagicConfig.MIN_DAMAGE + charge * (MagicConfig.MAX_DAMAGE - MagicConfig.MIN_DAMAGE)),
                1 + 2 * charge, MagicConfig.WINDUP_TICKS, MagicConfig.BEAM_TICKS, MagicConfig.CLOSE_TICKS);
    }

}
