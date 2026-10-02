package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

public final class WaitSecondsAction implements BattleAction {
    private final double durationSeconds;

    private WaitTicksAction waitTicks;

    public WaitSecondsAction(double durationSeconds) {
        this.durationSeconds = Math.max(0.0D, durationSeconds);
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        if (this.waitTicks == null) {
            this.waitTicks = new WaitTicksAction(
                    context.instance().timeline().secondsToBattleTicks(this.durationSeconds)
            );
        }

        return this.waitTicks.run(context);
    }
}
