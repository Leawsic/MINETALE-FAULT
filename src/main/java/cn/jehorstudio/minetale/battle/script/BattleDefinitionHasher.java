package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class BattleDefinitionHasher {
    private BattleDefinitionHasher() {
    }

    static String sha256(JsonElement element) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical(element).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private static String canonical(JsonElement element) {
        if (element == null || element instanceof JsonNull) {
            return "null";
        }
        if (element.isJsonObject()) {
            return canonicalObject(element.getAsJsonObject());
        }
        if (element.isJsonArray()) {
            return canonicalArray(element.getAsJsonArray());
        }
        if (element.isJsonPrimitive()) {
            return canonicalPrimitive(element.getAsJsonPrimitive());
        }
        throw new IllegalArgumentException("Unsupported JSON element: " + element);
    }

    private static String canonicalObject(JsonObject object) {
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(object.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            Map.Entry<String, JsonElement> entry = entries.get(i);
            builder.append(quote(entry.getKey())).append(':').append(canonical(entry.getValue()));
        }
        return builder.append('}').toString();
    }

    private static String canonicalArray(JsonArray array) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < array.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(canonical(array.get(i)));
        }
        return builder.append(']').toString();
    }

    private static String canonicalPrimitive(JsonPrimitive primitive) {
        if (primitive.isString()) {
            return quote(primitive.getAsString());
        }
        if (primitive.isBoolean()) {
            return Boolean.toString(primitive.getAsBoolean());
        }
        return primitive.getAsNumber().toString();
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
