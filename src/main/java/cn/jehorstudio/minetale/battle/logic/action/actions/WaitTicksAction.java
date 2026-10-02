package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

public final class WaitTicksAction implements BattleAction {
    private final long durationTicks;

    private boolean started;
    private long startedAtBattleTick;

    public WaitTicksAction(long durationTicks) {
        this.durationTicks = Math.max(0L, durationTicks);
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        long battleTick = context.instance().timeline().battleTick();
        if (!this.started) {
            this.started = true;
            this.startedAtBattleTick = battleTick;
        }

        return battleTick - this.startedAtBattleTick >= this.durationTicks
                ? BattleActionResult.COMPLETED
                : BattleActionResult.RUNNING;
    }
}
