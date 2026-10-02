package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record BattleScriptTypeContext(
        Map<String, BattleScriptValue.Type> scriptVariables,
        Map<String, BattleScriptValue.Type> actorVariables
) {
    BattleScriptTypeContext {
        scriptVariables = Map.copyOf(Objects.requireNonNull(scriptVariables, "scriptVariables"));
        actorVariables = actorVariables == null ? null : Map.copyOf(actorVariables);
    }

    static BattleScriptTypeContext stage(CompiledBattleDefinition definition) {
        return new BattleScriptTypeContext(scriptVariableTypes(definition), null);
    }

    BattleScriptTypeContext withActorVariables(Map<String, BattleScriptValue.Type> actorVariables) {
        return new BattleScriptTypeContext(this.scriptVariables, actorVariables);
    }

    Optional<BattleScriptValue.Type> scriptVariable(String id) {
        return Optional.ofNullable(this.scriptVariables.get(id));
    }

    Optional<BattleScriptValue.Type> actorVariable(String id) {
        return this.actorVariables == null ? Optional.empty() : Optional.ofNullable(this.actorVariables.get(id));
    }

    boolean hasActorContext() {
        return this.actorVariables != null;
    }

    private static Map<String, BattleScriptValue.Type> scriptVariableTypes(CompiledBattleDefinition definition) {
        java.util.HashMap<String, BattleScriptValue.Type> types = new java.util.HashMap<>();
        for (VariableDefinition variable : definition.variables().values()) {
            types.put(variable.id(), variable.type());
        }
        return types;
    }
}
