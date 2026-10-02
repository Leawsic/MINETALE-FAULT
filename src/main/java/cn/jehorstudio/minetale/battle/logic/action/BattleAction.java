package cn.jehorstudio.minetale.battle.logic.action;

import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

public interface BattleAction {
    BattleActionResult run(BattleActionContext context);

    default BattleActionConflict conflict() {
        return BattleActionConflict.none();
    }

    default String actionType() {
        return getClass().getSimpleName();
    }
}
