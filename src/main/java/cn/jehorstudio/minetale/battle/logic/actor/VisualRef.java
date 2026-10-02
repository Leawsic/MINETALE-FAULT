package cn.jehorstudio.minetale.battle.logic.actor;

import cn.jehorstudio.minetale.lib.GeckoModels;
import cn.jehorstudio.minetale.lib.ObjModels;
import cn.jehorstudio.minetale.lib.Sprites;
import com.google.gson.JsonElement;

import java.util.Locale;
import java.util.Objects;

public record VisualRef(
        VisualType type,
        ObjModels objModel,
        GeckoModels geckoModel,
        Sprites sprite,
        String specialKey,
        ActorAppearance appearance
) {
    private static final VisualRef VOID = new VisualRef(VisualType.VOID, null, null, null, null, null);

    public VisualRef {
        Objects.requireNonNull(type, "type");
        switch (type) {
            case OBJ_MODEL -> requireOnlyObjModel(objModel, geckoModel, sprite, specialKey, appearance);
            case GECKO_MODEL -> requireOnlyGeckoModel(objModel, geckoModel, sprite, specialKey, appearance);
            case SPRITE -> requireOnlySprite(objModel, geckoModel, sprite, specialKey, appearance);
            case APPEARANCE -> requireOnlyAppearance(objModel, geckoModel, sprite, specialKey, appearance);
            case VOID -> requireNoPayload(objModel, geckoModel, sprite, specialKey, appearance);
            case SPECIAL -> requireOnlySpecial(objModel, geckoModel, sprite, specialKey, appearance);
        }
    }

    public static VisualRef objModel(ObjModels objModel) {
        return new VisualRef(VisualType.OBJ_MODEL, objModel, null, null, null, null);
    }

    public static VisualRef geckoModel(GeckoModels geckoModel) {
        return new VisualRef(VisualType.GECKO_MODEL, null, geckoModel, null, null, null);
    }

    public static VisualRef sprite(Sprites sprite) {
        return new VisualRef(VisualType.SPRITE, null, null, sprite, null, null);
    }

    public static VisualRef voidVisual() {
        return VOID;
    }

    public static VisualRef special(String specialKey) {
        return new VisualRef(VisualType.SPECIAL, null, null, null, specialKey, null);
    }

    public static VisualRef appearance(ActorAppearance appearance) {
        return new VisualRef(VisualType.APPEARANCE, null, null, null, null, appearance);
    }

    public static VisualRef fromJson(JsonElement element) {
        if (element == null) {
            return objModel(ObjModels.BULLET1);
        }
        if (element.isJsonObject()) {
            return appearance(ActorAppearance.fromJson(element.getAsJsonObject()));
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("visual must be a legacy string or actor appearance object.");
        }
        String value = element.getAsString().toUpperCase(Locale.ROOT);
        try {
            return objModel(ObjModels.valueOf(value));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown obj visual: " + value, exception);
        }
    }

    private static void requireOnlyObjModel(ObjModels objModel, GeckoModels geckoModel, Sprites sprite, String specialKey, ActorAppearance appearance) {
        Objects.requireNonNull(objModel, "objModel");
        requireNoPayload(null, geckoModel, sprite, specialKey, appearance);
    }

    private static void requireOnlyGeckoModel(ObjModels objModel, GeckoModels geckoModel, Sprites sprite, String specialKey, ActorAppearance appearance) {
        Objects.requireNonNull(geckoModel, "geckoModel");
        requireNoPayload(objModel, null, sprite, specialKey, appearance);
    }

    private static void requireOnlySprite(ObjModels objModel, GeckoModels geckoModel, Sprites sprite, String specialKey, ActorAppearance appearance) {
        Objects.requireNonNull(sprite, "sprite");
        requireNoPayload(objModel, geckoModel, null, specialKey, appearance);
    }

    private static void requireOnlySpecial(ObjModels objModel, GeckoModels geckoModel, Sprites sprite, String specialKey, ActorAppearance appearance) {
        requireNoPayload(objModel, geckoModel, sprite, null, appearance);
        if (specialKey == null || specialKey.isBlank()) {
            throw new IllegalArgumentException("specialKey must not be blank.");
        }
    }

    private static void requireOnlyAppearance(ObjModels objModel, GeckoModels geckoModel, Sprites sprite, String specialKey, ActorAppearance appearance) {
        Objects.requireNonNull(appearance, "appearance");
        requireNoPayload(objModel, geckoModel, sprite, specialKey, null);
    }

    private static void requireNoPayload(ObjModels objModel, GeckoModels geckoModel, Sprites sprite, String specialKey, ActorAppearance appearance) {
        if (objModel != null || geckoModel != null || sprite != null || specialKey != null || appearance != null) {
            throw new IllegalArgumentException("VisualRef payload does not match visual type.");
        }
    }
}
