package cn.jehorstudio.minetale.narrative.data;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BooleanValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ConditionOperator;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ConditionSource;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueCondition;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueRule;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IdValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IntValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.PackageOrder;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ValueType;

// 分析有限条件语言：单条规则为合取，同包规则为析取，包含关系通过反例见证判定。
final class ConditionLogic {
    private ConditionLogic() {
    }

    static Analysis analyze(
            String owner,
            List<DialogueRule> rules,
            Map<String, Domain> universes
    ) {
        for (int i = 0; i < rules.size(); i++) {
            if (!region(rules.get(i).when(), universes).satisfiable()) {
                throw new NarrativeValidationException(owner + ".rules[" + i + "] is logically unsatisfiable.");
            }
        }

        Map<ResourceLocation, List<DialogueRule>> byPackage = new LinkedHashMap<>();
        for (DialogueRule rule : rules) {
            byPackage.computeIfAbsent(rule.run(), ignored -> new ArrayList<>()).add(rule);
        }
        byPackage.replaceAll((ignored, value) -> List.copyOf(value));

        Set<PackageOrder> order = new HashSet<>();
        List<ResourceLocation> packages = List.copyOf(byPackage.keySet());
        for (ResourceLocation candidate : packages) {
            for (ResourceLocation other : packages) {
                if (candidate.equals(other)) {
                    continue;
                }
                boolean candidateSubset = unionSubset(byPackage.get(candidate), byPackage.get(other), universes);
                boolean otherSubset = unionSubset(byPackage.get(other), byPackage.get(candidate), universes);
                if (candidateSubset && !otherSubset) {
                    order.add(new PackageOrder(candidate, other));
                }
            }
        }

        rejectPotentialAmbiguity(owner, byPackage, order, universes);
        return new Analysis(Map.copyOf(byPackage), Set.copyOf(order));
    }

    static boolean test(DialogueCondition condition, StoryValue actual) {
        if (actual.type() != condition.source().type()) {
            return false;
        }
        return switch (actual) {
            case BooleanValue bool -> condition.operator() == ConditionOperator.EQ
                    && bool.value() == ((BooleanValue) condition.value()).value();
            case IntValue integer -> compare(integer.value(), ((IntValue) condition.value()).value(), condition.operator());
            case IdValue identifier -> {
                boolean equal = identifier.value().equals(((IdValue) condition.value()).value());
                yield condition.operator() == ConditionOperator.EQ ? equal : !equal;
            }
        };
    }

    private static void rejectPotentialAmbiguity(
            String owner,
            Map<ResourceLocation, List<DialogueRule>> byPackage,
            Set<PackageOrder> order,
            Map<String, Domain> universes
    ) {
        List<ResourceLocation> packages = List.copyOf(byPackage.keySet());
        for (int leftIndex = 0; leftIndex < packages.size(); leftIndex++) {
            ResourceLocation left = packages.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < packages.size(); rightIndex++) {
                ResourceLocation right = packages.get(rightIndex);
                if (order.contains(new PackageOrder(left, right))
                        || order.contains(new PackageOrder(right, left))) {
                    continue;
                }

                List<DialogueRule> blockers = new ArrayList<>();
                for (ResourceLocation candidate : packages) {
                    if (candidate.equals(left) || candidate.equals(right)) {
                        continue;
                    }
                    if (order.contains(new PackageOrder(candidate, left))
                            && order.contains(new PackageOrder(candidate, right))) {
                        blockers.addAll(byPackage.get(candidate));
                    }
                }

                for (DialogueRule leftRule : byPackage.get(left)) {
                    for (DialogueRule rightRule : byPackage.get(right)) {
                        Region overlap = region(concat(leftRule.when(), rightRule.when()), universes);
                        if (!overlap.satisfiable()) {
                            continue;
                        }
                        if (!coveredByUnion(overlap, blockers, universes)) {
                            throw new NarrativeValidationException(
                                    owner + " has a potential ambiguous match between " + left + " and " + right
                                            + "; witness " + overlap.witness() + "."
                            );
                        }
                    }
                }
            }
        }
    }

    private static boolean unionSubset(
            List<DialogueRule> candidates,
            List<DialogueRule> covering,
            Map<String, Domain> universes
    ) {
        for (DialogueRule candidate : candidates) {
            Region candidateRegion = region(candidate.when(), universes);
            if (!coveredByUnion(candidateRegion, covering, universes)) {
                return false;
            }
        }
        return true;
    }

    private static boolean coveredByUnion(
            Region base,
            List<DialogueRule> covering,
            Map<String, Domain> universes
    ) {
        if (!base.satisfiable()) {
            return true;
        }
        return !findOutsideUnion(base, covering, 0, universes);
    }

    // 搜索满足 base 但不满足任一 covering 规则的反例。
    private static boolean findOutsideUnion(
            Region current,
            List<DialogueRule> covering,
            int ruleIndex,
            Map<String, Domain> universes
    ) {
        if (!current.satisfiable()) {
            return false;
        }
        if (ruleIndex >= covering.size()) {
            return true;
        }
        DialogueRule rule = covering.get(ruleIndex);
        if (rule.when().isEmpty()) {
            return false;
        }
        for (DialogueCondition condition : rule.when()) {
            Region next = current.copy();
            next.apply(negate(condition), universes);
            if (findOutsideUnion(next, covering, ruleIndex + 1, universes)) {
                return true;
            }
        }
        return false;
    }

    private static DialogueCondition negate(DialogueCondition condition) {
        ConditionOperator inverse = switch (condition.operator()) {
            case EQ -> ConditionOperator.NE;
            case NE -> ConditionOperator.EQ;
            case LT -> ConditionOperator.GE;
            case LE -> ConditionOperator.GT;
            case GT -> ConditionOperator.LE;
            case GE -> ConditionOperator.LT;
        };
        return new DialogueConditionUnchecked(condition.source(), inverse, condition.value()).asCondition();
    }

    // boolean ne 只存在于分析器内部，用来表示公共 eq 条件的逻辑否定。
    private record DialogueConditionUnchecked(
            ConditionSource source,
            ConditionOperator operator,
            StoryValue value
    ) {
        private DialogueCondition asCondition() {
            if (source.type() != ValueType.BOOLEAN || operator == ConditionOperator.EQ) {
                return new DialogueCondition(source, operator, value);
            }
            return new InternalBooleanNotCondition(source, value).condition();
        }
    }

    private record InternalBooleanNotCondition(ConditionSource source, StoryValue value) {
        private DialogueCondition condition() {
            // 公共 record 禁止 boolean ne，Region 通过私有来源标记保存该内部否定。
            return new DialogueCondition(
                    new ConditionSource(source.storyState(), "\u0000not:" + source.key(), ValueType.BOOLEAN),
                    ConditionOperator.EQ,
                    value
            );
        }
    }

    private static Region region(List<DialogueCondition> conditions, Map<String, Domain> universes) {
        Region region = new Region();
        for (DialogueCondition condition : conditions) {
            region.apply(condition, universes);
        }
        return region;
    }

    private static List<DialogueCondition> concat(
            Collection<DialogueCondition> first,
            Collection<DialogueCondition> second
    ) {
        List<DialogueCondition> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static boolean compare(int left, int right, ConditionOperator operator) {
        return switch (operator) {
            case EQ -> left == right;
            case NE -> left != right;
            case LT -> left < right;
            case LE -> left <= right;
            case GT -> left > right;
            case GE -> left >= right;
        };
    }

    record Analysis(Map<ResourceLocation, List<DialogueRule>> rulesByPackage, Set<PackageOrder> specificity) {
    }

    sealed interface Domain permits BooleanDomain, IntDomain, IdDomain {
        Domain copy();

        boolean satisfiable();

        StoryValue witness();

        void apply(ConditionOperator operator, StoryValue value);
    }

    static final class BooleanDomain implements Domain {
        private boolean allowFalse = true;
        private boolean allowTrue = true;

        @Override
        public Domain copy() {
            BooleanDomain copy = new BooleanDomain();
            copy.allowFalse = this.allowFalse;
            copy.allowTrue = this.allowTrue;
            return copy;
        }

        @Override
        public boolean satisfiable() {
            return this.allowFalse || this.allowTrue;
        }

        @Override
        public StoryValue witness() {
            return new BooleanValue(this.allowFalse ? false : true);
        }

        @Override
        public void apply(ConditionOperator operator, StoryValue value) {
            boolean expected = ((BooleanValue) value).value();
            if (operator == ConditionOperator.EQ) {
                this.allowFalse &= !expected;
                this.allowTrue &= expected;
            } else if (operator == ConditionOperator.NE) {
                this.allowFalse &= expected;
                this.allowTrue &= !expected;
            } else {
                this.allowFalse = false;
                this.allowTrue = false;
            }
        }
    }

    static final class IntDomain implements Domain {
        private int min;
        private int max;
        private final Set<Integer> excluded = new HashSet<>();

        IntDomain(int min, int max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public Domain copy() {
            IntDomain copy = new IntDomain(this.min, this.max);
            copy.excluded.addAll(this.excluded);
            return copy;
        }

        @Override
        public boolean satisfiable() {
            if (this.min > this.max) {
                return false;
            }
            long size = (long) this.max - this.min + 1L;
            long excludedInside = this.excluded.stream()
                    .filter(value -> value >= this.min && value <= this.max)
                    .count();
            return size > excludedInside;
        }

        @Override
        public StoryValue witness() {
            int value = this.min;
            while (this.excluded.contains(value) && value < this.max) {
                value++;
            }
            return new IntValue(value);
        }

        @Override
        public void apply(ConditionOperator operator, StoryValue value) {
            int expected = ((IntValue) value).value();
            switch (operator) {
                case EQ -> {
                    this.min = Math.max(this.min, expected);
                    this.max = Math.min(this.max, expected);
                }
                case NE -> this.excluded.add(expected);
                case LT -> this.max = Math.min(this.max, expected == Integer.MIN_VALUE ? Integer.MIN_VALUE : expected - 1);
                case LE -> this.max = Math.min(this.max, expected);
                case GT -> this.min = Math.max(this.min, expected == Integer.MAX_VALUE ? Integer.MAX_VALUE : expected + 1);
                case GE -> this.min = Math.max(this.min, expected);
            }
            if (operator == ConditionOperator.LT && expected == Integer.MIN_VALUE
                    || operator == ConditionOperator.GT && expected == Integer.MAX_VALUE) {
                this.min = 1;
                this.max = 0;
            }
        }
    }

    static final class IdDomain implements Domain {
        private final Set<ResourceLocation> allowed;

        IdDomain(Collection<ResourceLocation> allowed) {
            this.allowed = new HashSet<>(allowed);
        }

        @Override
        public Domain copy() {
            return new IdDomain(this.allowed);
        }

        @Override
        public boolean satisfiable() {
            return !this.allowed.isEmpty();
        }

        @Override
        public StoryValue witness() {
            return new IdValue(this.allowed.iterator().next());
        }

        @Override
        public void apply(ConditionOperator operator, StoryValue value) {
            ResourceLocation expected = ((IdValue) value).value();
            if (operator == ConditionOperator.EQ) {
                this.allowed.retainAll(Set.of(expected));
            } else if (operator == ConditionOperator.NE) {
                this.allowed.remove(expected);
            } else {
                this.allowed.clear();
            }
        }
    }

    private static final class Region {
        private final Map<String, Domain> constrained = new HashMap<>();
        private boolean satisfiable = true;

        private Region copy() {
            Region copy = new Region();
            copy.satisfiable = this.satisfiable;
            this.constrained.forEach((key, value) -> copy.constrained.put(key, value.copy()));
            return copy;
        }

        private void apply(DialogueCondition condition, Map<String, Domain> universes) {
            boolean internalNot = condition.source().key().startsWith("\u0000not:");
            String key = internalNot ? condition.source().key().substring("\u0000not:".length()) : condition.source().key();
            Domain universe = universes.get(key);
            if (universe == null) {
                this.satisfiable = false;
                return;
            }
            Domain domain = this.constrained.computeIfAbsent(key, ignored -> universe.copy());
            ConditionOperator operator = internalNot ? ConditionOperator.NE : condition.operator();
            domain.apply(operator, condition.value());
            this.satisfiable &= domain.satisfiable();
        }

        private boolean satisfiable() {
            return this.satisfiable && this.constrained.values().stream().allMatch(Domain::satisfiable);
        }

        private Map<String, StoryValue> witness() {
            Map<String, StoryValue> result = new LinkedHashMap<>();
            this.constrained.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue().witness()));
            return result;
        }
    }
}
