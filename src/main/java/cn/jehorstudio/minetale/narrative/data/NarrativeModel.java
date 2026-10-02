package cn.jehorstudio.minetale.narrative.data;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// 资源准备阶段构建的纯强类型叙事模型
public final class NarrativeModel {
    private NarrativeModel() {
    }

    public enum StoryScope {
        PLAYER,
        WORLD;

        public static StoryScope parse(String text) {
            return switch (text) {
                case "player" -> PLAYER;
                case "world" -> WORLD;
                default -> throw new NarrativeValidationException("Unknown story state scope: " + text);
            };
        }
    }

    public enum ValueType {
        BOOLEAN,
        INT,
        ID;

        public static ValueType parse(String text) {
            return switch (text) {
                case "boolean" -> BOOLEAN;
                case "int" -> INT;
                case "id" -> ID;
                default -> throw new NarrativeValidationException("Unknown narrative value type: " + text);
            };
        }
    }

    public sealed interface StoryValue permits BooleanValue, IntValue, IdValue {
        ValueType type();
    }

    public record BooleanValue(boolean value) implements StoryValue {
        @Override
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }

    public record IntValue(int value) implements StoryValue {
        @Override
        public ValueType type() {
            return ValueType.INT;
        }
    }

    public record IdValue(ResourceLocation value) implements StoryValue {
        public IdValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public ValueType type() {
            return ValueType.ID;
        }
    }

    public record StoryStateDefinition(
            ResourceLocation id,
            StoryScope scope,
            ValueType type,
            StoryValue defaultValue,
            int min,
            int max,
            Set<ResourceLocation> allowedIds
    ) {
        public StoryStateDefinition(
                ResourceLocation id,
                StoryScope scope,
                ValueType type,
                StoryValue defaultValue,
                int min,
                int max,
                Set<ResourceLocation> allowedIds
        ) {
            this.id = Objects.requireNonNull(id, "id");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.type = Objects.requireNonNull(type, "type");
            this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
            this.min = min;
            this.max = max;
            this.allowedIds = Set.copyOf(allowedIds);
            if (this.defaultValue.type() != this.type) {
                throw new NarrativeValidationException(this.id + " default value has the wrong type.");
            }
            if (this.min > this.max) {
                throw new NarrativeValidationException(this.id + " min must be <= max.");
            }
            validate(this.defaultValue);
        }

        public void validate(StoryValue value) {
            if (value.type() != this.type) {
                throw new NarrativeValidationException(this.id + " expects " + this.type + " but received " + value.type() + ".");
            }
            if (value instanceof IntValue integer && (integer.value() < this.min || integer.value() > this.max)) {
                throw new NarrativeValidationException(this.id + " value " + integer.value() + " is outside [" + this.min + ", " + this.max + "].");
            }
            if (value instanceof IdValue identifier && !this.allowedIds.contains(identifier.value())) {
                throw new NarrativeValidationException(this.id + " does not allow id value " + identifier.value() + ".");
            }
        }
    }

    public enum ConditionOperator {
        EQ,
        NE,
        LT,
        LE,
        GT,
        GE;

        public static ConditionOperator parse(String text) {
            return switch (text) {
                case "eq" -> EQ;
                case "ne" -> NE;
                case "lt" -> LT;
                case "le" -> LE;
                case "gt" -> GT;
                case "ge" -> GE;
                default -> throw new NarrativeValidationException("Unknown condition operator: " + text);
            };
        }
    }

    public record ConditionSource(boolean storyState, String key, ValueType type) {
        public ConditionSource {
            if (key == null || key.isBlank()) {
                throw new NarrativeValidationException("Condition source key must not be blank.");
            }
            Objects.requireNonNull(type, "type");
        }
    }

    public record DialogueCondition(
            ConditionSource source,
            ConditionOperator operator,
            StoryValue value
    ) {
        public DialogueCondition {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
            if (source.type() != value.type()) {
                throw new NarrativeValidationException("Condition " + source.key() + " compares incompatible types.");
            }
            switch (source.type()) {
                case BOOLEAN -> {
                    if (operator != ConditionOperator.EQ) {
                        throw new NarrativeValidationException("Boolean condition " + source.key() + " only supports eq.");
                    }
                }
                case ID -> {
                    if (operator != ConditionOperator.EQ && operator != ConditionOperator.NE) {
                        throw new NarrativeValidationException("Id condition " + source.key() + " only supports eq/ne.");
                    }
                }
                case INT -> {
                    // 当前 int 条件支持全部已定义运算符。
                }
            }
        }
    }

    public record DialogueRule(List<DialogueCondition> when, ResourceLocation run) {
        public DialogueRule {
            when = List.copyOf(when);
            Objects.requireNonNull(run, "run");
        }
    }

    public record DialogueProfile(
            ResourceLocation id,
            List<DialogueRule> rules,
            Map<ResourceLocation, List<DialogueRule>> rulesByPackage,
            Set<PackageOrder> specificity
    ) {
        public DialogueProfile {
            Objects.requireNonNull(id, "id");
            rules = List.copyOf(rules);
            rulesByPackage = Map.copyOf(rulesByPackage);
            specificity = Set.copyOf(specificity);
        }

        public boolean isMoreSpecific(ResourceLocation candidate, ResourceLocation other) {
            return this.specificity.contains(new PackageOrder(candidate, other));
        }
    }

    public record PackageOrder(ResourceLocation moreSpecific, ResourceLocation lessSpecific) {
        public PackageOrder {
            Objects.requireNonNull(moreSpecific, "moreSpecific");
            Objects.requireNonNull(lessSpecific, "lessSpecific");
        }
    }

    public enum DialoguePlacement {
        TOP,
        BOTTOM;

        public static DialoguePlacement parse(String text) {
            return switch (text) {
                case "top" -> TOP;
                case "bottom" -> BOTTOM;
                default -> throw new NarrativeValidationException("Unknown dialogue placement: " + text);
            };
        }
    }

    public enum PortraitMode {
        PORTRAIT,
        NO_PORTRAIT;

        public static PortraitMode parse(String text) {
            return switch (text) {
                case "portrait" -> PORTRAIT;
                case "no_portrait" -> NO_PORTRAIT;
                default -> throw new NarrativeValidationException("Unknown portrait mode: " + text);
            };
        }
    }

    public enum CameraPolicy {
        LOCKED,
        FREE;

        public static CameraPolicy parse(String text) {
            return switch (text) {
                case "locked" -> LOCKED;
                case "free" -> FREE;
                default -> throw new NarrativeValidationException("Unknown camera policy: " + text);
            };
        }
    }

    public enum AdvancePolicy {
        MANUAL,
        AUTO;

        public static AdvancePolicy parse(String text) {
            return switch (text) {
                case "manual" -> MANUAL;
                case "auto" -> AUTO;
                default -> throw new NarrativeValidationException("Unknown advance policy: " + text);
            };
        }
    }

    public enum RevealPolicy {
        SKIPPABLE,
        UNSKIPPABLE;

        public static RevealPolicy parse(String text) {
            return switch (text) {
                case "skippable" -> SKIPPABLE;
                case "unskippable" -> UNSKIPPABLE;
                default -> throw new NarrativeValidationException("Unknown reveal policy: " + text);
            };
        }
    }

    public enum BattleParticipantScope {
        SELF,
        NEARBY;

        public static BattleParticipantScope parse(String text) {
            return switch (text) {
                case "self" -> SELF;
                case "nearby" -> NEARBY;
                default -> throw new NarrativeValidationException("Unknown battle participant scope: " + text);
            };
        }
    }

    public sealed interface DialogueNode permits SequenceNode, PageNode, ChoiceNode, SetNode, AddNode, RunNode, BranchNode, RandomNode, ShuffleCycleNode, StartBattleNode {
        String id();
    }

    public record SequenceNode(String id, List<DialogueNode> children) implements DialogueNode {
        public SequenceNode {
            requireStepId(id);
            children = List.copyOf(children);
        }
    }

    public record PageNode(
            String id,
            List<String> lines,
            Optional<ResourceLocation> portrait,
            AdvancePolicy advance,
            RevealPolicy reveal,
            int autoDelayTicks,
            double charactersPerSecond,
            DialogueSoundSet sound
    ) implements DialogueNode {
        public PageNode {
            requireStepId(id);
            lines = List.copyOf(lines);
            if (lines.isEmpty() || lines.stream().anyMatch(String::isEmpty)) {
                throw new NarrativeValidationException("Dialogue page " + id + " must contain non-empty logical lines.");
            }
            portrait = Objects.requireNonNull(portrait, "portrait");
            Objects.requireNonNull(advance, "advance");
            Objects.requireNonNull(reveal, "reveal");
            sound = Objects.requireNonNull(sound, "sound");
            if (advance == AdvancePolicy.AUTO && autoDelayTicks < 0) {
                throw new NarrativeValidationException("Auto page " + id + " requires a non-negative delay.");
            }
            if (!Double.isFinite(charactersPerSecond) || charactersPerSecond <= 0.0D) {
                throw new NarrativeValidationException("Dialogue page " + id + " characters_per_second must be finite and > 0.");
            }
        }
    }

    public record ChoiceOption(
            String id,
            String literal,
            List<DialogueRule> results,
            BranchPlan plan
    ) {
        public ChoiceOption {
            requireStepId(id);
            if (literal == null || literal.isEmpty()) {
                throw new NarrativeValidationException("Dialogue option " + id + " must have non-empty literal text.");
            }
            results = List.copyOf(results);
            if (results.isEmpty() || results.stream().noneMatch(rule -> rule.when().isEmpty())) {
                throw new NarrativeValidationException("Dialogue option " + id + " requires an unconditional result.");
            }
            Objects.requireNonNull(plan, "plan");
        }
    }

    public record ChoiceNode(
            String id,
            List<ChoiceOption> options,
            Optional<ResourceLocation> portrait,
            RevealPolicy reveal,
            double charactersPerSecond,
            DialogueSoundSet sound
    ) implements DialogueNode {
        public ChoiceNode {
            requireStepId(id);
            options = List.copyOf(options);
            if (options.isEmpty()) {
                throw new NarrativeValidationException("Dialogue choice " + id + " must contain options.");
            }
            Set<String> optionIds = new java.util.HashSet<>();
            if (options.stream().anyMatch(option -> !optionIds.add(option.id()))) {
                throw new NarrativeValidationException("Dialogue choice " + id + " contains duplicate option ids.");
            }
            portrait = Objects.requireNonNull(portrait, "portrait");
            Objects.requireNonNull(reveal, "reveal");
            sound = Objects.requireNonNull(sound, "sound");
            if (!Double.isFinite(charactersPerSecond) || charactersPerSecond <= 0.0D) {
                throw new NarrativeValidationException("Dialogue choice " + id + " characters_per_second must be finite and > 0.");
            }
        }

        public ChoiceOption requireOption(String optionId) {
            return this.options.stream()
                    .filter(option -> option.id().equals(optionId))
                    .findFirst()
                    .orElseThrow(() -> new NarrativeValidationException(
                            "Dialogue choice " + this.id + " has no option " + optionId + "."
                    ));
        }
    }

    public record SoundMultiplierRange(double min, double max) {
        public static final SoundMultiplierRange ONE = new SoundMultiplierRange(1.0D, 1.0D);

        public SoundMultiplierRange {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0.0D || max < min) {
                throw new NarrativeValidationException("Sound multiplier range must be finite, >= 0 and ordered.");
            }
        }

        public double sample(RandomSource random) {
            Objects.requireNonNull(random, "random");
            return min == max ? min : min + random.nextDouble() * (max - min);
        }
    }

    public record DialogueSoundSet(
            List<DialogueSoundSpec> perPage,
            List<DialogueSoundSpec> perGrapheme
    ) {
        public static final DialogueSoundSet EMPTY = new DialogueSoundSet(List.of(), List.of());

        public DialogueSoundSet {
            perPage = List.copyOf(perPage);
            perGrapheme = List.copyOf(perGrapheme);
        }

        public Optional<DialogueSoundSpec> choosePerPage(RandomSource random) {
            return choose(this.perPage, random);
        }

        public Optional<DialogueSoundSpec> choosePerGrapheme(RandomSource random) {
            return choose(this.perGrapheme, random);
        }

        private static Optional<DialogueSoundSpec> choose(
                List<DialogueSoundSpec> candidates,
                RandomSource random
        ) {
            Objects.requireNonNull(random, "random");
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(candidates.get(random.nextInt(candidates.size())));
        }
    }

    public record DialogueSoundSpec(
            ResourceLocation event,
            SoundSource source,
            SoundMultiplierRange volumeMultiplier,
            SoundMultiplierRange pitchMultiplier
    ) {
        public DialogueSoundSpec {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(volumeMultiplier, "volumeMultiplier");
            Objects.requireNonNull(pitchMultiplier, "pitchMultiplier");
        }
    }

    public record SetNode(String id, ResourceLocation state, StoryValue value) implements DialogueNode {
        public SetNode {
            requireStepId(id);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(value, "value");
        }
    }

    public record AddNode(String id, ResourceLocation state, int value) implements DialogueNode {
        public AddNode {
            requireStepId(id);
            Objects.requireNonNull(state, "state");
        }
    }

    public record RunNode(String id, ResourceLocation dialoguePackage) implements DialogueNode {
        public RunNode {
            requireStepId(id);
            Objects.requireNonNull(dialoguePackage, "dialoguePackage");
        }
    }

    public record BranchNode(String id, List<DialogueRule> rules, BranchPlan plan) implements DialogueNode {
        public BranchNode {
            requireStepId(id);
            rules = List.copyOf(rules);
            Objects.requireNonNull(plan, "plan");
        }
    }

    public record BranchPlan(Map<ResourceLocation, List<DialogueRule>> rulesByPackage, Set<PackageOrder> specificity) {
        public BranchPlan {
            rulesByPackage = Map.copyOf(rulesByPackage);
            specificity = Set.copyOf(specificity);
        }

        public boolean isMoreSpecific(ResourceLocation candidate, ResourceLocation other) {
            return this.specificity.contains(new PackageOrder(candidate, other));
        }
    }

    public record RandomNode(String id, List<ResourceLocation> packages) implements DialogueNode {
        public RandomNode {
            requireStepId(id);
            packages = requirePackages(packages, id);
        }
    }

    public record ShuffleCycleNode(String id, List<ResourceLocation> packages) implements DialogueNode {
        public ShuffleCycleNode {
            requireStepId(id);
            packages = requirePackages(packages, id);
        }
    }

    public record StartBattleNode(
            String id,
            ResourceLocation battle,
            BattleParticipantScope participants,
            double range
    ) implements DialogueNode {
        public StartBattleNode {
            requireStepId(id);
            Objects.requireNonNull(battle, "battle");
            Objects.requireNonNull(participants, "participants");
            if (participants == BattleParticipantScope.SELF && range != 0.0D) {
                throw new NarrativeValidationException("Self battle participant scope cannot declare range.");
            }
            if (participants == BattleParticipantScope.NEARBY && (!Double.isFinite(range) || range <= 0.0D)) {
                throw new NarrativeValidationException("Nearby battle participant scope requires finite range > 0.");
            }
        }
    }

    public record DialoguePackage(
            ResourceLocation id,
            String locale,
            DialoguePlacement placement,
            PortraitMode portraitMode,
            CameraPolicy cameraPolicy,
            DialogueNode body,
            Map<String, DialogueNode> nodesById,
            String semanticSkeleton
    ) {
        public DialoguePackage {
            Objects.requireNonNull(id, "id");
            if (locale == null || locale.isBlank()) {
                throw new NarrativeValidationException(id + " locale must not be blank.");
            }
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(portraitMode, "portraitMode");
            Objects.requireNonNull(cameraPolicy, "cameraPolicy");
            Objects.requireNonNull(body, "body");
            nodesById = Map.copyOf(nodesById);
            Objects.requireNonNull(semanticSkeleton, "semanticSkeleton");
        }

        public DialogueNode requireNode(String stepId) {
            DialogueNode node = this.nodesById.get(stepId);
            if (node == null) {
                throw new NarrativeValidationException(this.id + " no longer contains step " + stepId + ".");
            }
            return node;
        }
    }

    private static void requireStepId(String id) {
        if (id == null || id.isBlank()) {
            throw new NarrativeValidationException("Dialogue step id must not be blank.");
        }
    }

    private static List<ResourceLocation> requirePackages(List<ResourceLocation> packages, String id) {
        List<ResourceLocation> copy = List.copyOf(packages);
        if (copy.isEmpty()) {
            throw new NarrativeValidationException("Dialogue pool " + id + " must not be empty.");
        }
        return copy;
    }
}
