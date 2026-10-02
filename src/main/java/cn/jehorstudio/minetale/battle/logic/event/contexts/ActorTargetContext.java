package cn.jehorstudio.minetale.battle.logic.event.contexts;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;

import java.util.Objects;

public record ActorTargetContext(
        ActorRef actor,
        ActorRef target
) implements BattleEventContext {
    public ActorTargetContext {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(target, "target");
    }
}
