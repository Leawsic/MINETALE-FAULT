package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

import java.util.Objects;

public record WaitForActionConflictAction(String conflictKey) implements BattleAction {
    public WaitForActionConflictAction {
        if (Objects.requireNonNull(conflictKey, "conflictKey").isBlank()) {
            throw new IllegalArgumentException("conflictKey must not be blank.");
        }
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        return context.instance().actionStacks().hasConflict(this.conflictKey, context.stackId())
                ? BattleActionResult.RUNNING
                : BattleActionResult.COMPLETED;
    }
}
