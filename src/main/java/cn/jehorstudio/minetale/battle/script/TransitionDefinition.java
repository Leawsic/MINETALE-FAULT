package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Optional;

public record TransitionDefinition(
        JsonObject condition,
        Optional<String> targetPhase,
        boolean endsBattle
) {
    public TransitionDefinition {
        Objects.requireNonNull(condition, "condition");
        targetPhase = Objects.requireNonNull(targetPhase, "targetPhase");
        condition = condition.deepCopy();
        if (!endsBattle && targetPhase.isEmpty()) {
            throw new IllegalArgumentException("Transition must target a phase or end the battle.");
        }
    }

    public JsonObject condition() {
        return this.condition.deepCopy();
    }
}
