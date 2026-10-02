package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorKindRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorTemplateRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ScriptedActorStateSnapshot(
        ActorKindRef kind,
        ActorTemplateRef templateRef,
        String runtimeId,
        List<String> tags,
        Map<String, BattleScriptValue> vars,
        Map<String, JsonObject> componentStates,
        long ageTicks,
        double ageSeconds
) implements ActorTypeSnapshot {
    public static final ScriptedActorStateSnapshot EMPTY = new ScriptedActorStateSnapshot(
            ActorKindRef.of("minetale:unknown"),
            ActorTemplateRef.of("unknown"),
            "",
            List.of(),
            Map.of(),
            Map.of(),
            0L,
            0.0D
    );

    public ScriptedActorStateSnapshot {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(templateRef, "templateRef");
        Objects.requireNonNull(runtimeId, "runtimeId");
        tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        vars = Map.copyOf(Objects.requireNonNull(vars, "vars"));
        Map<String, JsonObject> componentStateCopy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : Objects.requireNonNull(componentStates, "componentStates").entrySet()) {
            componentStateCopy.put(entry.getKey(), entry.getValue().deepCopy());
        }
        componentStates = Map.copyOf(componentStateCopy);
        if (ageTicks < 0L) {
            throw new IllegalArgumentException("ageTicks must be >= 0.");
        }
        if (!Double.isFinite(ageSeconds) || ageSeconds < 0.0D) {
            throw new IllegalArgumentException("ageSeconds must be finite and >= 0.");
        }
    }

    public Map<String, JsonObject> componentStates() {
        Map<String, JsonObject> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : this.componentStates.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return Map.copyOf(copy);
    }

    @Override
    public ActorType actorType() {
        return ActorType.SCRIPTED_ACTOR;
    }
}
