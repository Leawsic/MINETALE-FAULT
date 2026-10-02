package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public record PhaseDefinition(
        String id,
        List<String> ruleSetRefs,
        List<JsonObject> onEnterActions,
        List<JsonObject> onTickActions,
        List<JsonObject> onExitActions,
        List<TransitionDefinition> transitions,
        OptionalDouble durationSeconds,
        OptionalLong durationTicks
) {
    public PhaseDefinition {
        Objects.requireNonNull(id, "id");
        ruleSetRefs = List.copyOf(Objects.requireNonNull(ruleSetRefs, "ruleSetRefs"));
        onEnterActions = onEnterActions.stream().map(JsonObject::deepCopy).toList();
        onTickActions = onTickActions.stream().map(JsonObject::deepCopy).toList();
        onExitActions = onExitActions.stream().map(JsonObject::deepCopy).toList();
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        Objects.requireNonNull(durationSeconds, "durationSeconds");
        Objects.requireNonNull(durationTicks, "durationTicks");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Phase id must not be blank.");
        }
    }

    public List<JsonObject> onEnterActions() {
        return this.onEnterActions.stream().map(JsonObject::deepCopy).toList();
    }

    public List<JsonObject> onTickActions() {
        return this.onTickActions.stream().map(JsonObject::deepCopy).toList();
    }

    public List<JsonObject> onExitActions() {
        return this.onExitActions.stream().map(JsonObject::deepCopy).toList();
    }
}
