package cn.jehorstudio.minetale.battle.logic.states;

import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ValueStateCache {
    private final Map<String, BattleScriptValue> scriptValues = new LinkedHashMap<>();

    public void declare(String id, BattleScriptValue initialValue) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(initialValue, "initialValue");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Value id must not be blank.");
        }
        if (this.scriptValues.containsKey(id)) {
            throw new IllegalArgumentException("Value has already been declared: " + id);
        }
        this.scriptValues.put(id, initialValue.deepCopy());
    }

    public boolean declared(String id) {
        return this.scriptValues.containsKey(Objects.requireNonNull(id, "id"));
    }

    public Optional<BattleScriptValue> get(String id) {
        return Optional.ofNullable(this.scriptValues.get(Objects.requireNonNull(id, "id")));
    }

    public BattleScriptValue require(String id) {
        BattleScriptValue value = this.scriptValues.get(Objects.requireNonNull(id, "id"));
        if (value == null) {
            throw new IllegalArgumentException("Unknown script value: " + id);
        }
        return value;
    }

    public void set(String id, BattleScriptValue value) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        BattleScriptValue current = this.scriptValues.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown script value: " + id);
        }
        if (current.type() != value.type()) {
            throw new IllegalArgumentException("Script value type mismatch for " + id + ".");
        }
        this.scriptValues.put(id, value.deepCopy());
    }

    public ValueStateSnapshot snapshot() {
        return new ValueStateSnapshot(this.scriptValues);
    }
}
