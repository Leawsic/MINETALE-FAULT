package cn.jehorstudio.minetale.battle.logic.states;

import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ValueStateSnapshot(
        Map<String, BattleScriptValue> scriptValues
) {
    public ValueStateSnapshot {
        scriptValues = Map.copyOf(Objects.requireNonNull(scriptValues, "scriptValues"));
    }

    public Optional<BattleScriptValue> get(String id) {
        return Optional.ofNullable(this.scriptValues.get(id));
    }
}
