package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record ActorPrefabDefinition(
        String localId,
        Optional<ResourceLocation> globalId,
        String actorType,
        JsonObject root
) {
    public ActorPrefabDefinition {
        Objects.requireNonNull(localId, "localId");
        globalId = Objects.requireNonNull(globalId, "globalId");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(root, "root");
        if (localId.isBlank()) {
            throw new IllegalArgumentException("Actor prefab localId must not be blank.");
        }
        root = root.deepCopy();
    }

    public JsonObject root() {
        return this.root.deepCopy();
    }
}
