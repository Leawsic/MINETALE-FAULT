package cn.jehorstudio.minetale.narrative.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Arrays;

import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.AddNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.AdvancePolicy;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BooleanValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BattleParticipantScope;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BranchNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BranchPlan;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.CameraPolicy;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ChoiceNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ChoiceOption;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ConditionOperator;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ConditionSource;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueCondition;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialoguePackage;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialoguePlacement;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueProfile;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueRule;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueSoundSet;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueSoundSpec;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IdValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IntValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.PageNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.PortraitMode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.RandomNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.RevealPolicy;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.RunNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.SequenceNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.SetNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ShuffleCycleNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryScope;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryStateDefinition;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StartBattleNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.SoundMultiplierRange;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ValueType;

public final class NarrativeCompiler {
    private static final double DEFAULT_CHARACTERS_PER_SECOND = 30.0D;

    private NarrativeCompiler() {
    }

    public static NarrativeSnapshot compile(
            Map<ResourceLocation, JsonElement> stateResources,
            Map<ResourceLocation, JsonElement> profileResources,
            Map<DialogueResourceId, JsonElement> dialogueResources,
            HolderLookup.Provider registryAccess
    ) {
        Map<ResourceLocation, StoryStateDefinition> states = parseStates(stateResources);
        ReadCatalog readCatalog = ReadCatalog.create(registryAccess);
        Map<String, ConditionLogic.Domain> universes = universes(states, readCatalog);

        Map<ResourceLocation, Map<String, DialoguePackage>> packages =
                parsePackages(dialogueResources, states, readCatalog, universes);
        Map<ResourceLocation, DialogueProfile> profiles =
                parseProfiles(profileResources, states, readCatalog, universes);
        validateReferences(profiles, packages);
        return new NarrativeSnapshot(states, profiles, packages);
    }

    private static Map<ResourceLocation, StoryStateDefinition> parseStates(
            Map<ResourceLocation, JsonElement> resources
    ) {
        Map<ResourceLocation, StoryStateDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation id = entry.getKey();
            String path = id.toString();
            JsonObject root = object(entry.getValue(), path);
            StoryScope scope = StoryScope.parse(string(root, "scope", path));
            ValueType type = ValueType.parse(string(root, "type", path));
            int min = type == ValueType.INT ? optionalInt(root, "min", Integer.MIN_VALUE, path) : Integer.MIN_VALUE;
            int max = type == ValueType.INT ? optionalInt(root, "max", Integer.MAX_VALUE, path) : Integer.MAX_VALUE;
            Set<ResourceLocation> allowed = new LinkedHashSet<>();
            if (type == ValueType.ID) {
                JsonArray values = array(root, "allowed", path);
                for (int i = 0; i < values.size(); i++) {
                    allowed.add(resourceLocation(string(values.get(i), path + ".allowed[" + i + "]")));
                }
                if (allowed.isEmpty()) {
                    throw error(path + ".allowed must not be empty.");
                }
            }
            JsonElement defaultElement = required(root, "default", path);
            StoryValue defaultValue = value(defaultElement, type, path + ".default");
            result.put(id, new StoryStateDefinition(id, scope, type, defaultValue, min, max, allowed));
        }
        return Map.copyOf(result);
    }

    private static Map<ResourceLocation, DialogueProfile> parseProfiles(
            Map<ResourceLocation, JsonElement> resources,
            Map<ResourceLocation, StoryStateDefinition> states,
            ReadCatalog reads,
            Map<String, ConditionLogic.Domain> universes
    ) {
        Map<ResourceLocation, DialogueProfile> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            String path = entry.getKey().toString();
            JsonObject root = object(entry.getValue(), path);
            List<DialogueRule> rules = rules(array(root, "rules", path), path + ".rules", states, reads);
            ConditionLogic.Analysis analysis = ConditionLogic.analyze(path, rules, universes);
            result.put(entry.getKey(), new DialogueProfile(
                    entry.getKey(),
                    rules,
                    analysis.rulesByPackage(),
                    analysis.specificity()
            ));
        }
        return Map.copyOf(result);
    }

    private static Map<ResourceLocation, Map<String, DialoguePackage>> parsePackages(
            Map<DialogueResourceId, JsonElement> resources,
            Map<ResourceLocation, StoryStateDefinition> states,
            ReadCatalog reads,
            Map<String, ConditionLogic.Domain> universes
    ) {
        Map<ResourceLocation, Map<String, DialoguePackage>> result = new LinkedHashMap<>();
        for (Map.Entry<DialogueResourceId, JsonElement> entry : resources.entrySet()) {
            DialogueResourceId resource = entry.getKey();
            String path = resource.id() + "[" + resource.locale() + "]";
            JsonObject root = object(entry.getValue(), path);
            DialoguePlacement placement = DialoguePlacement.parse(string(root, "placement", path));
            PortraitMode portraitMode = PortraitMode.parse(string(root, "portrait_mode", path));
            CameraPolicy camera = CameraPolicy.parse(optionalString(root, "camera").orElse("locked"));

            NodeParser parser = new NodeParser(resource.id(), portraitMode, states, reads, universes);
            DialogueNode body = parser.parse(required(root, "body", path), path + ".body");
            String skeleton = skeleton(placement, portraitMode, camera, body);
            DialoguePackage dialoguePackage = new DialoguePackage(
                    resource.id(),
                    NarrativeSnapshot.normalizeLocale(resource.locale()),
                    placement,
                    portraitMode,
                    camera,
                    body,
                    parser.nodes(),
                    skeleton
            );
            Map<String, DialoguePackage> variants =
                    result.computeIfAbsent(resource.id(), ignored -> new LinkedHashMap<>());
            DialoguePackage previous = variants.put(dialoguePackage.locale(), dialoguePackage);
            if (previous != null) {
                throw error("Duplicate dialogue locale variant " + resource + ".");
            }
        }

        for (Map.Entry<ResourceLocation, Map<String, DialoguePackage>> entry : result.entrySet()) {
            DialoguePackage fallback = entry.getValue().get(NarrativeSnapshot.FALLBACK_LOCALE);
            if (fallback == null) {
                throw error("Dialogue package " + entry.getKey() + " is missing required zh_cn variant.");
            }
            for (DialoguePackage variant : entry.getValue().values()) {
                if (!variant.semanticSkeleton().equals(fallback.semanticSkeleton())) {
                    throw error("Dialogue package " + entry.getKey() + " locale " + variant.locale()
                            + " differs from the zh_cn semantic skeleton.");
                }
            }
        }
        return immutableNested(result);
    }

    private static void validateReferences(
            Map<ResourceLocation, DialogueProfile> profiles,
            Map<ResourceLocation, Map<String, DialoguePackage>> packages
    ) {
        Set<ResourceLocation> available = packages.keySet();
        for (DialogueProfile profile : profiles.values()) {
            for (DialogueRule rule : profile.rules()) {
                requirePackage(rule.run(), available, profile.id().toString());
            }
        }
        for (Map<String, DialoguePackage> variants : packages.values()) {
            DialoguePackage packageVariant = variants.get(NarrativeSnapshot.FALLBACK_LOCALE);
            validateNodeReferences(packageVariant.body(), available, packageVariant.id().toString());
        }
    }

    private static void validateNodeReferences(
            DialogueNode node,
            Set<ResourceLocation> available,
            String owner
    ) {
        switch (node) {
            case SequenceNode sequence -> sequence.children()
                    .forEach(child -> validateNodeReferences(child, available, owner));
            case RunNode run -> requirePackage(run.dialoguePackage(), available, owner + "." + run.id());
            case BranchNode branch -> branch.rules()
                    .forEach(rule -> requirePackage(rule.run(), available, owner + "." + branch.id()));
            case ChoiceNode choice -> choice.options().forEach(option -> option.results()
                    .forEach(rule -> requirePackage(rule.run(), available, owner + "." + choice.id() + "." + option.id())));
            case RandomNode random -> random.packages()
                    .forEach(id -> requirePackage(id, available, owner + "." + random.id()));
            case ShuffleCycleNode shuffle -> shuffle.packages()
                    .forEach(id -> requirePackage(id, available, owner + "." + shuffle.id()));
            case PageNode ignored -> {
            }
            case SetNode ignored -> {
            }
            case AddNode ignored -> {
            }
            case StartBattleNode ignored -> {
            }
        }
    }

    private static void requirePackage(ResourceLocation id, Set<ResourceLocation> available, String owner) {
        if (!available.contains(id)) {
            throw error(owner + " references missing dialogue package " + id + ".");
        }
    }

    private static List<DialogueRule> rules(
            JsonArray array,
            String path,
            Map<ResourceLocation, StoryStateDefinition> states,
            ReadCatalog reads
    ) {
        List<DialogueRule> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String rulePath = path + "[" + i + "]";
            JsonObject rule = object(array.get(i), rulePath);
            JsonArray conditions = array(rule, "when", rulePath);
            List<DialogueCondition> parsedConditions = new ArrayList<>();
            for (int conditionIndex = 0; conditionIndex < conditions.size(); conditionIndex++) {
                parsedConditions.add(condition(
                        object(conditions.get(conditionIndex), rulePath + ".when[" + conditionIndex + "]"),
                        rulePath + ".when[" + conditionIndex + "]",
                        states,
                        reads
                ));
            }
            result.add(new DialogueRule(
                    parsedConditions,
                    resourceLocation(string(rule, "run", rulePath))
            ));
        }
        return List.copyOf(result);
    }

    private static DialogueCondition condition(
            JsonObject root,
            String path,
            Map<ResourceLocation, StoryStateDefinition> states,
            ReadCatalog reads
    ) {
        boolean hasState = root.has("state");
        boolean hasRead = root.has("read");
        if (hasState == hasRead) {
            throw error(path + " must contain exactly one of state/read.");
        }

        ConditionSource source;
        ValueType type;
        StoryStateDefinition stateDefinition = null;
        ReadKey readKey = null;
        if (hasState) {
            ResourceLocation stateId = resourceLocation(string(root, "state", path));
            stateDefinition = states.get(stateId);
            if (stateDefinition == null) {
                throw error(path + " references unknown story state " + stateId + ".");
            }
            type = stateDefinition.type();
            source = new ConditionSource(true, "story:" + stateId, type);
        } else {
            String readId = string(root, "read", path);
            readKey = reads.keys().get(readId);
            if (readKey == null) {
                throw error(path + " references unknown narrative read key " + readId + ".");
            }
            type = readKey.type();
            source = new ConditionSource(false, readId, type);
        }

        ConditionOperator operator = ConditionOperator.parse(string(root, "op", path));
        StoryValue expected = value(required(root, "value", path), type, path + ".value");
        if (stateDefinition != null && expected instanceof IdValue) {
            stateDefinition.validate(expected);
        }
        if (readKey != null && expected instanceof IdValue id && !readKey.allowedIds().contains(id.value())) {
            throw error(path + " references unknown " + readKey.registryName() + " id " + id.value() + ".");
        }
        return new DialogueCondition(source, operator, expected);
    }

    private static StoryValue value(JsonElement element, ValueType type, String path) {
        JsonPrimitive primitive = primitive(element, path);
        return switch (type) {
            case BOOLEAN -> {
                if (!primitive.isBoolean()) {
                    throw error(path + " must be a boolean.");
                }
                yield new BooleanValue(primitive.getAsBoolean());
            }
            case INT -> {
                if (!primitive.isNumber()) {
                    throw error(path + " must be an int.");
                }
                try {
                    yield new IntValue(primitive.getAsBigDecimal().intValueExact());
                } catch (ArithmeticException | NumberFormatException exception) {
                    throw new NarrativeValidationException(path + " must be a 32-bit int.", exception);
                }
            }
            case ID -> {
                if (!primitive.isString()) {
                    throw error(path + " must be a namespaced id string.");
                }
                yield new IdValue(resourceLocation(primitive.getAsString()));
            }
        };
    }

    private static Map<String, ConditionLogic.Domain> universes(
            Map<ResourceLocation, StoryStateDefinition> states,
            ReadCatalog reads
    ) {
        Map<String, ConditionLogic.Domain> result = new HashMap<>();
        for (StoryStateDefinition definition : states.values()) {
            result.put("story:" + definition.id(), domain(
                    definition.type(),
                    definition.min(),
                    definition.max(),
                    definition.allowedIds()
            ));
        }
        for (Map.Entry<String, ReadKey> entry : reads.keys().entrySet()) {
            ReadKey read = entry.getValue();
            result.put(entry.getKey(), domain(read.type(), read.min(), read.max(), read.allowedIds()));
        }
        return Map.copyOf(result);
    }

    private static ConditionLogic.Domain domain(
            ValueType type,
            int min,
            int max,
            Set<ResourceLocation> ids
    ) {
        return switch (type) {
            case BOOLEAN -> new ConditionLogic.BooleanDomain();
            case INT -> new ConditionLogic.IntDomain(min, max);
            case ID -> new ConditionLogic.IdDomain(ids);
        };
    }

    private static String skeleton(
            DialoguePlacement placement,
            PortraitMode portraitMode,
            CameraPolicy camera,
            DialogueNode node
    ) {
        StringBuilder builder = new StringBuilder()
                .append(placement).append('|')
                .append(portraitMode).append('|')
                .append(camera).append('|');
        appendSkeleton(builder, node);
        return builder.toString();
    }

    private static void appendSkeleton(StringBuilder builder, DialogueNode node) {
        builder.append(node.getClass().getSimpleName()).append('(').append(node.id()).append(':');
        switch (node) {
            case SequenceNode sequence -> sequence.children().forEach(child -> appendSkeleton(builder, child));
            case PageNode page -> builder.append(page.lines().size()).append(',')
                    .append(page.portrait().orElse(null)).append(',')
                    .append(page.advance()).append(',')
                    .append(page.reveal()).append(',')
                    .append(page.charactersPerSecond()).append(',')
                    .append(page.sound());
            case ChoiceNode choice -> {
                builder.append(choice.portrait().orElse(null)).append(',')
                        .append(choice.reveal()).append(',')
                        .append(choice.charactersPerSecond()).append(',')
                        .append(choice.sound());
                choice.options().forEach(option -> {
                    builder.append('[').append(option.id()).append(':');
                    option.results().forEach(rule -> {
                        builder.append('{');
                        rule.when().forEach(condition -> builder.append(condition).append(';'));
                        builder.append("->").append(rule.run()).append('}');
                    });
                    builder.append(']');
                });
            }
            case SetNode set -> builder.append(set.state()).append(',').append(set.value());
            case AddNode add -> builder.append(add.state()).append(',').append(add.value());
            case RunNode run -> builder.append(run.dialoguePackage());
            case BranchNode branch -> branch.rules().forEach(rule -> {
                builder.append('[');
                rule.when().forEach(condition -> builder.append(condition).append(';'));
                builder.append("->").append(rule.run()).append(']');
            });
            case RandomNode random -> builder.append(random.packages());
            case ShuffleCycleNode shuffle -> builder.append(shuffle.packages());
            case StartBattleNode start -> builder.append(start.battle()).append(',')
                    .append(start.participants()).append(',')
                    .append(start.range());
        }
        builder.append(')');
    }

    private static <K, K2, V> Map<K, Map<K2, V>> immutableNested(Map<K, Map<K2, V>> source) {
        Map<K, Map<K2, V>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, Map.copyOf(value)));
        return Map.copyOf(result);
    }

    private static JsonElement required(JsonObject object, String member, String path) {
        JsonElement element = object.get(member);
        if (element == null) {
            throw error(path + "." + member + " is required.");
        }
        return element;
    }

    private static JsonObject object(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw error(path + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String member, String path) {
        JsonElement element = required(object, member, path);
        if (!element.isJsonArray()) {
            throw error(path + "." + member + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    private static JsonPrimitive primitive(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive()) {
            throw error(path + " must be a primitive value.");
        }
        return element.getAsJsonPrimitive();
    }

    private static String string(JsonObject object, String member, String path) {
        return string(required(object, member, path), path + "." + member);
    }

    private static String string(JsonElement element, String path) {
        JsonPrimitive primitive = primitive(element, path);
        if (!primitive.isString() || primitive.getAsString().isBlank()) {
            throw error(path + " must be a non-blank string.");
        }
        return primitive.getAsString();
    }

    private static Optional<String> optionalString(JsonObject object, String member) {
        JsonElement element = object.get(member);
        return element == null ? Optional.empty() : Optional.of(string(element, member));
    }

    private static int optionalInt(JsonObject object, String member, int fallback, String path) {
        JsonElement element = object.get(member);
        if (element == null) {
            return fallback;
        }
        return integer(element, path + "." + member);
    }

    private static int requiredInt(JsonObject object, String member, String path) {
        return integer(required(object, member, path), path + "." + member);
    }

    private static int integer(JsonElement element, String path) {
        JsonPrimitive primitive = primitive(element, path);
        if (!primitive.isNumber()) {
            throw error(path + " must be an int.");
        }
        try {
            return primitive.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new NarrativeValidationException(path + " must be a 32-bit int.", exception);
        }
    }

    private static double optionalDouble(JsonObject object, String member, double fallback, String path) {
        JsonElement element = object.get(member);
        if (element == null) {
            return fallback;
        }
        JsonPrimitive primitive = primitive(element, path + "." + member);
        if (!primitive.isNumber()) {
            throw error(path + "." + member + " must be a number.");
        }
        double value = primitive.getAsDouble();
        if (!Double.isFinite(value)) {
            throw error(path + "." + member + " must be finite.");
        }
        return value;
    }

    private static ResourceLocation resourceLocation(String text) {
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || text.indexOf(':') < 1) {
            throw error("Expected a namespaced id, received: " + text);
        }
        return id;
    }

    private static NarrativeValidationException error(String message) {
        return new NarrativeValidationException(message);
    }

    public record DialogueResourceId(ResourceLocation id, String locale) {
        public DialogueResourceId {
            if (locale == null || locale.isBlank()) {
                throw error("Dialogue locale must not be blank.");
            }
        }
    }

    private record ReadKey(
            ValueType type,
            int min,
            int max,
            Set<ResourceLocation> allowedIds,
            String registryName
    ) {
        private ReadKey {
            allowedIds = Set.copyOf(allowedIds);
        }
    }

    private record ReadCatalog(Map<String, ReadKey> keys) {
        private static ReadCatalog create(HolderLookup.Provider access) {
            Set<ResourceLocation> biomes = registryIds(access, Registries.BIOME);
            Set<ResourceLocation> entityTypes = registryIds(access, Registries.ENTITY_TYPE);
            Set<ResourceLocation> dimensions = registryIds(access, Registries.DIMENSION);
            Map<String, ReadKey> keys = Map.of(
                    "interactor.biome", new ReadKey(ValueType.ID, 0, 0, biomes, "biome"),
                    "interactor.sneaking", new ReadKey(ValueType.BOOLEAN, 0, 0, Set.of(), "boolean"),
                    "target.biome", new ReadKey(ValueType.ID, 0, 0, biomes, "biome"),
                    "target.entity_type", new ReadKey(ValueType.ID, 0, 0, entityTypes, "entity type"),
                    "level.dimension", new ReadKey(ValueType.ID, 0, 0, dimensions, "dimension"),
                    "level.time_of_day", new ReadKey(ValueType.INT, 0, 23999, Set.of(), "int"),
                    "level.raining", new ReadKey(ValueType.BOOLEAN, 0, 0, Set.of(), "boolean"),
                    "level.thundering", new ReadKey(ValueType.BOOLEAN, 0, 0, Set.of(), "boolean")
            );
            return new ReadCatalog(keys);
        }

        private static <T> Set<ResourceLocation> registryIds(
                HolderLookup.Provider access,
                ResourceKey<? extends Registry<T>> key
        ) {
            return access.lookup(key)
                    .map(registry -> registry.listElementIds()
                            .map(ResourceKey::location)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                    .orElse(Set.of());
        }
    }

    private static final class NodeParser {
        private final ResourceLocation packageId;
        private final PortraitMode portraitMode;
        private final Map<ResourceLocation, StoryStateDefinition> states;
        private final ReadCatalog reads;
        private final Map<String, ConditionLogic.Domain> universes;
        private final Map<String, DialogueNode> nodes = new LinkedHashMap<>();

        private NodeParser(
                ResourceLocation packageId,
                PortraitMode portraitMode,
                Map<ResourceLocation, StoryStateDefinition> states,
                ReadCatalog reads,
                Map<String, ConditionLogic.Domain> universes
        ) {
            this.packageId = packageId;
            this.portraitMode = portraitMode;
            this.states = states;
            this.reads = reads;
            this.universes = universes;
        }

        private DialogueNode parse(JsonElement element, String path) {
            JsonObject root = object(element, path);
            String id = string(root, "id", path);
            String type = string(root, "type", path);
            DialogueNode node = switch (type) {
                case "sequence" -> parseSequence(id, root, path);
                case "page" -> parsePage(id, root, path);
                case "choice" -> parseChoice(id, root, path);
                case "set" -> parseSet(id, root, path);
                case "add" -> parseAdd(id, root, path);
                case "run" -> new RunNode(id, resourceLocation(string(root, "package", path)));
                case "branch" -> parseBranch(id, root, path);
                case "random" -> new RandomNode(id, packageList(root, path));
                case "shuffle_cycle" -> new ShuffleCycleNode(id, packageList(root, path));
                case "start_battle" -> parseStartBattle(id, root, path);
                default -> throw error(path + ".type has unknown dialogue node type " + type + ".");
            };
            if (this.nodes.putIfAbsent(id, node) != null) {
                throw error(this.packageId + " contains duplicate step id " + id + ".");
            }
            return node;
        }

        private SequenceNode parseSequence(String id, JsonObject root, String path) {
            JsonArray children = array(root, "children", path);
            List<DialogueNode> parsed = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                parsed.add(parse(children.get(i), path + ".children[" + i + "]"));
            }
            return new SequenceNode(id, parsed);
        }

        private PageNode parsePage(String id, JsonObject root, String path) {
            JsonArray lineElements = array(root, "lines", path);
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < lineElements.size(); i++) {
                JsonObject line = object(lineElements.get(i), path + ".lines[" + i + "]");
                lines.add(string(line, "literal", path + ".lines[" + i + "]"));
            }

            Optional<ResourceLocation> portrait = Optional.empty();
            if (root.has("portrait")) {
                portrait = Optional.of(resourceLocation(string(root, "portrait", path)));
            }
            if (this.portraitMode == PortraitMode.PORTRAIT && portrait.isEmpty()) {
                throw error(path + ".portrait is required by portrait_mode.");
            }
            if (this.portraitMode == PortraitMode.NO_PORTRAIT && portrait.isPresent()) {
                throw error(path + ".portrait is forbidden by no_portrait mode.");
            }

            AdvancePolicy advance = AdvancePolicy.parse(optionalString(root, "advance").orElse("manual"));
            RevealPolicy reveal = RevealPolicy.parse(optionalString(root, "reveal").orElse("skippable"));
            int delayTicks = -1;
            if (advance == AdvancePolicy.AUTO) {
                if (!root.has("auto_delay_seconds")) {
                    throw error(path + ".auto_delay_seconds is required for auto pages.");
                }
                double seconds = optionalDouble(root, "auto_delay_seconds", -1.0D, path);
                if (seconds < 0.0D) {
                    throw error(path + ".auto_delay_seconds must be >= 0.");
                }
                if (seconds > Integer.MAX_VALUE / 20.0D) {
                    throw error(path + ".auto_delay_seconds is too large.");
                }
                delayTicks = (int) Math.ceil(seconds * 20.0D);
            } else if (root.has("auto_delay_seconds")) {
                throw error(path + ".auto_delay_seconds is only valid for auto pages.");
            }
            double charactersPerSecond = optionalDouble(
                    root,
                    "characters_per_second",
                    DEFAULT_CHARACTERS_PER_SECOND,
                    path
            );
            return new PageNode(
                    id,
                    lines,
                    portrait,
                    advance,
                    reveal,
                    delayTicks,
                    charactersPerSecond,
                    parseSound(root, path)
            );
        }

        private ChoiceNode parseChoice(String id, JsonObject root, String path) {
            Optional<ResourceLocation> portrait = parsePortrait(root, path);
            RevealPolicy reveal = RevealPolicy.parse(optionalString(root, "reveal").orElse("skippable"));
            double charactersPerSecond = optionalDouble(
                    root,
                    "characters_per_second",
                    DEFAULT_CHARACTERS_PER_SECOND,
                    path
            );
            if (root.has("advance") || root.has("auto_delay_seconds")) {
                throw error(path + " choice nodes are always manual and cannot declare advance/auto_delay_seconds.");
            }

            JsonArray optionElements = array(root, "options", path);
            List<ChoiceOption> options = new ArrayList<>();
            for (int i = 0; i < optionElements.size(); i++) {
                String optionPath = path + ".options[" + i + "]";
                JsonObject option = object(optionElements.get(i), optionPath);
                String optionId = string(option, "id", optionPath);
                String literal = string(option, "literal", optionPath);
                List<DialogueRule> results = rules(
                        array(option, "results", optionPath),
                        optionPath + ".results",
                        this.states,
                        this.reads
                );
                ConditionLogic.Analysis analysis = ConditionLogic.analyze(
                        this.packageId + "." + id + "." + optionId,
                        results,
                        this.universes
                );
                options.add(new ChoiceOption(
                        optionId,
                        literal,
                        results,
                        new BranchPlan(analysis.rulesByPackage(), analysis.specificity())
                ));
            }
            return new ChoiceNode(id, options, portrait, reveal, charactersPerSecond, parseSound(root, path));
        }

        private DialogueSoundSet parseSound(JsonObject root, String path) {
            if (!root.has("sound")) {
                return DialogueSoundSet.EMPTY;
            }
            JsonObject sound = object(root.get("sound"), path + ".sound");
            rejectUnknownSoundMembers(sound, Set.of("per_page", "per_grapheme"), path + ".sound");
            List<DialogueSoundSpec> perPage = parseSoundCandidates(sound, "per_page", path + ".sound");
            List<DialogueSoundSpec> perGrapheme = parseSoundCandidates(
                    sound,
                    "per_grapheme",
                    path + ".sound"
            );
            if (perPage.isEmpty() && perGrapheme.isEmpty()) {
                throw error(path + ".sound must declare per_page, per_grapheme, or both.");
            }
            return new DialogueSoundSet(perPage, perGrapheme);
        }

        private List<DialogueSoundSpec> parseSoundCandidates(JsonObject sound, String member, String path) {
            if (!sound.has(member)) {
                return List.of();
            }
            JsonElement value = sound.get(member);
            if (!value.isJsonArray()) {
                return List.of(parseSoundSpec(object(value, path + "." + member), path + "." + member));
            }
            JsonArray candidates = value.getAsJsonArray();
            if (candidates.isEmpty()) {
                throw error(path + "." + member + " random candidate array must not be empty.");
            }
            if (candidates.size() > 64) {
                throw error(path + "." + member + " random candidate array exceeds 64 entries.");
            }
            List<DialogueSoundSpec> parsed = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                String candidatePath = path + "." + member + "[" + i + "]";
                parsed.add(parseSoundSpec(object(candidates.get(i), candidatePath), candidatePath));
            }
            return List.copyOf(parsed);
        }

        private DialogueSoundSpec parseSoundSpec(JsonObject sound, String path) {
            rejectUnknownSoundMembers(
                    sound,
                    Set.of("event", "source", "volume_multiplier", "pitch_multiplier"),
                    path
            );
            ResourceLocation event = resourceLocation(string(sound, "event", path));
            String sourceName = optionalString(sound, "source").orElse("voice");
            SoundSource source = Arrays.stream(SoundSource.values())
                    .filter(candidate -> candidate.getName().equals(sourceName))
                    .findFirst()
                    .orElseThrow(() -> error(path + ".source has unknown SoundSource " + sourceName + "."));
            return new DialogueSoundSpec(
                    event,
                    source,
                    parseSoundMultiplier(sound, "volume_multiplier", path),
                    parseSoundMultiplier(sound, "pitch_multiplier", path)
            );
        }

        private SoundMultiplierRange parseSoundMultiplier(JsonObject sound, String member, String path) {
            if (!sound.has(member)) {
                return SoundMultiplierRange.ONE;
            }
            JsonElement value = sound.get(member);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                double fixed = value.getAsDouble();
                return new SoundMultiplierRange(fixed, fixed);
            }
            JsonObject range = object(value, path + "." + member);
            rejectUnknownSoundMembers(range, Set.of("min", "max"), path + "." + member);
            double min = requiredFiniteDouble(range, "min", path + "." + member);
            double max = requiredFiniteDouble(range, "max", path + "." + member);
            return new SoundMultiplierRange(min, max);
        }

        private void rejectUnknownSoundMembers(JsonObject object, Set<String> allowed, String path) {
            for (String member : object.keySet()) {
                if (!allowed.contains(member)) {
                    throw error(path + " contains unknown member " + member + ".");
                }
            }
        }

        private double requiredFiniteDouble(JsonObject object, String member, String path) {
            if (!object.has(member)
                    || !object.get(member).isJsonPrimitive()
                    || !object.get(member).getAsJsonPrimitive().isNumber()) {
                throw error(path + "." + member + " must be a number.");
            }
            double value = object.get(member).getAsDouble();
            if (!Double.isFinite(value)) {
                throw error(path + "." + member + " must be finite.");
            }
            return value;
        }

        private Optional<ResourceLocation> parsePortrait(JsonObject root, String path) {
            Optional<ResourceLocation> portrait = Optional.empty();
            if (root.has("portrait")) {
                portrait = Optional.of(resourceLocation(string(root, "portrait", path)));
            }
            if (this.portraitMode == PortraitMode.PORTRAIT && portrait.isEmpty()) {
                throw error(path + ".portrait is required by portrait_mode.");
            }
            if (this.portraitMode == PortraitMode.NO_PORTRAIT && portrait.isPresent()) {
                throw error(path + ".portrait is forbidden by no_portrait mode.");
            }
            return portrait;
        }

        private SetNode parseSet(String id, JsonObject root, String path) {
            ResourceLocation stateId = resourceLocation(string(root, "state", path));
            StoryStateDefinition definition = requireState(stateId, path);
            StoryValue stateValue = value(required(root, "value", path), definition.type(), path + ".value");
            definition.validate(stateValue);
            return new SetNode(id, stateId, stateValue);
        }

        private AddNode parseAdd(String id, JsonObject root, String path) {
            ResourceLocation stateId = resourceLocation(string(root, "state", path));
            StoryStateDefinition definition = requireState(stateId, path);
            if (definition.type() != ValueType.INT) {
                throw error(path + " add requires an int story state.");
            }
            return new AddNode(id, stateId, requiredInt(root, "value", path));
        }

        private BranchNode parseBranch(String id, JsonObject root, String path) {
            List<DialogueRule> parsedRules = rules(array(root, "rules", path), path + ".rules", this.states, this.reads);
            ConditionLogic.Analysis analysis = ConditionLogic.analyze(
                    this.packageId + "." + id,
                    parsedRules,
                    this.universes
            );
            return new BranchNode(
                    id,
                    parsedRules,
                    new BranchPlan(analysis.rulesByPackage(), analysis.specificity())
            );
        }

        private StartBattleNode parseStartBattle(String id, JsonObject root, String path) {
            JsonObject participants = object(required(root, "participants", path), path + ".participants");
            BattleParticipantScope scope = BattleParticipantScope.parse(
                    string(participants, "scope", path + ".participants")
            );
            double range;
            if (scope == BattleParticipantScope.NEARBY) {
                if (!participants.has("range")) {
                    throw error(path + ".participants.range is required for nearby scope.");
                }
                range = optionalDouble(participants, "range", -1.0D, path + ".participants");
            } else {
                if (participants.has("range")) {
                    throw error(path + ".participants.range is only valid for nearby scope.");
                }
                range = 0.0D;
            }
            return new StartBattleNode(
                    id,
                    resourceLocation(string(root, "battle", path)),
                    scope,
                    range
            );
        }

        private List<ResourceLocation> packageList(JsonObject root, String path) {
            JsonArray packages = array(root, "packages", path);
            List<ResourceLocation> result = new ArrayList<>();
            for (int i = 0; i < packages.size(); i++) {
                result.add(resourceLocation(string(packages.get(i), path + ".packages[" + i + "]")));
            }
            return result;
        }

        private StoryStateDefinition requireState(ResourceLocation id, String path) {
            StoryStateDefinition definition = this.states.get(id);
            if (definition == null) {
                throw error(path + " references unknown story state " + id + ".");
            }
            return definition;
        }

        private Map<String, DialogueNode> nodes() {
            return Map.copyOf(this.nodes);
        }
    }
}
