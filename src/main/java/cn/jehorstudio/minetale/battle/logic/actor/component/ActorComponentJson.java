package cn.jehorstudio.minetale.battle.logic.actor.component;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

final class ActorComponentJson {
    private ActorComponentJson() {
    }

    static double doubleValue(JsonObject object, String member, double fallback) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        double value = element.getAsDouble();
        return Double.isFinite(value) ? value : fallback;
    }

    static int intValue(JsonObject object, String member, int fallback) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsInt();
    }

    static boolean booleanValue(JsonObject object, String member, boolean fallback) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return element.getAsBoolean();
    }

    static String stringValue(JsonObject object, String member, String fallback) {
        JsonElement element = object.get(member);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        return element.getAsString();
    }

    static String lowerString(JsonObject object, String member, String fallback) {
        return stringValue(object, member, fallback).toLowerCase(Locale.ROOT);
    }
}
