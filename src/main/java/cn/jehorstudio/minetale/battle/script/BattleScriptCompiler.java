package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import cn.jehorstudio.minetale.battle.logic.blackboard.value.BattleScriptValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public final class BattleScriptCompiler {
    private BattleScriptCompiler() {
    }

    public static Map<ResourceLocation, BattleDefinition> compileAll(Map<ResourceLocation, JsonElement> loadedData) {
        CompileResult result = compileLenient(loadedData);
        if (!result.rejected().isEmpty()) {
            RuntimeException first = result.rejected().values().iterator().next();
            throw first;
        }
        return result.definitions();
    }

    public static CompileResult compileLenient(Map<ResourceLocation, JsonElement> loadedData) {
        Map<ResourceLocation, ParsedDefinition> parsedDefinitions = new LinkedHashMap<>();
        Map<ResourceLocation, RuntimeException> rejected = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : loadedData.entrySet()) {
            try {
                parsedDefinitions.put(entry.getKey(), parse(entry.getKey(), entry.getValue()));
            } catch (RuntimeException ex) {
                rejected.put(entry.getKey(), ex);
            }
        }

        ExportIndex exportIndex = ExportIndex.create(parsedDefinitions);
        Map<ResourceLocation, BattleDefinition> compiled = new LinkedHashMap<>();
        for (ParsedDefinition parsed : parsedDefinitions.values()) {
            try {
                compiled.put(parsed.id(), compile(parsed, exportIndex));
            } catch (RuntimeException ex) {
                rejected.put(parsed.id(), ex);
            }
        }
        return new CompileResult(Map.copyOf(compiled), Map.copyOf(rejected));
    }

    private static ParsedDefinition parse(ResourceLocation id, JsonElement element) {
        JsonObject root = BattleScriptJson.requireObject(element, id.toString());
        int schemaVersion = BattleScriptJson.requirePositiveInt(root, "schemaVersion", id.toString());
        BattleScriptJson.optionalString(root, "id").ifPresent(declaredId -> {
            ResourceLocation parsedId = BattleScriptJson.requireResourceLocation(declaredId, id + ".id");
            if (!parsedId.equals(id)) {
                throw new BattleScriptValidationException(id + ".id must match resource id " + id + ".");
            }
        });

        return new ParsedDefinition(
                id,
                schemaVersion,
                root.deepCopy(),
                BattleDefinitionHasher.sha256(root),
                parseVariables(root, id + ".variables"),
                parseActorPrefabs(root, id + ".actors"),
                parseRuleSets(root, id + ".rulesets"),
                parsePatterns(root, id + ".patterns"),
                parseOutcomes(root, id + ".outcomes"),
                parsePhaseGraph(root, id + ".phaseGraph"),
                BattleScriptBudgets.fromJson(root)
        );
    }

    private static BattleDefinition compile(ParsedDefinition parsed, ExportIndex exportIndex) {
        ImportedDefinitions imported = importDefinitions(parsed, exportIndex);

        rejectLocalConflicts(parsed.id(), "actor prefab", imported.actorPrefabs(), parsed.actorPrefabs());
        Map<String, ActorPrefabDefinition> actorPrefabs = new LinkedHashMap<>(imported.actorPrefabs());
        actorPrefabs.putAll(parsed.actorPrefabs());
        Map<ResourceLocation, ActorPrefabDefinition> globalActorPrefabs = new LinkedHashMap<>(imported.globalActorPrefabs());
        putGlobalActors(globalActorPrefabs, parsed.actorPrefabs());

        rejectLocalConflicts(parsed.id(), "ruleset", imported.ruleSets(), parsed.ruleSets());
        Map<String, RuleSetDefinition> ruleSets = new LinkedHashMap<>(imported.ruleSets());
        ruleSets.putAll(parsed.ruleSets());
        Map<ResourceLocation, RuleSetDefinition> globalRuleSets = new LinkedHashMap<>(imported.globalRuleSets());
        putGlobalRuleSets(globalRuleSets, parsed.ruleSets());

        rejectLocalConflicts(parsed.id(), "pattern", imported.patterns(), parsed.patterns());
        Map<String, PatternDefinition> patterns = new LinkedHashMap<>(imported.patterns());
        patterns.putAll(parsed.patterns());
        Map<ResourceLocation, PatternDefinition> globalPatterns = new LinkedHashMap<>(imported.globalPatterns());
        putGlobalPatterns(globalPatterns, parsed.patterns());

        rejectLocalConflicts(parsed.id(), "outcome", imported.outcomes(), parsed.outcomes());
        Map<String, OutcomeDefinition> outcomes = new LinkedHashMap<>(imported.outcomes());
        outcomes.putAll(parsed.outcomes());
        Map<ResourceLocation, OutcomeDefinition> globalOutcomes = new LinkedHashMap<>(imported.globalOutcomes());
        putGlobalOutcomes(globalOutcomes, parsed.outcomes());

        CompiledBattleDefinition compiled = new CompiledBattleDefinition(
                parsed.id(),
                parsed.schemaVersion(),
                compiledHash(parsed, imported),
                parsed.root(),
                parsed.variables(),
                actorPrefabs,
                globalActorPrefabs,
                ruleSets,
                globalRuleSets,
                patterns,
                globalPatterns,
                outcomes,
                globalOutcomes,
                parsed.phaseGraph(),
                parsed.budgets()
        );
        BattleScriptValidator.validate(compiled);
        return new BattleDefinition(compiled);
    }

    private static Map<String, VariableDefinition> parseVariables(JsonObject root, String path) {
        Map<String, VariableDefinition> variables = new LinkedHashMap<>();
        JsonObject variablesRoot = BattleScriptJson.optionalObject(root, "variables").orElseGet(JsonObject::new);
        for (Map.Entry<String, JsonElement> entry : variablesRoot.entrySet()) {
            String id = entry.getKey();
            if (id.isBlank() || BattleScriptJson.isNamespaced(id)) {
                throw new BattleScriptValidationException(path + "." + id + " must be a naked local variable id.");
            }
            JsonObject variable = BattleScriptJson.requireObject(entry.getValue(), path + "." + id);
            String typeText = BattleScriptJson.requireString(variable, "type", path + "." + id);
            BattleScriptValue.Type type = BattleScriptValue.Type.parse(typeText, path + "." + id + ".type");
            JsonElement initial = variable.get("initial");
            if (initial == null) {
                throw new BattleScriptValidationException(path + "." + id + ".initial is required.");
            }
            variables.put(id, new VariableDefinition(
                    id,
                    type,
                    initial
            ));
        }
        return Map.copyOf(variables);
    }

    private static Map<String, ActorPrefabDefinition> parseActorPrefabs(JsonObject root, String path) {
        Map<String, ActorPrefabDefinition> prefabs = new LinkedHashMap<>();
        JsonObject actorsRoot = BattleScriptJson.optionalObject(root, "actors").orElseGet(JsonObject::new);
        for (Map.Entry<String, JsonElement> entry : actorsRoot.entrySet()) {
            String localId = requireLocalId(entry.getKey(), path);
            JsonObject actor = BattleScriptJson.requireObject(entry.getValue(), path + "." + localId);
            String type = BattleScriptJson.requireString(actor, "type", path + "." + localId);
            Optional<ResourceLocation> globalId = optionalGlobalId(actor, path + "." + localId);
            prefabs.put(localId, new ActorPrefabDefinition(localId, globalId, type, actor));
        }
        return Map.copyOf(prefabs);
    }

    private static Map<String, RuleSetDefinition> parseRuleSets(JsonObject root, String path) {
        Map<String, RuleSetDefinition> ruleSets = new LinkedHashMap<>();
        JsonObject rulesetsRoot = BattleScriptJson.optionalObject(root, "rulesets").orElseGet(JsonObject::new);
        for (Map.Entry<String, JsonElement> entry : rulesetsRoot.entrySet()) {
            String localId = requireLocalId(entry.getKey(), path);
            JsonObject ruleset = BattleScriptJson.requireObject(entry.getValue(), path + "." + localId);
            Optional<ResourceLocation> globalId = optionalGlobalId(ruleset, path + "." + localId);
            JsonArray rules = BattleScriptJson.requireArray(ruleset, "rules", path + "." + localId);
            List<RuleDefinition> parsedRules = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                JsonObject rule = BattleScriptJson.requireObject(rules.get(i), path + "." + localId + ".rules[" + i + "]");
                String domain = BattleScriptJson.requireString(rule, "domain", path + "." + localId + ".rules[" + i + "]");
                parsedRules.add(new RuleDefinition(domain, rule));
            }
            ruleSets.put(localId, new RuleSetDefinition(localId, globalId, parsedRules));
        }
        return Map.copyOf(ruleSets);
    }

    private static Map<String, PatternDefinition> parsePatterns(JsonObject root, String path) {
        Map<String, PatternDefinition> patterns = new LinkedHashMap<>();
        JsonObject patternsRoot = BattleScriptJson.optionalObject(root, "patterns").orElseGet(JsonObject::new);
        for (Map.Entry<String, JsonElement> entry : patternsRoot.entrySet()) {
            String localId = requireLocalId(entry.getKey(), path);
            Optional<ResourceLocation> globalId = Optional.empty();
            List<JsonObject> actions;
            if (entry.getValue().isJsonArray()) {
                actions = BattleScriptJson.objectList(entry.getValue().getAsJsonArray(), path + "." + localId);
            } else {
                JsonObject pattern = BattleScriptJson.requireObject(entry.getValue(), path + "." + localId);
                globalId = optionalGlobalId(pattern, path + "." + localId);
                actions = BattleScriptJson.objectList(BattleScriptJson.optionalArray(pattern, "actions"), path + "." + localId + ".actions");
            }
            patterns.put(localId, new PatternDefinition(localId, globalId, actions));
        }
        return Map.copyOf(patterns);
    }

    private static Map<String, OutcomeDefinition> parseOutcomes(JsonObject root, String path) {
        Map<String, OutcomeDefinition> outcomes = new LinkedHashMap<>();
        JsonObject outcomesRoot = BattleScriptJson.optionalObject(root, "outcomes").orElseGet(JsonObject::new);
        for (Map.Entry<String, JsonElement> entry : outcomesRoot.entrySet()) {
            String localId = requireLocalId(entry.getKey(), path);
            JsonObject outcome = BattleScriptJson.requireObject(entry.getValue(), path + "." + localId);
            Optional<ResourceLocation> globalId = optionalGlobalId(outcome, path + "." + localId);
            String result = BattleScriptJson.optionalString(outcome, "result").orElse("victory");
            outcomes.put(localId, new OutcomeDefinition(
                    localId,
                    globalId,
                    parseResultState(result, path + "." + localId + ".result"),
                    outcome
            ));
        }
        return Map.copyOf(outcomes);
    }

    private static PhaseGraphDefinition parsePhaseGraph(JsonObject root, String path) {
        JsonObject phaseGraph = BattleScriptJson.requireObject(root, "phaseGraph", path);
        String entryPhase = BattleScriptJson.requireString(phaseGraph, "entry", path);
        JsonObject phasesRoot = BattleScriptJson.requireObject(phaseGraph, "phases", path);
        Map<String, PhaseDefinition> phases = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : phasesRoot.entrySet()) {
            String phaseId = requireLocalId(entry.getKey(), path + ".phases");
            phases.put(phaseId, parsePhase(phaseId, entry.getValue(), path + ".phases." + phaseId));
        }
        return new PhaseGraphDefinition(entryPhase, phases);
    }

    private static PhaseDefinition parsePhase(String id, JsonElement element, String path) {
        JsonObject phase = BattleScriptJson.requireObject(element, path);
        List<String> ruleSetRefs = BattleScriptJson.stringList(BattleScriptJson.optionalArray(phase, "rulesets"), path + ".rulesets");
        List<JsonObject> onEnter = BattleScriptJson.objectList(BattleScriptJson.optionalArray(phase, "onEnter"), path + ".onEnter");
        List<JsonObject> onTick = BattleScriptJson.objectList(BattleScriptJson.optionalArray(phase, "onTick"), path + ".onTick");
        List<JsonObject> onExit = BattleScriptJson.objectList(BattleScriptJson.optionalArray(phase, "onExit"), path + ".onExit");
        List<TransitionDefinition> transitions = parseTransitions(BattleScriptJson.optionalArray(phase, "transitions"), path + ".transitions");
        Duration duration = parseDuration(phase, path);
        return new PhaseDefinition(
                id,
                ruleSetRefs,
                onEnter,
                onTick,
                onExit,
                transitions,
                duration.seconds(),
                duration.ticks()
        );
    }

    private static List<TransitionDefinition> parseTransitions(JsonArray transitions, String path) {
        List<TransitionDefinition> parsed = new ArrayList<>();
        for (int i = 0; i < transitions.size(); i++) {
            JsonObject transition = BattleScriptJson.requireObject(transitions.get(i), path + "[" + i + "]");
            JsonObject condition = BattleScriptJson.requireObject(transition, "when", path + "[" + i + "]");
            Optional<String> to = BattleScriptJson.optionalString(transition, "to");
            boolean endBattle = BattleScriptJson.optionalBoolean(transition, "endBattle", false);
            parsed.add(new TransitionDefinition(condition, to, endBattle));
        }
        return List.copyOf(parsed);
    }

    private static Duration parseDuration(JsonObject phase, String path) {
        Optional<JsonObject> duration = BattleScriptJson.optionalObject(phase, "duration");
        if (duration.isEmpty()) {
            return new Duration(OptionalDouble.empty(), OptionalLong.empty());
        }
        OptionalDouble seconds = OptionalDouble.empty();
        OptionalLong ticks = OptionalLong.empty();
        JsonElement secondsElement = duration.get().get("seconds");
        if (secondsElement != null) {
            if (!secondsElement.isJsonPrimitive() || !secondsElement.getAsJsonPrimitive().isNumber()) {
                throw new BattleScriptValidationException(path + ".duration.seconds must be a number.");
            }
            double value = secondsElement.getAsDouble();
            if (!Double.isFinite(value) || value < 0.0D) {
                throw new BattleScriptValidationException(path + ".duration.seconds must be finite and >= 0.");
            }
            seconds = OptionalDouble.of(value);
        }
        JsonElement ticksElement = duration.get().get("ticks");
        if (ticksElement != null) {
            if (!ticksElement.isJsonPrimitive() || !ticksElement.getAsJsonPrimitive().isNumber()) {
                throw new BattleScriptValidationException(path + ".duration.ticks must be a number.");
            }
            long value = ticksElement.getAsLong();
            if (value < 0L) {
                throw new BattleScriptValidationException(path + ".duration.ticks must be >= 0.");
            }
            ticks = OptionalLong.of(value);
        }
        return new Duration(seconds, ticks);
    }

    private static ImportedDefinitions importDefinitions(ParsedDefinition parsed, ExportIndex exportIndex) {
        Map<String, ActorPrefabDefinition> actorPrefabs = new LinkedHashMap<>();
        Map<ResourceLocation, ActorPrefabDefinition> globalActorPrefabs = new LinkedHashMap<>();
        Map<String, RuleSetDefinition> ruleSets = new LinkedHashMap<>();
        Map<ResourceLocation, RuleSetDefinition> globalRuleSets = new LinkedHashMap<>();
        Map<String, PatternDefinition> patterns = new LinkedHashMap<>();
        Map<ResourceLocation, PatternDefinition> globalPatterns = new LinkedHashMap<>();
        Map<String, OutcomeDefinition> outcomes = new LinkedHashMap<>();
        Map<ResourceLocation, OutcomeDefinition> globalOutcomes = new LinkedHashMap<>();
        List<String> importHashes = new ArrayList<>();

        JsonObject imports = BattleScriptJson.optionalObject(parsed.root(), "imports").orElseGet(JsonObject::new);
        for (String idText : BattleScriptJson.stringList(BattleScriptJson.optionalArray(imports, "actorPrefabs"), parsed.id() + ".imports.actorPrefabs")) {
            ResourceLocation id = BattleScriptJson.requireResourceLocation(idText, parsed.id() + ".imports.actorPrefabs");
            Export<ActorPrefabDefinition> export = exportIndex.actorPrefabs().get(id);
            if (export == null) {
                throw new BattleScriptValidationException(parsed.id() + " imports missing actor prefab " + id + ".");
            }
            putImportedLocal(actorPrefabs, export.value().localId(), export.value(), parsed.id(), "actor prefab", id);
            globalActorPrefabs.put(id, export.value());
            importHashes.add(export.sourceHash());
        }
        for (String idText : BattleScriptJson.stringList(BattleScriptJson.optionalArray(imports, "rulesets"), parsed.id() + ".imports.rulesets")) {
            ResourceLocation id = BattleScriptJson.requireResourceLocation(idText, parsed.id() + ".imports.rulesets");
            Export<RuleSetDefinition> export = exportIndex.ruleSets().get(id);
            if (export == null) {
                throw new BattleScriptValidationException(parsed.id() + " imports missing ruleset " + id + ".");
            }
            putImportedLocal(ruleSets, export.value().localId(), export.value(), parsed.id(), "ruleset", id);
            globalRuleSets.put(id, export.value());
            importHashes.add(export.sourceHash());
        }
        for (String idText : BattleScriptJson.stringList(BattleScriptJson.optionalArray(imports, "patterns"), parsed.id() + ".imports.patterns")) {
            ResourceLocation id = BattleScriptJson.requireResourceLocation(idText, parsed.id() + ".imports.patterns");
            Export<PatternDefinition> export = exportIndex.patterns().get(id);
            if (export == null) {
                throw new BattleScriptValidationException(parsed.id() + " imports missing pattern " + id + ".");
            }
            putImportedLocal(patterns, export.value().localId(), export.value(), parsed.id(), "pattern", id);
            globalPatterns.put(id, export.value());
            importHashes.add(export.sourceHash());
        }
        for (String idText : BattleScriptJson.stringList(BattleScriptJson.optionalArray(imports, "outcomes"), parsed.id() + ".imports.outcomes")) {
            ResourceLocation id = BattleScriptJson.requireResourceLocation(idText, parsed.id() + ".imports.outcomes");
            Export<OutcomeDefinition> export = exportIndex.outcomes().get(id);
            if (export == null) {
                throw new BattleScriptValidationException(parsed.id() + " imports missing outcome " + id + ".");
            }
            putImportedLocal(outcomes, export.value().localId(), export.value(), parsed.id(), "outcome", id);
            globalOutcomes.put(id, export.value());
            importHashes.add(export.sourceHash());
        }

        return new ImportedDefinitions(
                actorPrefabs,
                globalActorPrefabs,
                ruleSets,
                globalRuleSets,
                patterns,
                globalPatterns,
                outcomes,
                globalOutcomes,
                List.copyOf(importHashes)
        );
    }

    private static String compiledHash(ParsedDefinition parsed, ImportedDefinitions imported) {
        JsonObject input = parsed.root().deepCopy();
        JsonArray importHashes = new JsonArray();
        imported.importHashes().stream().sorted().forEach(importHashes::add);
        input.add("_resolvedImportHashes", importHashes);
        return BattleDefinitionHasher.sha256(input);
    }

    private static <T> void putImportedLocal(
            Map<String, T> target,
            String localId,
            T value,
            ResourceLocation battleId,
            String kind,
            ResourceLocation importedId
    ) {
        if (target.containsKey(localId)) {
            throw new BattleScriptValidationException(
                    battleId + " imports multiple " + kind + " definitions with local id '" + localId
                            + "'; latest imported id was " + importedId + "."
            );
        }
        target.put(localId, value);
    }

    private static void rejectLocalConflicts(
            ResourceLocation battleId,
            String kind,
            Map<String, ?> imported,
            Map<String, ?> local
    ) {
        for (String localId : local.keySet()) {
            if (imported.containsKey(localId)) {
                throw new BattleScriptValidationException(
                        battleId + " has inline " + kind + " local id '" + localId
                                + "' that conflicts with an imported " + kind + "."
                );
            }
        }
    }

    private static BattleResultState parseResultState(String result, String path) {
        try {
            return BattleResultState.valueOf(result.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BattleScriptValidationException(path + " has unsupported battle result: " + result, ex);
        }
    }

    private static Optional<ResourceLocation> optionalGlobalId(JsonObject object, String path) {
        return BattleScriptJson.optionalString(object, "id")
                .map(id -> BattleScriptJson.requireResourceLocation(id, path + ".id"));
    }

    private static String requireLocalId(String id, String path) {
        if (id.isBlank() || BattleScriptJson.isNamespaced(id)) {
            throw new BattleScriptValidationException(path + "." + id + " must be a naked local id.");
        }
        return id;
    }

    private static void putGlobalActors(Map<ResourceLocation, ActorPrefabDefinition> target, Map<String, ActorPrefabDefinition> definitions) {
        for (ActorPrefabDefinition definition : definitions.values()) {
            definition.globalId().ifPresent(id -> target.put(id, definition));
        }
    }

    private static void putGlobalRuleSets(Map<ResourceLocation, RuleSetDefinition> target, Map<String, RuleSetDefinition> definitions) {
        for (RuleSetDefinition definition : definitions.values()) {
            definition.globalId().ifPresent(id -> target.put(id, definition));
        }
    }

    private static void putGlobalPatterns(Map<ResourceLocation, PatternDefinition> target, Map<String, PatternDefinition> definitions) {
        for (PatternDefinition definition : definitions.values()) {
            definition.globalId().ifPresent(id -> target.put(id, definition));
        }
    }

    private static void putGlobalOutcomes(Map<ResourceLocation, OutcomeDefinition> target, Map<String, OutcomeDefinition> definitions) {
        for (OutcomeDefinition definition : definitions.values()) {
            definition.globalId().ifPresent(id -> target.put(id, definition));
        }
    }

    private record ParsedDefinition(
            ResourceLocation id,
            int schemaVersion,
            JsonObject root,
            String rawHash,
            Map<String, VariableDefinition> variables,
            Map<String, ActorPrefabDefinition> actorPrefabs,
            Map<String, RuleSetDefinition> ruleSets,
            Map<String, PatternDefinition> patterns,
            Map<String, OutcomeDefinition> outcomes,
            PhaseGraphDefinition phaseGraph,
            BattleScriptBudgets budgets
    ) {
        private ParsedDefinition {
            root = root.deepCopy();
        }

        @Override
        public JsonObject root() {
            return this.root.deepCopy();
        }
    }

    private record Export<T>(
            T value,
            String sourceHash
    ) {
    }

    private record ExportIndex(
            Map<ResourceLocation, Export<ActorPrefabDefinition>> actorPrefabs,
            Map<ResourceLocation, Export<RuleSetDefinition>> ruleSets,
            Map<ResourceLocation, Export<PatternDefinition>> patterns,
            Map<ResourceLocation, Export<OutcomeDefinition>> outcomes
    ) {
        static ExportIndex create(Map<ResourceLocation, ParsedDefinition> parsedDefinitions) {
            Map<ResourceLocation, Export<ActorPrefabDefinition>> actorPrefabs = new HashMap<>();
            Map<ResourceLocation, Export<RuleSetDefinition>> ruleSets = new HashMap<>();
            Map<ResourceLocation, Export<PatternDefinition>> patterns = new HashMap<>();
            Map<ResourceLocation, Export<OutcomeDefinition>> outcomes = new HashMap<>();
            for (ParsedDefinition parsed : parsedDefinitions.values()) {
                for (ActorPrefabDefinition definition : parsed.actorPrefabs().values()) {
                    definition.globalId().ifPresent(id -> actorPrefabs.put(id, new Export<>(definition, parsed.rawHash())));
                }
                for (RuleSetDefinition definition : parsed.ruleSets().values()) {
                    definition.globalId().ifPresent(id -> ruleSets.put(id, new Export<>(definition, parsed.rawHash())));
                }
                for (PatternDefinition definition : parsed.patterns().values()) {
                    definition.globalId().ifPresent(id -> patterns.put(id, new Export<>(definition, parsed.rawHash())));
                }
                for (OutcomeDefinition definition : parsed.outcomes().values()) {
                    definition.globalId().ifPresent(id -> outcomes.put(id, new Export<>(definition, parsed.rawHash())));
                }
            }
            return new ExportIndex(Map.copyOf(actorPrefabs), Map.copyOf(ruleSets), Map.copyOf(patterns), Map.copyOf(outcomes));
        }
    }

    private record ImportedDefinitions(
            Map<String, ActorPrefabDefinition> actorPrefabs,
            Map<ResourceLocation, ActorPrefabDefinition> globalActorPrefabs,
            Map<String, RuleSetDefinition> ruleSets,
            Map<ResourceLocation, RuleSetDefinition> globalRuleSets,
            Map<String, PatternDefinition> patterns,
            Map<ResourceLocation, PatternDefinition> globalPatterns,
            Map<String, OutcomeDefinition> outcomes,
            Map<ResourceLocation, OutcomeDefinition> globalOutcomes,
            List<String> importHashes
    ) {
        private ImportedDefinitions {
            actorPrefabs = Map.copyOf(actorPrefabs);
            globalActorPrefabs = Map.copyOf(globalActorPrefabs);
            ruleSets = Map.copyOf(ruleSets);
            globalRuleSets = Map.copyOf(globalRuleSets);
            patterns = Map.copyOf(patterns);
            globalPatterns = Map.copyOf(globalPatterns);
            outcomes = Map.copyOf(outcomes);
            globalOutcomes = Map.copyOf(globalOutcomes);
            importHashes = List.copyOf(importHashes);
        }
    }

    private record Duration(
            OptionalDouble seconds,
            OptionalLong ticks
    ) {
    }

    public record CompileResult(
            Map<ResourceLocation, BattleDefinition> definitions,
            Map<ResourceLocation, RuntimeException> rejected
    ) {
        public CompileResult {
            definitions = Map.copyOf(definitions);
            rejected = Map.copyOf(rejected);
        }
    }
}
