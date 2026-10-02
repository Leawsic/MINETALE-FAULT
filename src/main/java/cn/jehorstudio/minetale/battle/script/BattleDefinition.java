package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public final class BattleDefinition {
    private final CompiledBattleDefinition compiled;

    public BattleDefinition(CompiledBattleDefinition compiled) {
        this.compiled = Objects.requireNonNull(compiled, "compiled");
    }

    public ResourceLocation id() {
        return this.compiled.id();
    }

    public int schemaVersion() {
        return this.compiled.schemaVersion();
    }

    public String hash() {
        return this.compiled.hash();
    }

    public JsonObject root() {
        return this.compiled.root();
    }

    public CompiledBattleDefinition compiled() {
        return this.compiled;
    }
}
