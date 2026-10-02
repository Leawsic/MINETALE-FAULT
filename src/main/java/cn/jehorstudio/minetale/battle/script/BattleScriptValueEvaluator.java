package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.actor.Actor;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalTransform;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.ScriptedActorStateCache;
import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;
import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue.Vector2Value;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class BattleScriptValueEvaluator {
    public double evalNumber(JsonElement expr, BattleScriptEvaluationContext context) {
        Objects.requireNonNull(expr, "expr");
        if (expr.isJsonPrimitive() && expr.getAsJsonPrimitive().isNumber()) {
            return expr.getAsDouble();
        }
        Object value = evalAny(expr, BattleScriptValueType.NUMBER, context);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw typeError("Number", expr);
    }

    public boolean evalBoolean(JsonElement expr, BattleScriptEvaluationContext context) {
        Objects.requireNonNull(expr, "expr");
        if (expr.isJsonPrimitive() && expr.getAsJsonPrimitive().isBoolean()) {
            return expr.getAsBoolean();
        }
        if (expr.isJsonObject()) {
            JsonObject object = expr.getAsJsonObject();
            String type = optionalType(object);
            if ("compare".equals(type)) {
                return compare(
                        evalAny(require(object, "left", "compare"), BattleScriptValueType.ANY, context),
                        optionalString(object, "op", "eq"),
                        evalAny(require(object, "right", "compare"), BattleScriptValueType.ANY, context)
                );
            }
            if ("and".equals(type)) {
                for (JsonElement element : requireArray(object, "values", "and")) {
                    if (!evalBoolean(element, context)) {
                        return false;
                    }
                }
                return true;
            }
            if ("or".equals(type)) {
                for (JsonElement element : requireArray(object, "values", "or")) {
                    if (evalBoolean(element, context)) {
                        return true;
                    }
                }
                return false;
            }
            if ("not".equals(type)) {
                return !evalBoolean(require(object, "value", "not"), context);
            }
            if ("string_contains".equals(type)) {
                return evalString(require(object, "text", "string_contains"), context)
                        .contains(evalString(require(object, "needle", "string_contains"), context));
            }
            if ("string_equals".equals(type)) {
                return Objects.equals(
                        evalString(require(object, "left", "string_equals"), context),
                        evalString(require(object, "right", "string_equals"), context)
                );
            }
        }
        Object value = evalAny(expr, BattleScriptValueType.BOOLEAN, context);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw typeError("Boolean", expr);
    }

    public String evalString(JsonElement expr, BattleScriptEvaluationContext context) {
        Objects.requireNonNull(expr, "expr");
        if (expr.isJsonPrimitive() && expr.getAsJsonPrimitive().isString()) {
            return expr.getAsString();
        }
        if (expr.isJsonObject()) {
            JsonObject object = expr.getAsJsonObject();
            String type = optionalType(object);
            if ("number_to_string".equals(type)) {
                return Double.toString(evalNumber(require(object, "value", "number_to_string"), context));
            }
            if ("boolean_to_string".equals(type)) {
                return Boolean.toString(evalBoolean(require(object, "value", "boolean_to_string"), context));
            }
            if ("to_string".equals(type)) {
                return String.valueOf(evalAny(require(object, "value", "to_string"), BattleScriptValueType.ANY, context));
            }
        }
        Object value = evalAny(expr, BattleScriptValueType.STRING, context);
        if (value instanceof String string) {
            return string;
        }
        throw typeError("String", expr);
    }

    public Vector2Value evalVector2(JsonElement expr, BattleScriptEvaluationContext context) {
        if (expr.isJsonArray()) {
            JsonArray array = exactArray(expr.getAsJsonArray(), 2, "Vector2");
            return new Vector2Value(evalNumber(array.get(0), context), evalNumber(array.get(1), context));
        }
        if (expr.isJsonObject()) {
            JsonObject object = expr.getAsJsonObject();
            String type = optionalType(object);
            if ("vector2".equals(type)) {
                return new Vector2Value(
                        evalNumber(require(object, "x", "vector2"), context),
                        evalNumber(require(object, "y", "vector2"), context)
                );
            }
            if ("vector3_xz".equals(type)) {
                CanonicalVec3 vector = evalVector3(require(object, "vector", "vector3_xz"), context);
                return new Vector2Value(vector.x(), vector.z());
            }
        }
        Object value = evalAny(expr, BattleScriptValueType.VECTOR2, context);
        if (value instanceof Vector2Value vector) {
            return vector;
        }
        throw typeError("Vector2", expr);
    }

    public CanonicalVec3 evalVector3(JsonElement expr, BattleScriptEvaluationContext context) {
        if (expr.isJsonArray()) {
            JsonArray array = exactArray(expr.getAsJsonArray(), 3, "Vector3");
            return new CanonicalVec3(evalNumber(array.get(0), context), evalNumber(array.get(1), context), evalNumber(array.get(2), context));
        }
        if (expr.isJsonObject()) {
            JsonObject object = expr.getAsJsonObject();
            String type = optionalType(object);
            if ("vector3".equals(type)) {
                return new CanonicalVec3(
                        evalNumber(require(object, "x", "vector3"), context),
                        evalNumber(require(object, "y", "vector3"), context),
                        evalNumber(require(object, "z", "vector3"), context)
                );
            }
            if ("vector2_to_vector3".equals(type)) {
                Vector2Value vector = evalVector2(require(object, "vector", "vector2_to_vector3"), context);
                return new CanonicalVec3(vector.x(), evalNumber(require(object, "y", "vector2_to_vector3"), context), vector.y());
            }
            if ("vector_scale".equals(type)) {
                return evalVector3(require(object, "vector", "vector_scale"), context)
                        .scale(evalNumber(require(object, "scale", "vector_scale"), context));
            }
            if ("vector_add".equals(type)) {
                return evalVector3(require(object, "left", "vector_add"), context)
                        .add(evalVector3(require(object, "right", "vector_add"), context));
            }
            if ("vector_normalize".equals(type)) {
                CanonicalVec3 vector = evalVector3(require(object, "vector", "vector_normalize"), context);
                return vector.lengthSquared() <= 0.0D ? CanonicalVec3.ZERO : vector.normalize();
            }
            if ("vector_direction_from_to".equals(type)) {
                CanonicalVec3 from = evalVector3(require(object, "from", "vector_direction_from_to"), context);
                CanonicalVec3 to = evalVector3(require(object, "to", "vector_direction_from_to"), context);
                CanonicalVec3 direction = to.subtract(from);
                return direction.lengthSquared() <= 0.0D ? CanonicalVec3.ZERO : direction.normalize();
            }
            if ("vector_from_angle_speed".equals(type)) {
                double radians = Math.toRadians(evalAngle(require(object, "angle", "vector_from_angle_speed"), context));
                double speed = evalNumber(require(object, "speed", "vector_from_angle_speed"), context);
                return new CanonicalVec3(Math.cos(radians) * speed, Math.sin(radians) * speed, 0.0D);
            }
        }
        Object value = evalAny(expr, BattleScriptValueType.VECTOR3, context);
        if (value instanceof CanonicalVec3 vector) {
            return vector;
        }
        throw typeError("Vector3", expr);
    }

    public double evalAngle(JsonElement expr, BattleScriptEvaluationContext context) {
        if (expr.isJsonPrimitive() && expr.getAsJsonPrimitive().isNumber()) {
            return expr.getAsDouble();
        }
        if (expr.isJsonObject()) {
            JsonObject object = expr.getAsJsonObject();
            if ("angle_degrees".equals(optionalType(object))) {
                return evalNumber(require(object, "degrees", "angle_degrees"), context);
            }
        }
        return evalNumber(expr, context);
    }

    public Object evalAny(JsonElement expr, BattleScriptValueType expectedType, BattleScriptEvaluationContext context) {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(context, "context");
        if (expr.isJsonPrimitive()) {
            JsonPrimitive primitive = expr.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsDouble();
            }
            return primitive.getAsString();
        }
        if (expr.isJsonArray()) {
            JsonArray array = expr.getAsJsonArray();
            if (array.size() == 2) {
                return evalVector2(expr, context);
            }
            if (array.size() == 3) {
                return evalVector3(expr, context);
            }
            throw new BattleScriptValueException("Unsupported value array size: " + array.size());
        }
        JsonObject object = BattleScriptJson.requireObject(expr, "value");
        if (object.has("read")) {
            return read(BattleScriptJson.requireString(object, "read", "value"), context);
        }
        String type = optionalType(object);
        return switch (type) {
            case "number_binary_op" -> evalNumberBinary(object, context);
            case "mod" -> evalNumber(require(object, "left", "mod"), context) % evalNumber(require(object, "right", "mod"), context);
            case "abs" -> Math.abs(evalNumber(require(object, "value", "abs"), context));
            case "round" -> (double) Math.round(evalNumber(require(object, "value", "round"), context));
            case "floor" -> Math.floor(evalNumber(require(object, "value", "floor"), context));
            case "ceil" -> Math.ceil(evalNumber(require(object, "value", "ceil"), context));
            case "random_number" -> randomNumber(object, context);
            case "random_int" -> (double) randomInt(object, context);
            case "compare", "and", "or", "not", "string_contains", "string_equals" -> evalBoolean(object, context);
            case "number_to_string", "boolean_to_string", "to_string" -> evalString(object, context);
            case "string_to_number" -> stringToNumber(object, context);
            case "vector2", "vector3_xz" -> evalVector2(object, context);
            case "vector3", "vector2_to_vector3", "vector_scale", "vector_add", "vector_normalize", "vector_direction_from_to", "vector_from_angle_speed" ->
                    evalVector3(object, context);
            case "vector_length" -> evalVector3(require(object, "vector", "vector_length"), context).length();
            case "vector_component" -> vectorComponent(object, context);
            case "angle_degrees" -> evalAngle(object, context);
            case "angle_to_number" -> evalAngle(require(object, "value", "angle_to_number"), context);
            case "trig" -> trig(object, context);
            default -> throw new BattleScriptValueException("Unsupported ValueExpr type: " + type);
        };
    }

    public BattleScriptValue evalTyped(JsonElement expr, BattleScriptValue.Type type, BattleScriptEvaluationContext context) {
        return switch (type) {
            case INT -> {
                double value = evalNumber(expr, context);
                yield BattleScriptValue.ofInt(requireIntegral(value, "INT"));
            }
            case DOUBLE -> BattleScriptValue.ofDouble(evalNumber(expr, context));
            case BOOLEAN -> BattleScriptValue.ofBoolean(evalBoolean(expr, context));
            case STRING -> BattleScriptValue.ofString(evalString(expr, context));
            case VECTOR2 -> {
                Vector2Value vector = evalVector2(expr, context);
                yield BattleScriptValue.ofVector2(vector.x(), vector.y());
            }
            case VECTOR3 -> BattleScriptValue.ofVector3(evalVector3(expr, context));
        };
    }

    private Object read(String path, BattleScriptEvaluationContext context) {
        if (path.startsWith("script.var:")) {
            return unwrap(context.scriptValues().require(path.substring("script.var:".length())));
        }
        if (path.startsWith("actor.var:")) {
            Actor self = context.self()
                    .orElseThrow(() -> new BattleScriptValueException("actor.var read requires self actor context: " + path));
            ScriptedActorStateCache scripted = self.scripted();
            if (scripted == null) {
                throw new BattleScriptValueException("actor.var read requires SCRIPTED_ACTOR self: " + path);
            }
            BattleScriptValue value = scripted.vars().get(path.substring("actor.var:".length()));
            if (value == null) {
                throw new BattleScriptValueException("Unknown actor var: " + path);
            }
            return unwrap(value);
        }
        if (path.startsWith("actor.tag_count:")) {
            return (double) context.runtime().actors().tagCount(path.substring("actor.tag_count:".length()));
        }
        if (path.startsWith("actor.property:")) {
            Actor actor = context.self()
                    .orElseThrow(() -> new BattleScriptValueException("actor.property read requires self actor context: " + path));
            return actorProperty(actor, path.substring("actor.property:".length()));
        }
        return switch (path) {
            case "player.any.hp_percent" -> context.instance().stateCache().players().snapshot()
                    .map(player -> player.hp() / (double) player.maxHp())
                    .orElse(0.0D);
            case "player.any.hp_text" -> context.instance().stateCache().players().snapshot()
                    .map(player -> player.hp() + " / " + player.maxHp())
                    .orElse("0 / 0");
            case "phase.elapsed_seconds" -> context.phase().activePhaseElapsedSeconds();
            case "phase.elapsed_ticks" -> (double) context.phase().activePhaseElapsedTicks();
            case "phase.current" -> context.phase().activePhaseId();
            default -> throw new BattleScriptValueException("Unsupported value read: " + path);
        };
    }

    private static Object unwrap(BattleScriptValue value) {
        return switch (value.type()) {
            case INT, DOUBLE, BOOLEAN, STRING -> value.value();
            case VECTOR2 -> value.asVector2();
            case VECTOR3 -> value.asVector3();
        };
    }

    private static Object actorProperty(Actor actor, String property) {
        CanonicalTransform transform = actor.transform();
        return switch (property) {
            case "position" -> transform.position();
            case "velocity" -> actor.velocity();
            case "yawDeg" -> transform.yawDeg();
            case "pitchDeg" -> transform.pitchDeg();
            case "rollDeg" -> transform.rollDeg();
            default -> throw new BattleScriptValueException("Unsupported actor property read: " + property);
        };
    }

    private double evalNumberBinary(JsonObject object, BattleScriptEvaluationContext context) {
        double left = evalNumber(require(object, "left", "number_binary_op"), context);
        double right = evalNumber(require(object, "right", "number_binary_op"), context);
        return switch (optionalString(object, "op", "add")) {
            case "add" -> left + right;
            case "sub" -> left - right;
            case "mul" -> left * right;
            case "div" -> left / right;
            default -> throw new BattleScriptValueException("Unsupported number_binary_op op: " + optionalString(object, "op", "add"));
        };
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
                default -> throw new BattleScriptValueException("Unsupported compare op: " + op);
            };
        }
        if (left instanceof String leftText && right instanceof String rightText && "contains".equals(op)) {
            return leftText.contains(rightText);
        }
        return switch (op) {
            case "eq" -> Objects.equals(left, right);
            case "ne" -> !Objects.equals(left, right);
            default -> throw new BattleScriptValueException("Compare op " + op + " requires numeric operands.");
        };
    }

    private double randomNumber(JsonObject object, BattleScriptEvaluationContext context) {
        double min = evalNumber(require(object, "min", "random_number"), context);
        double max = evalNumber(require(object, "max", "random_number"), context);
        return min + context.deterministicRandom().nextDouble() * (max - min);
    }

    private int randomInt(JsonObject object, BattleScriptEvaluationContext context) {
        int min = requireIntegral(evalNumber(require(object, "min", "random_int"), context), "random_int.min");
        int max = requireIntegral(evalNumber(require(object, "max", "random_int"), context), "random_int.max");
        if (max < min) {
            int swap = min;
            min = max;
            max = swap;
        }
        return min + context.deterministicRandom().nextInt(max - min + 1);
    }

    private double stringToNumber(JsonObject object, BattleScriptEvaluationContext context) {
        String text = evalString(require(object, "text", "string_to_number"), context);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return evalNumber(require(object, "fallback", "string_to_number"), context);
        }
    }

    private double vectorComponent(JsonObject object, BattleScriptEvaluationContext context) {
        CanonicalVec3 vector = evalVector3(require(object, "vector", "vector_component"), context);
        return switch (optionalString(object, "component", "x").toLowerCase(Locale.ROOT)) {
            case "x" -> vector.x();
            case "y" -> vector.y();
            case "z" -> vector.z();
            default -> throw new BattleScriptValueException("Unsupported vector component: " + optionalString(object, "component", "x"));
        };
    }

    private double trig(JsonObject object, BattleScriptEvaluationContext context) {
        double radians = Math.toRadians(evalAngle(require(object, "angle", "trig"), context));
        return switch (optionalString(object, "op", "sin")) {
            case "sin" -> Math.sin(radians);
            case "cos" -> Math.cos(radians);
            case "tan" -> Math.tan(radians);
            default -> throw new BattleScriptValueException("Unsupported trig op: " + optionalString(object, "op", "sin"));
        };
    }

    private static JsonElement require(JsonObject object, String member, String type) {
        JsonElement element = object.get(member);
        if (element == null) {
            throw new BattleScriptValueException(type + "." + member + " is required.");
        }
        return element;
    }

    private static JsonArray requireArray(JsonObject object, String member, String type) {
        JsonElement element = require(object, member, type);
        if (!element.isJsonArray()) {
            throw new BattleScriptValueException(type + "." + member + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    private static JsonArray exactArray(JsonArray array, int size, String type) {
        if (array.size() != size) {
            throw new BattleScriptValueException(type + " literal must contain exactly " + size + " values.");
        }
        return array;
    }

    private static String optionalType(JsonObject object) {
        return optionalString(object, "type", "");
    }

    private static String optionalString(JsonObject object, String member, String fallback) {
        JsonElement element = object.get(member);
        return element == null ? fallback : element.getAsString();
    }

    private static BattleScriptValueException typeError(String expected, JsonElement expr) {
        return new BattleScriptValueException("Expected " + expected + " ValueExpr but got: " + expr);
    }

    private static int requireIntegral(double value, String path) {
        try {
            return BattleScriptValue.requireIntegral(value, path);
        } catch (IllegalArgumentException ex) {
            throw new BattleScriptValueException(ex.getMessage(), ex);
        }
    }
}
