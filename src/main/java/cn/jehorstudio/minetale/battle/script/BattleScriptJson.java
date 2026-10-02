package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BattleScriptJson {
    private BattleScriptJson() {
    }

    static JsonObject requireObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw new BattleScriptValidationException(path + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    static JsonObject requireObject(JsonObject object, String member, String path) {
        return requireObject(object.get(member), path + "." + member);
    }

    static Optional<JsonObject> optionalObject(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonObject()) {
            throw new BattleScriptValidationException(member + " must be an object.");
        }
        return Optional.of(element.getAsJsonObject());
    }

    static JsonArray optionalArray(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null) {
            return new JsonArray();
        }
        if (!element.isJsonArray()) {
            throw new BattleScriptValidationException(member + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    static JsonArray requireArray(JsonObject object, String member, String path) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonArray()) {
            throw new BattleScriptValidationException(path + "." + member + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    static String requireString(JsonObject object, String member, String path) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new BattleScriptValidationException(path + "." + member + " must be a string.");
        }
        return element.getAsString();
    }

    static Optional<String> optionalString(JsonObject object, String member) {
        JsonElement element = object.get(member);
        if (element == null) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive()) {
            throw new BattleScriptValidationException(member + " must be a string.");
        }
        return Optional.of(element.getAsString());
    }

    static int requirePositiveInt(JsonObject object, String member, String path) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new BattleScriptValidationException(path + "." + member + " must be a number.");
        }
        int value = element.getAsInt();
        if (value <= 0) {
            throw new BattleScriptValidationException(path + "." + member + " must be positive.");
        }
        return value;
    }

    static double optionalDouble(JsonObject object, String member, double fallback) {
        JsonElement element = object.get(member);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new BattleScriptValidationException(member + " must be a number.");
        }
        return element.getAsDouble();
    }

    static boolean optionalBoolean(JsonObject object, String member, boolean fallback) {
        JsonElement element = object.get(member);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new BattleScriptValidationException(member + " must be a boolean.");
        }
        return element.getAsBoolean();
    }

    static List<String> stringList(JsonArray array, String path) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new BattleScriptValidationException(path + "[" + i + "] must be a string.");
            }
            result.add(element.getAsString());
        }
        return List.copyOf(result);
    }

    static List<JsonObject> objectList(JsonArray array, String path) {
        List<JsonObject> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            result.add(requireObject(array.get(i), path + "[" + i + "]").deepCopy());
        }
        return List.copyOf(result);
    }

    static ResourceLocation requireResourceLocation(String value, String path) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new BattleScriptValidationException(path + " must be a valid resource location.");
        }
        return location;
    }

    static boolean isNamespaced(String value) {
        return value.indexOf(':') >= 0;
    }

    static JsonObject deepCopyObject(JsonObject object) {
        return object == null ? new JsonObject() : object.deepCopy();
    }

    static JsonPrimitive primitive(String value) {
        return new JsonPrimitive(value);
    }
}
