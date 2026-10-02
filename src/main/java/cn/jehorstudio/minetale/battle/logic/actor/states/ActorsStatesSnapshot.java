package cn.jehorstudio.minetale.battle.logic.actor.states;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ActorsStatesSnapshot(
        Map<ActorType, List<ActorSingleSnapshot>> actorsByType
) {
    public ActorsStatesSnapshot {
        Objects.requireNonNull(actorsByType, "actorsByType");
        EnumMap<ActorType, List<ActorSingleSnapshot>> copy = new EnumMap<>(ActorType.class);
        for (ActorType type : ActorType.values()) {
            copy.put(type, List.copyOf(actorsByType.getOrDefault(type, List.of())));
        }
        actorsByType = Map.copyOf(copy);
    }

    public List<ActorSingleSnapshot> actors(ActorType type) {
        return this.actorsByType.getOrDefault(type, List.of());
    }

    public List<ActorSingleSnapshot> activeRenderableActors() {
        List<ActorSingleSnapshot> result = new java.util.ArrayList<>();
        for (Map.Entry<ActorType, List<ActorSingleSnapshot>> entry : this.actorsByType.entrySet()) {
            if (!entry.getKey().participation().renderable()) {
                continue;
            }
            for (ActorSingleSnapshot actor : entry.getValue()) {
                if (actor.lifecycle() == cn.jehorstudio.minetale.battle.logic.actor.ActorLifecycle.ACTIVE) {
                    result.add(actor);
                }
            }
        }
        return List.copyOf(result);
    }
}
