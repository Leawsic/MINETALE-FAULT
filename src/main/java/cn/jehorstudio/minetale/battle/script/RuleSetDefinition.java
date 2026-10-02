package cn.jehorstudio.minetale.battle.script;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RuleSetDefinition(
        String localId,
        Optional<ResourceLocation> globalId,
        List<RuleDefinition> rules
) {
    public RuleSetDefinition {
        Objects.requireNonNull(localId, "localId");
        globalId = Objects.requireNonNull(globalId, "globalId");
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (localId.isBlank()) {
            throw new IllegalArgumentException("Ruleset localId must not be blank.");
        }
    }
}
