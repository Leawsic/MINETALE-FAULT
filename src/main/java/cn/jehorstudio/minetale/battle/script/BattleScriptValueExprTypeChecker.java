package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

final class BattleScriptValueExprTypeChecker {
    BattleScriptValueType infer(JsonElement expr, BattleScriptTypeContext context, String path) {
        if (expr == null) {
            throw new BattleScriptValidationException(path + " is required.");
        }
        if (expr.isJsonPrimitive()) {
            JsonPrimitive primitive = expr.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                double value = primitive.getAsDouble();
                if (!Double.isFinite(value)) {
                    throw new BattleScriptValidationException(path + " must be finite.");
                }
                return Math.rint(value) == value ? BattleScriptValueType.INTEGER : BattleScriptValueType.DOUBLE;
            }
            if (primitive.isBoolean()) {
                return BattleScriptValueType.BOOLEAN;
            }
            if (primitive.isString()) {
                return BattleScriptValueType.STRING;
            }
            return BattleScriptValueType.ANY;
        }
        if (expr.isJsonArray()) {
            JsonArray array = expr.getAsJsonArray();
            if (array.size() == 2) {
                validateArrayNumbers(array, context, path);
                return BattleScriptValueType.VECTOR2;
            }
            if (array.size() == 3) {
                validateArrayNumbers(array, context, path);
                return BattleScriptValueType.VECTOR3;
            }
            throw new BattleScriptValidationException(path + " array literal must contain 2 or 3 values.");
        }
        JsonObject object = BattleScriptJson.requireObject(expr, path);
        if (object.has("read")) {
            return inferRead(BattleScriptJson.requireString(object, "read", path), context, path + ".read");
        }
        String type = BattleScriptJson.optionalString(object, "type")
                .orElseThrow(() -> new BattleScriptValidationException(path + ".type is required for object ValueExpr."));
        return switch (type) {
            case "number_binary_op" -> inferNumberBinary(object, context, path);
            case "mod" -> inferMod(object, context, path);
            case "abs" -> {
                BattleScriptValueType input = validateMember(object, "value", BattleScriptValueType.NUMBER, context, path);
                yield input == BattleScriptValueType.INTEGER ? BattleScriptValueType.INTEGER : numericResult(input);
            }
            case "round", "floor", "ceil" -> {
                validateMember(object, "value", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.INTEGER;
            }
            case "random_number" -> {
                validateMember(object, "min", BattleScriptValueType.NUMBER, context, path);
                validateMember(object, "max", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.DOUBLE;
            }
            case "random_int" -> {
                validateMember(object, "min", BattleScriptValueType.INTEGER, context, path);
                validateMember(object, "max", BattleScriptValueType.INTEGER, context, path);
                yield BattleScriptValueType.INTEGER;
            }
            case "compare", "and", "or", "not", "string_contains", "string_equals" -> {
                validateBooleanExpr(object, type, context, path);
                yield BattleScriptValueType.BOOLEAN;
            }
            case "number_to_string" -> {
                validateMember(object, "value", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.STRING;
            }
            case "boolean_to_string" -> {
                validateMember(object, "value", BattleScriptValueType.BOOLEAN, context, path);
                yield BattleScriptValueType.STRING;
            }
            case "to_string" -> {
                infer(require(object, "value", path), context, path + ".value");
                yield BattleScriptValueType.STRING;
            }
            case "string_to_number" -> {
                validateMember(object, "text", BattleScriptValueType.STRING, context, path);
                validateMember(object, "fallback", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.DOUBLE;
            }
            case "vector2" -> {
                validateMember(object, "x", BattleScriptValueType.NUMBER, context, path);
                validateMember(object, "y", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.VECTOR2;
            }
            case "vector3" -> {
                validateMember(object, "x", BattleScriptValueType.NUMBER, context, path);
                validateMember(object, "y", BattleScriptValueType.NUMBER, context, path);
                validateMember(object, "z", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.VECTOR3;
            }
            case "vector2_to_vector3" -> {
                validateMember(object, "vector", BattleScriptValueType.VECTOR2, context, path);
                validateMember(object, "y", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.VECTOR3;
            }
            case "vector3_xz" -> {
                validateMember(object, "vector", BattleScriptValueType.VECTOR3, context, path);
                yield BattleScriptValueType.VECTOR2;
            }
            case "vector_scale" -> {
                validateMember(object, "vector", BattleScriptValueType.VECTOR3, context, path);
                validateMember(object, "scale", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.VECTOR3;
            }
            case "vector_add", "vector_normalize", "vector_direction_from_to", "vector_from_angle_speed" -> inferVectorExpr(object, type, context, path);
            case "vector_length" -> {
                validateMember(object, "vector", BattleScriptValueType.VECTOR3, context, path);
                yield BattleScriptValueType.DOUBLE;
            }
            case "vector_component" -> {
                validateMember(object, "vector", BattleScriptValueType.VECTOR3, context, path);
                yield BattleScriptValueType.DOUBLE;
            }
            case "angle_degrees" -> {
                validateMember(object, "degrees", BattleScriptValueType.NUMBER, context, path);
                yield BattleScriptValueType.ANGLE;
            }
            case "angle_to_number" -> {
                validateMember(object, "value", BattleScriptValueType.ANGLE, context, path);
                yield BattleScriptValueType.DOUBLE;
            }
            case "trig" -> {
                validateMember(object, "angle", BattleScriptValueType.ANGLE, context, path);
                yield BattleScriptValueType.DOUBLE;
            }
            default -> throw new BattleScriptValidationException(path + ".type has unsupported ValueExpr type: " + type + ".");
        };
    }

    void validate(JsonElement expr, BattleScriptValueType expected, BattleScriptTypeContext context, String path) {
        BattleScriptValueType actual = infer(expr, context, path);
        if (!matches(expected, actual)) {
            throw new BattleScriptValidationException(path + " type mismatch: expected " + label(expected) + " but got " + label(actual) + ".");
        }
        if (expected == BattleScriptValueType.INTEGER && expr != null && expr.isJsonPrimitive() && expr.getAsJsonPrimitive().isNumber()) {
            try {
                BattleScriptValue.requireIntegral(expr.getAsDouble(), path);
            } catch (IllegalArgumentException ex) {
                throw new BattleScriptValidationException(ex.getMessage(), ex);
            }
        }
    }

    private BattleScriptValueType inferRead(String read, BattleScriptTypeContext context, String path) {
        if (read.startsWith("script.var:")) {
            String id = read.substring("script.var:".length());
            return BattleScriptValueType.fromValueType(context.scriptVariable(id)
                    .orElseThrow(() -> new BattleScriptValidationException(path + " references unknown script variable " + id + ".")));
        }
        if (read.startsWith("actor.var:")) {
            if (!context.hasActorContext()) {
                throw new BattleScriptValidationException(path + " reads actor.var without actor event context.");
            }
            String id = read.substring("actor.var:".length());
            return BattleScriptValueType.fromValueType(context.actorVariable(id)
                    .orElseThrow(() -> new BattleScriptValidationException(path + " references unknown actor variable " + id + ".")));
        }
        if (read.startsWith("actor.property:")) {
            if (!context.hasActorContext()) {
                throw new BattleScriptValidationException(path + " reads actor.property without actor event context.");
            }
            return switch (read.substring("actor.property:".length())) {
                case "position", "velocity" -> BattleScriptValueType.VECTOR3;
                case "yawDeg", "pitchDeg", "rollDeg" -> BattleScriptValueType.DOUBLE;
                default -> throw new BattleScriptValidationException(path + " has unsupported actor property read: " + read + ".");
            };
        }
        if (read.startsWith("actor.tag_count:")) {
            return BattleScriptValueType.INTEGER;
        }
        return switch (read) {
            case "player.any.hp_percent", "phase.elapsed_seconds" -> BattleScriptValueType.DOUBLE;
            case "player.any.hp_text" -> BattleScriptValueType.STRING;
            case "phase.elapsed_ticks" -> BattleScriptValueType.INTEGER;
            case "phase.current" -> BattleScriptValueType.STRING;
            default -> throw new BattleScriptValidationException(path + " is not an allowed read path: " + read + ".");
        };
    }

    private BattleScriptValueType inferNumberBinary(JsonObject object, BattleScriptTypeContext context, String path) {
        BattleScriptValueType left = validateMember(object, "left", BattleScriptValueType.NUMBER, context, path);
        BattleScriptValueType right = validateMember(object, "right", BattleScriptValueType.NUMBER, context, path);
        String op = BattleScriptJson.optionalString(object, "op").orElse("add");
        if ("div".equals(op)) {
            return BattleScriptValueType.DOUBLE;
        }
        if (left == BattleScriptValueType.INTEGER && right == BattleScriptValueType.INTEGER) {
            return BattleScriptValueType.INTEGER;
        }
        if (left == BattleScriptValueType.DOUBLE || right == BattleScriptValueType.DOUBLE) {
            return BattleScriptValueType.DOUBLE;
        }
        return BattleScriptValueType.NUMBER;
    }

    private BattleScriptValueType inferMod(JsonObject object, BattleScriptTypeContext context, String path) {
        BattleScriptValueType left = validateMember(object, "left", BattleScriptValueType.NUMBER, context, path);
        BattleScriptValueType right = validateMember(object, "right", BattleScriptValueType.NUMBER, context, path);
        return left == BattleScriptValueType.INTEGER && right == BattleScriptValueType.INTEGER
                ? BattleScriptValueType.INTEGER
                : BattleScriptValueType.DOUBLE;
    }

    private BattleScriptValueType inferVectorExpr(JsonObject object, String type, BattleScriptTypeContext context, String path) {
        switch (type) {
            case "vector_add" -> {
                validateMember(object, "left", BattleScriptValueType.VECTOR3, context, path);
                validateMember(object, "right", BattleScriptValueType.VECTOR3, context, path);
            }
            case "vector_normalize" -> validateMember(object, "vector", BattleScriptValueType.VECTOR3, context, path);
            case "vector_direction_from_to" -> {
                validateMember(object, "from", BattleScriptValueType.VECTOR3, context, path);
                validateMember(object, "to", BattleScriptValueType.VECTOR3, context, path);
            }
            case "vector_from_angle_speed" -> {
                validateMember(object, "angle", BattleScriptValueType.ANGLE, context, path);
                validateMember(object, "speed", BattleScriptValueType.NUMBER, context, path);
            }
            default -> throw new BattleScriptValidationException(path + ".type has unsupported vector expr: " + type + ".");
        }
        return BattleScriptValueType.VECTOR3;
    }

    private void validateBooleanExpr(JsonObject object, String type, BattleScriptTypeContext context, String path) {
        switch (type) {
            case "compare" -> {
                BattleScriptValueType left = infer(require(object, "left", path), context, path + ".left");
                BattleScriptValueType right = infer(require(object, "right", path), context, path + ".right");
                String op = BattleScriptJson.optionalString(object, "op").orElse("eq");
                if (SetOps.isOrdering(op) && (!matches(BattleScriptValueType.NUMBER, left) || !matches(BattleScriptValueType.NUMBER, right))) {
                    throw new BattleScriptValidationException(path + ".op " + op + " requires numeric operands.");
                }
            }
            case "and", "or" -> {
                JsonArray values = BattleScriptJson.requireArray(object, "values", path);
                for (int i = 0; i < values.size(); i++) {
                    validate(values.get(i), BattleScriptValueType.BOOLEAN, context, path + ".values[" + i + "]");
                }
            }
            case "not" -> validateMember(object, "value", BattleScriptValueType.BOOLEAN, context, path);
            case "string_contains" -> {
                validateMember(object, "text", BattleScriptValueType.STRING, context, path);
                validateMember(object, "needle", BattleScriptValueType.STRING, context, path);
            }
            case "string_equals" -> {
                validateMember(object, "left", BattleScriptValueType.STRING, context, path);
                validateMember(object, "right", BattleScriptValueType.STRING, context, path);
            }
            default -> throw new BattleScriptValidationException(path + ".type has unsupported boolean expr: " + type + ".");
        }
    }

    private BattleScriptValueType validateMember(JsonObject object, String member, BattleScriptValueType expected, BattleScriptTypeContext context, String path) {
        JsonElement value = require(object, member, path);
        BattleScriptValueType actual = infer(value, context, path + "." + member);
        if (!matches(expected, actual)) {
            throw new BattleScriptValidationException(path + "." + member + " type mismatch: expected " + label(expected) + " but got " + label(actual) + ".");
        }
        return actual;
    }

    private void validateArrayNumbers(JsonArray array, BattleScriptTypeContext context, String path) {
        for (int i = 0; i < array.size(); i++) {
            validate(array.get(i), BattleScriptValueType.NUMBER, context, path + "[" + i + "]");
        }
    }

    private static JsonElement require(JsonObject object, String member, String path) {
        JsonElement value = object.get(member);
        if (value == null) {
            throw new BattleScriptValidationException(path + "." + member + " is required.");
        }
        return value;
    }

    private static boolean matches(BattleScriptValueType expected, BattleScriptValueType actual) {
        if (expected == BattleScriptValueType.ANY || actual == BattleScriptValueType.ANY || expected == actual) {
            return true;
        }
        if (expected == BattleScriptValueType.NUMBER || expected == BattleScriptValueType.DOUBLE) {
            return actual == BattleScriptValueType.INTEGER || actual == BattleScriptValueType.DOUBLE || actual == BattleScriptValueType.NUMBER;
        }
        return false;
    }

    private static BattleScriptValueType numericResult(BattleScriptValueType input) {
        return input == BattleScriptValueType.DOUBLE ? BattleScriptValueType.DOUBLE : BattleScriptValueType.NUMBER;
    }

    private static String label(BattleScriptValueType type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static final class SetOps {
        private static boolean isOrdering(String op) {
            return "lt".equals(op) || "lte".equals(op) || "gt".equals(op) || "gte".equals(op);
        }
    }
}
