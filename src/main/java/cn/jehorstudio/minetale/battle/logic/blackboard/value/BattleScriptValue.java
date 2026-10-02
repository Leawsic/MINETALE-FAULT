package cn.jehorstudio.minetale.battle.logic.blackboard.value;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;

import java.util.Objects;

public record BattleScriptValue(
        Type type,
        Object value
) {
    public BattleScriptValue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        validate(type, value);
    }

    public static BattleScriptValue ofInt(int value) {
        return new BattleScriptValue(Type.INT, value);
    }

    public static BattleScriptValue ofDouble(double value) {
        return new BattleScriptValue(Type.DOUBLE, value);
    }

    public static BattleScriptValue ofBoolean(boolean value) {
        return new BattleScriptValue(Type.BOOLEAN, value);
    }

    public static BattleScriptValue ofString(String value) {
        return new BattleScriptValue(Type.STRING, Objects.requireNonNull(value, "value"));
    }

    public static BattleScriptValue ofVector2(double x, double y) {
        return new BattleScriptValue(Type.VECTOR2, new Vector2Value(x, y));
    }

    public static BattleScriptValue ofVector3(CanonicalVec3 value) {
        return new BattleScriptValue(Type.VECTOR3, Objects.requireNonNull(value, "value"));
    }

    public static BattleScriptValue parse(Type type, JsonElement element, String path) {
        if (element == null) {
            throw new IllegalArgumentException(path + " must be a " + type.jsonName() + ".");
        }
        return switch (type) {
            case INT -> {
                JsonPrimitive primitive = requirePrimitive(element, path, type);
                if (!primitive.isNumber()) {
                    throw new IllegalArgumentException(path + " must be an int.");
                }
                yield ofInt(requireIntegral(primitive.getAsDouble(), path));
            }
            case DOUBLE -> {
                JsonPrimitive primitive = requirePrimitive(element, path, type);
                if (!primitive.isNumber()) {
                    throw new IllegalArgumentException(path + " must be a double.");
                }
                yield ofDouble(primitive.getAsDouble());
            }
            case BOOLEAN -> {
                JsonPrimitive primitive = requirePrimitive(element, path, type);
                if (!primitive.isBoolean()) {
                    throw new IllegalArgumentException(path + " must be a boolean.");
                }
                yield ofBoolean(primitive.getAsBoolean());
            }
            case STRING -> {
                JsonPrimitive primitive = requirePrimitive(element, path, type);
                if (!primitive.isString()) {
                    throw new IllegalArgumentException(path + " must be a string.");
                }
                yield ofString(primitive.getAsString());
            }
            case VECTOR2 -> {
                JsonArray array = requireArray(element, path, 2);
                yield ofVector2(numberAt(array, 0, path), numberAt(array, 1, path));
            }
            case VECTOR3 -> {
                JsonArray array = requireArray(element, path, 3);
                yield ofVector3(new CanonicalVec3(numberAt(array, 0, path), numberAt(array, 1, path), numberAt(array, 2, path)));
            }
        };
    }

    public BattleScriptValue deepCopy() {
        return switch (this.type) {
            case INT -> ofInt((Integer) this.value);
            case DOUBLE -> ofDouble((Double) this.value);
            case BOOLEAN -> ofBoolean((Boolean) this.value);
            case STRING -> ofString((String) this.value);
            case VECTOR2 -> {
                Vector2Value vector = (Vector2Value) this.value;
                yield ofVector2(vector.x(), vector.y());
            }
            case VECTOR3 -> ofVector3((CanonicalVec3) this.value);
        };
    }

    public double asDouble() {
        return switch (this.type) {
            case INT -> ((Integer) this.value).doubleValue();
            case DOUBLE -> (Double) this.value;
            case BOOLEAN, STRING, VECTOR2, VECTOR3 -> throw new IllegalStateException("Value is not numeric: " + this.type);
        };
    }

    public int asInt() {
        return switch (this.type) {
            case INT -> (Integer) this.value;
            case DOUBLE -> throw new IllegalStateException("Value is not INT: " + this.type);
            case BOOLEAN, STRING, VECTOR2, VECTOR3 -> throw new IllegalStateException("Value is not numeric: " + this.type);
        };
    }

    public boolean asBoolean() {
        if (this.type != Type.BOOLEAN) {
            throw new IllegalStateException("Value is not boolean: " + this.type);
        }
        return (Boolean) this.value;
    }

    public String asString() {
        return String.valueOf(this.value);
    }

    public Vector2Value asVector2() {
        if (this.type != Type.VECTOR2) {
            throw new IllegalStateException("Value is not Vector2: " + this.type);
        }
        return (Vector2Value) this.value;
    }

    public CanonicalVec3 asVector3() {
        if (this.type != Type.VECTOR3) {
            throw new IllegalStateException("Value is not Vector3: " + this.type);
        }
        return (CanonicalVec3) this.value;
    }

    public BattleScriptValue withNumber(double number) {
        if (this.type == Type.INT) {
            return ofInt(requireIntegral(number, "INT assignment"));
        }
        return ofDouble(number);
    }

    public static int requireIntegral(double value, String path) {
        if (!Double.isFinite(value) || Math.rint(value) != value) {
            throw new IllegalArgumentException(path + " must be an integer, got: " + value);
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " is outside int range: " + value);
        }
        return (int) value;
    }

    private static void validate(Type type, Object value) {
        switch (type) {
            case INT -> {
                if (!(value instanceof Integer)) {
                    throw new IllegalArgumentException("INT value must be Integer.");
                }
            }
            case DOUBLE -> {
                if (!(value instanceof Double)) {
                    throw new IllegalArgumentException("DOUBLE value must be Double.");
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException("BOOLEAN value must be Boolean.");
                }
            }
            case STRING -> {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("STRING value must be String.");
                }
            }
            case VECTOR2 -> {
                if (!(value instanceof Vector2Value)) {
                    throw new IllegalArgumentException("VECTOR2 value must be Vector2Value.");
                }
            }
            case VECTOR3 -> {
                if (!(value instanceof CanonicalVec3)) {
                    throw new IllegalArgumentException("VECTOR3 value must be CanonicalVec3.");
                }
            }
        }
    }

    private static JsonPrimitive requirePrimitive(JsonElement element, String path, Type type) {
        if (!element.isJsonPrimitive()) {
            throw new IllegalArgumentException(path + " must be a " + type.jsonName() + ".");
        }
        return element.getAsJsonPrimitive();
    }

    private static JsonArray requireArray(JsonElement element, String path, int size) {
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be a " + size + "-number array.");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != size) {
            throw new IllegalArgumentException(path + " must contain exactly " + size + " numbers.");
        }
        return array;
    }

    private static double numberAt(JsonArray array, int index, String path) {
        JsonElement element = array.get(index);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + "[" + index + "] must be a number.");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(path + "[" + index + "] must be finite.");
        }
        return value;
    }

    public enum Type {
        INT("int"),
        DOUBLE("double"),
        BOOLEAN("boolean"),
        STRING("string"),
        VECTOR2("vector2"),
        VECTOR3("vector3");

        private final String jsonName;

        Type(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return this.jsonName;
        }

        public static Type parse(String value, String path) {
            for (Type type : values()) {
                if (type.jsonName.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException(path + " has unsupported variable type: " + value);
        }
    }

    public record Vector2Value(double x, double y) {
        public Vector2Value {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Vector2 components must be finite.");
            }
        }
    }
}
