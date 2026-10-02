package cn.jehorstudio.minetale.battle.logic.action;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.BattleInstance;

import java.util.Objects;

public final class BattleActionRunner {
    private final BattleInstance instance;

    public BattleActionRunner(BattleInstance instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    public BattleActionResult runNow(BattleAction action) {
        Objects.requireNonNull(action, "action");

        BattleActionContext context = new BattleActionContext(
                this.instance,
                this.instance.events().currentContext()
        );
        BattleActionResult result = action.run(context);

        if (result == BattleActionResult.RUNNING) {
            logFailed(action, "Immediate action returned RUNNING. Use PushAction to run it in an Action Stack.");
            return BattleActionResult.FAILED;
        }
        if (result == BattleActionResult.FAILED) {
            logFailed(action, "Action returned FAILED.");
        }

        return result;
    }

    void logFailed(BattleAction action, String reason) {
        MineTale.LOGGER.error(
                "Battle action failed. battleId={}, battleTick={}, actionType={}, reason={}",
                this.instance.battleId(),
                this.instance.timeline().battleTick(),
                action.actionType(),
                reason
        );
    }
}
