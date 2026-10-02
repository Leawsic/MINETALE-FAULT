package cn.jehorstudio.minetale.battle.logic.action.actions;

import cn.jehorstudio.minetale.battle.logic.action.BattleAction;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;
import cn.jehorstudio.minetale.battle.logic.actor.ActorLifecycle;
import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;

import java.util.Objects;

public final class DestroyActorAction implements BattleAction {
    private final ActorRef ref;

    public DestroyActorAction(ActorRef ref) {
        this.ref = Objects.requireNonNull(ref, "ref");
    }

    @Override
    public BattleActionResult run(BattleActionContext context) {
        context.instance().stateCache().actors().resolve(this.ref)
                .ifPresent(actor -> actor.setLifecycle(ActorLifecycle.REMOVED));
        return BattleActionResult.COMPLETED;
    }
}
