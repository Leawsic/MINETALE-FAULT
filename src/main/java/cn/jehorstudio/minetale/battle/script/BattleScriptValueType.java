package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;

public enum BattleScriptValueType {
    INTEGER,
    DOUBLE,
    NUMBER,
    BOOLEAN,
    STRING,
    VECTOR2,
    VECTOR3,
    ANGLE,
    ACTOR_REF,
    PLAYER_REF,
    PHASE_REF,
    PATTERN_REF,
    RULESET_REF,
    OUTCOME_REF,
    MENU_ITEM_REF,
    TAG,
    SIGNAL,
    VISUAL_REF,
    SOUND_REF,
    COLLISION_SHAPE,
    ANY;

    static BattleScriptValueType fromValueType(BattleScriptValue.Type type) {
        return switch (type) {
            case INT -> INTEGER;
            case DOUBLE -> DOUBLE;
            case BOOLEAN -> BOOLEAN;
            case STRING -> STRING;
            case VECTOR2 -> VECTOR2;
            case VECTOR3 -> VECTOR3;
        };
    }
}
