package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PatternDefinition(
        String localId,
        Optional<ResourceLocation> globalId,
        List<JsonObject> actions
) {
    public PatternDefinition {
        Objects.requireNonNull(localId, "localId");
        globalId = Objects.requireNonNull(globalId, "globalId");
        actions = actions.stream().map(JsonObject::deepCopy).toList();
        if (localId.isBlank()) {
            throw new IllegalArgumentException("Pattern localId must not be blank.");
        }
    }

    public List<JsonObject> actions() {
        return this.actions.stream().map(JsonObject::deepCopy).toList();
    }
}
