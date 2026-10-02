package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public record OutcomeDefinition(
        String localId,
        Optional<ResourceLocation> globalId,
        BattleResultState resultState,
        JsonObject root
) {
    public OutcomeDefinition {
        Objects.requireNonNull(localId, "localId");
        globalId = Objects.requireNonNull(globalId, "globalId");
        Objects.requireNonNull(resultState, "resultState");
        Objects.requireNonNull(root, "root");
        if (localId.isBlank()) {
            throw new IllegalArgumentException("Outcome localId must not be blank.");
        }
        root = root.deepCopy();
    }

    public JsonObject root() {
        return this.root.deepCopy();
    }
}
