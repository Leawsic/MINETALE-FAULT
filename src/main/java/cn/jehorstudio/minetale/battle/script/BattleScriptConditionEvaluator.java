package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.NoContext;
import cn.jehorstudio.minetale.battle.logic.input.BattleInputKey;

import java.util.Objects;

final class BattleScriptConditionEvaluator {
    private final BattleScriptRuntime runtime;

    BattleScriptConditionEvaluator(BattleScriptRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    boolean evaluate(JsonObject condition) {
        return evaluate(condition, new BattleActionContext(this.runtime.instance(), NoContext.INSTANCE));
    }

    boolean evaluate(JsonObject condition, BattleActionContext actionContext) {
        return evaluate(condition, actionContext, Long.MIN_VALUE);
    }

    boolean evaluate(JsonObject condition, BattleActionContext actionContext, long signalAfterSequenceExclusive) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(actionContext, "actionContext");
        if (condition.has("type")) {
            return this.runtime.values().evalBoolean(condition, new BattleScriptEvaluationContext(this.runtime, actionContext));
        }
        if (BattleScriptJson.optionalBoolean(condition, "always", false)) {
            return true;
        }
        if (condition.has("all") && !evaluateAll(condition.getAsJsonArray("all"), actionContext, signalAfterSequenceExclusive)) {
            return false;
        }
        if (condition.has("any") && !evaluateAny(condition.getAsJsonArray("any"), actionContext, signalAfterSequenceExclusive)) {
            return false;
        }
        if (condition.has("not") && evaluate(BattleScriptJson.requireObject(condition, "not", "condition"), actionContext, signalAfterSequenceExclusive)) {
            return false;
        }
        if (condition.has("currentTickSignalReceived")) {
            String signal = BattleScriptJson.requireString(condition, "currentTickSignalReceived", "condition");
            if (!this.runtime.signals().currentTickReceived(signal, this.runtime.instance().timeline().battleTick())) {
                return false;
            }
        }
        if (condition.has("keyDown")) {
            String key = BattleScriptJson.requireString(condition, "keyDown", "condition");
            if (!this.runtime.instance().input().isHeld(BattleInputKey.require(key, "condition.keyDown"))) {
                return false;
            }
        }
        if (BattleScriptJson.optionalBoolean(condition, "timeElapsed", false)
                && !this.runtime.phaseRuntime().activePhaseDurationElapsed()) {
            return false;
        }
        if (BattleScriptJson.optionalBoolean(condition, "actionStackCompleted", false)
                && !this.runtime.phaseRuntime().entryStackCompleted()) {
            return false;
        }
        if (condition.has("signalReceived")) {
            String signal = BattleScriptJson.requireString(condition, "signalReceived", "condition");
            if (!this.runtime.signals().receivedAfterSequence(signal, this.runtime.instance().timeline().battleTick(), signalAfterSequenceExclusive)) {
                return false;
            }
        }
        if (condition.has("read")) {
            return evaluateReadComparison(condition, actionContext);
        }
        return true;
    }

    private boolean evaluateAll(JsonArray array, BattleActionContext actionContext) {
        return evaluateAll(array, actionContext, Long.MIN_VALUE);
    }

    private boolean evaluateAll(JsonArray array, BattleActionContext actionContext, long signalAfterSequenceExclusive) {
        for (JsonElement element : array) {
            if (!evaluate(BattleScriptJson.requireObject(element, "condition.all"), actionContext, signalAfterSequenceExclusive)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateAny(JsonArray array, BattleActionContext actionContext) {
        return evaluateAny(array, actionContext, Long.MIN_VALUE);
    }

    private boolean evaluateAny(JsonArray array, BattleActionContext actionContext, long signalAfterSequenceExclusive) {
        for (JsonElement element : array) {
            if (evaluate(BattleScriptJson.requireObject(element, "condition.any"), actionContext, signalAfterSequenceExclusive)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateReadComparison(JsonObject condition, BattleActionContext actionContext) {
        BattleScriptEvaluationContext evalContext = new BattleScriptEvaluationContext(this.runtime, actionContext);
        JsonObject readExpr = new JsonObject();
        readExpr.addProperty("read", BattleScriptJson.requireString(condition, "read", "condition"));
        Object left = this.runtime.values().evalAny(readExpr, BattleScriptValueType.ANY, evalContext);
        String op = BattleScriptJson.optionalString(condition, "op").orElse("eq");
        JsonElement rightElement = condition.get("right");
        if (rightElement == null) {
            return truthy(left);
        }
        Object right = this.runtime.values().evalAny(rightElement, BattleScriptValueType.ANY, evalContext);
        return compare(left, op, right);
    }

    private static boolean compare(Object left, String op, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            double l = leftNumber.doubleValue();
            double r = rightNumber.doubleValue();
            return switch (op) {
                case "eq" -> Double.compare(l, r) == 0;
                case "ne" -> Double.compare(l, r) != 0;
                case "lt" -> l < r;
                case "lte" -> l <= r;
                case "gt" -> l > r;
                case "gte" -> l >= r;
                default -> throw new IllegalArgumentException("Unsupported condition op: " + op);
            };
        }
        return switch (op) {
            case "eq" -> Objects.equals(left, right);
            case "ne" -> !Objects.equals(left, right);
            default -> throw new IllegalArgumentException("Condition op " + op + " requires numeric operands.");
        };
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0D;
        }
        if (value instanceof String string) {
            return !string.isBlank();
        }
        return value != null;
    }
}
