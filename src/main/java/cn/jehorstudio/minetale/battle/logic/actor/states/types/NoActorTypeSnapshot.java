package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;

import java.util.Objects;

public record NoActorTypeSnapshot(ActorType actorType) implements ActorTypeSnapshot {
    public NoActorTypeSnapshot {
        Objects.requireNonNull(actorType, "actorType");
    }
}
