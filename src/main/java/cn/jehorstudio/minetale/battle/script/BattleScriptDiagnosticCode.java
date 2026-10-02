package cn.jehorstudio.minetale.battle.script;

import java.util.Locale;

// Schema、Editor 与 Java 必须共享稳定诊断码。
public enum BattleScriptDiagnosticCode {
    MISSING_SPAWN_BULLET_PREFAB("missing_spawn_bullet_prefab"),
    EMPTY_SPAWN_TRANSFORM_OVERRIDE("empty_spawn_transform_override"),
    PREFAB_DYNAMIC_VELOCITY("prefab_dynamic_velocity"),
    INVALID_VELOCITY_SHAPE("invalid_velocity_shape"),
    INVALID_VALUE_EXPR_TYPE("invalid_value_expr_type"),
    DYNAMIC_WAIT_DURATION("dynamic_wait_duration"),
    UNSUPPORTED_LAYOUT_SPACE("unsupported_layout_space"),
    UNSUPPORTED_RULESET_DOMAIN("unsupported_ruleset_domain"),
    UNKNOWN_BUDGET_FIELD("unknown_budget_field"),
    BUDGET_INTEGER_OVERFLOW("budget_integer_overflow"),
    LEGACY_SPAWN_POSITION("legacy_spawn_position"),
    BLANK_ACTOR_ID("blank_actor_id"),
    INVALID_RESOURCE_ID("invalid_resource_id"),
    VALIDATION_ERROR("validation_error");

    private final String serializedName;

    BattleScriptDiagnosticCode(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return this.serializedName;
    }

    static BattleScriptDiagnosticCode infer(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("spawn_bullet.prefab is required")) return MISSING_SPAWN_BULLET_PREFAB;
        if (normalized.contains("spawn transform override must contain")) return EMPTY_SPAWN_TRANSFORM_OVERRIDE;
        if (normalized.contains(".velocity.value must be a 3-number array")) return PREFAB_DYNAMIC_VELOCITY;
        if (normalized.contains("legacy direct valueexpr shape")) return INVALID_VELOCITY_SHAPE;
        if (normalized.contains("unsupported valueexpr type")) return INVALID_VALUE_EXPR_TYPE;
        if (normalized.contains(".seconds must be a static number")
                || normalized.contains(".ticks must be a static integer")) return DYNAMIC_WAIT_DURATION;
        if (normalized.contains("layout.space currently requires viewport.percent")) return UNSUPPORTED_LAYOUT_SPACE;
        if (normalized.contains("unsupported stable rule domain")) return UNSUPPORTED_RULESET_DOMAIN;
        if (normalized.contains("budgets has unsupported field")) return UNKNOWN_BUDGET_FIELD;
        if (normalized.contains("must be a 32-bit positive integer")) return BUDGET_INTEGER_OVERFLOW;
        if (normalized.contains(".position has moved to")) return LEGACY_SPAWN_POSITION;
        if (normalized.contains(".actorid must not be blank")) return BLANK_ACTOR_ID;
        if (normalized.contains("must be a valid resource location")) return INVALID_RESOURCE_ID;
        return VALIDATION_ERROR;
    }
}
