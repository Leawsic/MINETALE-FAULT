package cn.jehorstudio.minetale.battle.script;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record CompiledBattleDefinition(
        ResourceLocation id,
        int schemaVersion,
        String hash,
        JsonObject root,
        Map<String, VariableDefinition> variables,
        Map<String, ActorPrefabDefinition> actorPrefabs,
        Map<ResourceLocation, ActorPrefabDefinition> globalActorPrefabs,
        Map<String, RuleSetDefinition> ruleSets,
        Map<ResourceLocation, RuleSetDefinition> globalRuleSets,
        Map<String, PatternDefinition> patterns,
        Map<ResourceLocation, PatternDefinition> globalPatterns,
        Map<String, OutcomeDefinition> outcomes,
        Map<ResourceLocation, OutcomeDefinition> globalOutcomes,
        PhaseGraphDefinition phaseGraph,
        BattleScriptBudgets budgets
) {
    public CompiledBattleDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(root, "root");
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
        actorPrefabs = Map.copyOf(Objects.requireNonNull(actorPrefabs, "actorPrefabs"));
        globalActorPrefabs = Map.copyOf(Objects.requireNonNull(globalActorPrefabs, "globalActorPrefabs"));
        ruleSets = Map.copyOf(Objects.requireNonNull(ruleSets, "ruleSets"));
        globalRuleSets = Map.copyOf(Objects.requireNonNull(globalRuleSets, "globalRuleSets"));
        patterns = Map.copyOf(Objects.requireNonNull(patterns, "patterns"));
        globalPatterns = Map.copyOf(Objects.requireNonNull(globalPatterns, "globalPatterns"));
        outcomes = Map.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
        globalOutcomes = Map.copyOf(Objects.requireNonNull(globalOutcomes, "globalOutcomes"));
        Objects.requireNonNull(phaseGraph, "phaseGraph");
        Objects.requireNonNull(budgets, "budgets");
        root = root.deepCopy();
    }

    public JsonObject root() {
        return this.root.deepCopy();
    }

    public Optional<ActorPrefabDefinition> resolveActorPrefab(String ref) {
        Objects.requireNonNull(ref, "ref");
        if (BattleScriptJson.isNamespaced(ref)) {
            return Optional.ofNullable(this.globalActorPrefabs.get(ResourceLocation.parse(ref)));
        }
        return Optional.ofNullable(this.actorPrefabs.get(ref));
    }

    public Optional<RuleSetDefinition> resolveRuleSet(String ref) {
        Objects.requireNonNull(ref, "ref");
        if (BattleScriptJson.isNamespaced(ref)) {
            return Optional.ofNullable(this.globalRuleSets.get(ResourceLocation.parse(ref)));
        }
        return Optional.ofNullable(this.ruleSets.get(ref));
    }

    public Optional<PatternDefinition> resolvePattern(String ref) {
        Objects.requireNonNull(ref, "ref");
        if (BattleScriptJson.isNamespaced(ref)) {
            return Optional.ofNullable(this.globalPatterns.get(ResourceLocation.parse(ref)));
        }
        return Optional.ofNullable(this.patterns.get(ref));
    }

    public Optional<OutcomeDefinition> resolveOutcome(String ref) {
        Objects.requireNonNull(ref, "ref");
        if (BattleScriptJson.isNamespaced(ref)) {
            return Optional.ofNullable(this.globalOutcomes.get(ResourceLocation.parse(ref)));
        }
        return Optional.ofNullable(this.outcomes.get(ref));
    }
}
