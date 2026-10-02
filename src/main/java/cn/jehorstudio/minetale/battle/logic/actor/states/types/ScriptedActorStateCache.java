package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorKindRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorTemplateRef;
import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ScriptedActorStateCache {
    private ActorKindRef kind = ActorKindRef.of("minetale:unknown");
    private ActorTemplateRef templateRef = ActorTemplateRef.of("unknown");
    private String runtimeId = "";
    private List<String> tags = List.of();
    private final Map<String, BattleScriptValue> vars = new LinkedHashMap<>();
    private final Map<String, JsonObject> componentStates = new LinkedHashMap<>();
    private long ageTicks;
    private double ageSeconds;

    public ActorKindRef kind() {
        return this.kind;
    }

    public void setKind(ActorKindRef kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public ActorTemplateRef templateRef() {
        return this.templateRef;
    }

    public void setTemplateRef(ActorTemplateRef templateRef) {
        this.templateRef = Objects.requireNonNull(templateRef, "templateRef");
    }

    public String runtimeId() {
        return this.runtimeId;
    }

    public void setRuntimeId(String runtimeId) {
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
    }

    public List<String> tags() {
        return this.tags;
    }

    public void setTags(List<String> tags) {
        Objects.requireNonNull(tags, "tags");
        List<String> copy = new ArrayList<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank()) {
                copy.add(tag);
            }
        }
        this.tags = List.copyOf(copy);
    }

    public Map<String, BattleScriptValue> vars() {
        return Map.copyOf(this.vars);
    }

    public void setVar(String id, BattleScriptValue value) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Scripted actor var id must not be blank.");
        }
        this.vars.put(id, Objects.requireNonNull(value, "value").deepCopy());
    }

    public JsonObject componentState(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Component state key must not be blank.");
        }
        return this.componentStates.computeIfAbsent(key, ignored -> new JsonObject());
    }

    public Map<String, JsonObject> componentStates() {
        Map<String, JsonObject> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> entry : this.componentStates.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return Map.copyOf(copy);
    }

    public long ageTicks() {
        return this.ageTicks;
    }

    public double ageSeconds() {
        return this.ageSeconds;
    }

    public void advanceAge(long ticks, double seconds) {
        if (ticks < 0L) {
            throw new IllegalArgumentException("ticks must be >= 0.");
        }
        if (!Double.isFinite(seconds) || seconds < 0.0D) {
            throw new IllegalArgumentException("seconds must be finite and >= 0.");
        }
        this.ageTicks += ticks;
        this.ageSeconds += seconds;
    }

    public ScriptedActorStateSnapshot snapshot() {
        return new ScriptedActorStateSnapshot(
                this.kind,
                this.templateRef,
                this.runtimeId,
                this.tags,
                this.vars,
                this.componentStates,
                this.ageTicks,
                this.ageSeconds
        );
    }
}
