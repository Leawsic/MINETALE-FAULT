package cn.jehorstudio.minetale.magic.skill;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * 可注册技能的服务端契约
 * 所有回调都在服务端线程运行，消耗由 MagicCasting 统一结算。
 */
public interface Skill {
    /** 返回技能定义；ID 与分类在注册后必须保持稳定。数值在施法开始时冻结。 */
    Definition definition();

    /** 为一次施法创建独立会话；不得在构造时产生世界副作用。 */
    Cast createCast();

    /** 功率单位为魔力/秒，起手费用和功率均先于消耗倍率计算。 */
    record Definition(ResourceLocation id, ResourceLocation school, ResourceLocation spell,
                      ResourceLocation role, double activationCost, double powerPerSecond) {
        public Definition {
            Objects.requireNonNull(id);
            Objects.requireNonNull(school);
            Objects.requireNonNull(spell);
            Objects.requireNonNull(role);
            if (!Double.isFinite(activationCost) || activationCost < 0
                    || !Double.isFinite(powerPerSecond) || powerPerSecond < 0) {
                throw new IllegalArgumentException("技能消耗必须为有限非负值");
            }
        }
    }

    /** 会话可立即完成、继续运行或请求释放；无需按输出方式建立继承层次。 */
    enum Step { CONTINUE, RELEASE, COMPLETE }

    /** 统一的领域结果；NO_POSITION 表示释放失败，应退还本次已消耗魔力。 */
    enum Result { SUCCESS, BUSY, UNAVAILABLE, NO_MANA, NO_POSITION, INVALID, CANCELLED }

    /**
     * 一次施法独占的可变状态。MagicCasting 调用 start 一次，随后每个成功消耗的 Tick 调用 tick。
     * RELEASE 转到 release，COMPLETE 直接结束；主动取消和实体离开 Level 调用 cancel。
     * release/cancel 前 MagicCasting 已消费会话，回调不得自行扣除同一份魔力。
     */
    interface Cast {
        /**
         * @param context 开始时的服务端上下文，elapsedTicks 为 0
         * @return 下一步生命周期动作
         */
        default Step start(Context context) { return Step.CONTINUE; }
        /**
         * @param context 当前成功消耗 Tick 的上下文
         * @return 下一步生命周期动作
         */
        default Step tick(Context context) { return Step.CONTINUE; }
        /**
         * 执行释放时的世界副作用；由调用者保证仅调用一次。
         * @param context 包含服务端累计消耗 Tick，可在此刻解析瞄准
         * @return SUCCESS 保留消耗，其余结果使 MagicCasting 退还本次累计消耗；失败前应撤销已产生的副作用
         */
        Result release(Context context);
        /** @param context 取消时的上下文；释放会话自有资源，取消不返还。 */
        default void cancel(Context context) {}
        /**
         * @param context 当前上下文
         * @return 0..1 的有限显示进度，不得产生副作用
         */
        default float progress(Context context) { return 0; }
    }

    /** 本次回调的只读上下文；elapsedTicks 仅累计服务端成功消耗的运行 Tick。 */
    record Context(Entity caster, int elapsedTicks) {}

    /** point 为世界坐标；target 可为空，表示自由瞄准的位置。 */
    record Aim(Vec3 point, Entity target) {}
}
