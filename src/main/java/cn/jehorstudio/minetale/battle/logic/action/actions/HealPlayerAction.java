package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateCache;

public final class HealPlayerAction implements BattleAction {
    private final double amount;

    public HealPlayerAction(double amount) {
        if (!Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount must be finite.");
        }
        this.amount = Math.max(0.0D, amount);
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        ActorTargetContext hit = context.eventContext(ActorTargetContext.class);
        Actor target = context.instance().stateCache().actors().resolve(hit.target()).orElse(null);
        if (target == null || target.type() != ActorType.PLAYER_SOUL || this.amount <= 0.0D) {
            return BattleActionResult.CANCELLED;
        }

        PlayerStateCache players = context.instance().stateCache().players();
        if (!players.matchesSoul(target.ref())) {
            return BattleActionResult.CANCELLED;
        }
        players.setHp((int) (players.hp() + this.amount));
        return BattleActionResult.COMPLETED;
    }
}
