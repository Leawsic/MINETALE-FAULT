package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateCache;

public final class SetPlayerInvincibleAction implements BattleAction {
    private final boolean invincible;

    public SetPlayerInvincibleAction(boolean invincible) {
        this.invincible = invincible;
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        PlayerStateCache players = context.instance().stateCache().players();
        if (!players.initialized()) {
            return BattleActionResult.CANCELLED;
        }
        players.setInvincible(this.invincible);
        if (this.invincible) {
            players.setInvincibleStartedAtBattleTick(
                    context.instance().timeline().battleTick()
            );
        }
        return BattleActionResult.COMPLETED;
    }
}
