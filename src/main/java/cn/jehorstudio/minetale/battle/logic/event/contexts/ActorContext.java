package cn.jehorstudio.minetale.battle.logic.event.contexts;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;

import java.util.Objects;

public record ActorContext(
        ActorRef actor
) implements BattleEventContext {
    public ActorContext {
        Objects.requireNonNull(actor, "actor");
    }
}
