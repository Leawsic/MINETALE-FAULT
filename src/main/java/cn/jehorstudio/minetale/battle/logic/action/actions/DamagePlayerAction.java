package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.action.BattleRenderRequest;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.rules.RuleResolvedView;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateCache;

import java.util.Locale;
import java.util.Objects;

public final class DamagePlayerAction implements BattleAction {
    private static final double MOVEMENT_EPSILON = 1.0E-8D;
    private static final double HIT_FLASH_SECONDS = 0.12D;
    private static final double HIT_FLASH_INTENSITY = 0.10D;
    private static final int HIT_FLASH_RGB = 0xFFFFFF;
    private static final double HIT_SHAKE_SECONDS = 0.18D;
    private static final double HIT_SHAKE_INTENSITY = 0.35D;

    private final double amount;
    private final String attackType;

    public DamagePlayerAction(double amount, String attackType) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite.");
        }
        this.amount = Math.max(0.0D, amount);
        this.attackType = Objects.requireNonNullElse(attackType, "white").toLowerCase(Locale.ROOT);
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        ActorTargetContext hit = context.eventContext(ActorTargetContext.class);
        Actor target = context.instance().stateCache().actors().resolve(hit.target()).orElse(null);
        if (target == null || target.type() != ActorType.PLAYER_SOUL || this.amount <= 0.0D) {
            return BattleActionResult.CANCELLED;
        }
        if (!shouldDamage(target)) {
            return BattleActionResult.CANCELLED;
        }

        PlayerStateCache players = context.instance().stateCache().players();
        if (!players.matchesSoul(target.ref()) || players.invincible()) {
            return BattleActionResult.CANCELLED;
        }

        players.setHp((int) (players.hp() - this.amount));
        double invincibleSeconds = RuleResolvedView
                .resolve(context.instance().stateCache().rules().snapshot())
                .damage()
                .invincibleSeconds();
        players.setInvincibleTimeSeconds(invincibleSeconds);
        BattleActionResult result = new SetPlayerInvincibleAction(true).run(context);
        if (result == BattleActionResult.COMPLETED) {
            context.instance().requestRenderAction(BattleRenderRequest.screenFlash(
                    HIT_FLASH_SECONDS,
                    HIT_FLASH_INTENSITY,
                    HIT_FLASH_RGB
            ));
            context.instance().requestRenderAction(BattleRenderRequest.screenShake(
                    HIT_SHAKE_SECONDS,
                    HIT_SHAKE_INTENSITY
            ));
        }
        return result;
    }

    private boolean shouldDamage(Actor target) {
        boolean moving = target.velocity().lengthSquared() > MOVEMENT_EPSILON;
        return switch (this.attackType) {
            case "none", "green" -> false;
            case "blue" -> moving;
            case "orange" -> !moving;
            default -> true;
        };
    }
}
