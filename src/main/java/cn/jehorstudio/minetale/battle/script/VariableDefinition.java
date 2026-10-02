package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;
import com.google.gson.JsonElement;

import java.util.Objects;

public record VariableDefinition(
        String id,
        BattleScriptValue.Type type,
        JsonElement initialExpression
) {
    public VariableDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(initialExpression, "initialExpression");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Variable id must not be blank.");
        }
        initialExpression = initialExpression.deepCopy();
    }

    public JsonElement initialExpression() {
        return this.initialExpression.deepCopy();
    }
}
