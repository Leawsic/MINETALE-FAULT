package cn.jehorstudio.minetale.battle.script;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BattleScriptCatalog {
    private static volatile Map<ResourceLocation, BattleDefinition> definitions = Map.of();

    private BattleScriptCatalog() {
    }

    public static void replace(Map<ResourceLocation, BattleDefinition> loadedDefinitions) {
        definitions = Map.copyOf(Objects.requireNonNull(loadedDefinitions, "loadedDefinitions"));
    }

    public static Optional<BattleDefinition> get(ResourceLocation id) {
        return Optional.ofNullable(definitions.get(Objects.requireNonNull(id, "id")));
    }

    public static Collection<BattleDefinition> definitions() {
        return definitions.values();
    }

    public static int size() {
        return definitions.size();
    }
}
