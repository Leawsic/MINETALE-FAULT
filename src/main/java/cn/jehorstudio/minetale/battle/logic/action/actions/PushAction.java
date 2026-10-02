package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

import java.util.Objects;

public record PushAction(
        long stackId,
        BattleAction action
) implements BattleAction {
    public PushAction {
        Objects.requireNonNull(action, "action");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        return context.instance().actionStacks().push(this.stackId, this.action);
    }
}
