package cn.jehorstudio.minetale.battle.script;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

public record BattleScriptBudgets(
        int maxActorSpawnsPerTick,
        int maxSignalDispatchPerTick,
        int maxLoopIterations,
        int maxRecursionDepth,
        int maxRuleStackDepth,
        int maxActionsPerStackPerTick,
        int maxActionsPerBattleTick,
        int maxRenderRequestsPerTick
) {
    private static final Set<String> CANONICAL_FIELDS = Set.of(
            "maxActorSpawnsPerTick",
            "maxSignalDispatchPerTick",
            "maxLoopIterations",
            "maxRecursionDepth",
            "maxRuleStackDepth",
            "maxActionsPerStackPerTick",
            "maxActionsPerBattleTick",
            "maxRenderRequestsPerTick"
    );

    public static final BattleScriptBudgets DEFAULT = new BattleScriptBudgets(
            256,
            512,
            1_024,
            64,
            64,
            256,
            1_024,
            64
    );

    public BattleScriptBudgets {
        if (maxActorSpawnsPerTick <= 0
                || maxSignalDispatchPerTick <= 0
                || maxLoopIterations <= 0
                || maxRecursionDepth <= 0
                || maxRuleStackDepth <= 0
                || maxActionsPerStackPerTick <= 0
                || maxActionsPerBattleTick <= 0
                || maxRenderRequestsPerTick <= 0) {
            throw new IllegalArgumentException("BattleScript budgets must be positive.");
        }
    }

    public static BattleScriptBudgets fromJson(com.google.gson.JsonObject root) {
        Objects.requireNonNull(root, "root");
        return BattleScriptJson.optionalObject(root, "budgets")
                .map(budgets -> {
                    validateFields(budgets);
                    return new BattleScriptBudgets(
                            positiveOrDefault(budgets, "maxActorSpawnsPerTick", DEFAULT.maxActorSpawnsPerTick),
                            positiveOrDefault(budgets, "maxSignalDispatchPerTick", DEFAULT.maxSignalDispatchPerTick),
                            positiveOrDefault(budgets, "maxLoopIterations", DEFAULT.maxLoopIterations),
                            positiveOrDefault(budgets, "maxRecursionDepth", DEFAULT.maxRecursionDepth),
                            positiveOrDefault(budgets, "maxRuleStackDepth", DEFAULT.maxRuleStackDepth),
                            positiveOrDefault(budgets, "maxActionsPerStackPerTick", DEFAULT.maxActionsPerStackPerTick),
                            positiveOrDefault(budgets, "maxActionsPerBattleTick", DEFAULT.maxActionsPerBattleTick),
                            positiveOrDefault(budgets, "maxRenderRequestsPerTick", DEFAULT.maxRenderRequestsPerTick)
                    );
                })
                .orElse(DEFAULT);
    }

    private static void validateFields(com.google.gson.JsonObject budgets) {
        for (String member : budgets.keySet()) {
            if (!CANONICAL_FIELDS.contains(member)) {
                throw new BattleScriptValidationException("budgets has unsupported field: " + member + ".");
            }
        }
    }

    private static int positiveOrDefault(com.google.gson.JsonObject object, String member, int fallback) {
        com.google.gson.JsonElement element = object.get(member);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new BattleScriptValidationException("budgets." + member + " must be a number.");
        }
        BigDecimal decimal;
        try {
            decimal = element.getAsBigDecimal();
        } catch (NumberFormatException exception) {
            throw new BattleScriptValidationException("budgets." + member + " must be a positive integer.", exception);
        }
        if (decimal.signum() <= 0) {
            throw new BattleScriptValidationException("budgets." + member + " must be a positive integer.");
        }
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException exception) {
            if (decimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new BattleScriptValidationException(
                        "budgets." + member + " must be a 32-bit positive integer.",
                        exception
                );
            }
            throw new BattleScriptValidationException(
                    "budgets." + member + " must be a positive integer without a fractional part.",
                    exception
            );
        }
    }
}
