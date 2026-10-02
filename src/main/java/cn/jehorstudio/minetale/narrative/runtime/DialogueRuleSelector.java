package cn.jehorstudio.minetale.narrative.runtime;

import cn.jehorstudio.minetale.narrative.data.NarrativeValidationException;
import cn.jehorstudio.minetale.narrative.data.NarrativeModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueCondition;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueProfile;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueRule;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryValue;

final class DialogueRuleSelector {
    private DialogueRuleSelector() {
    }

    static Optional<ResourceLocation> select(
            DialogueProfile profile,
            ServerPlayer interactor,
            DialogueTargetContext target,
            ServerLevel level
    ) {
        return select(
                profile.id().toString(),
                profile.rulesByPackage(),
                profile::isMoreSpecific,
                interactor,
                target,
                level
        );
    }

    static Optional<ResourceLocation> select(
            String owner,
            NarrativeModel.BranchPlan plan,
            ServerPlayer interactor,
            DialogueTargetContext target,
            ServerLevel level
    ) {
        return select(
                owner,
                plan.rulesByPackage(),
                plan::isMoreSpecific,
                interactor,
                target,
                level
        );
    }

    private static Optional<ResourceLocation> select(
            String owner,
            Map<ResourceLocation, List<DialogueRule>> byPackage,
            BiPredicate<ResourceLocation, ResourceLocation> moreSpecific,
            ServerPlayer interactor,
            DialogueTargetContext target,
            ServerLevel level
    ) {
        Set<ResourceLocation> matched = new LinkedHashSet<>();
        for (Map.Entry<ResourceLocation, List<DialogueRule>> entry : byPackage.entrySet()) {
            if (entry.getValue().stream().anyMatch(rule -> matches(rule, interactor, target, level))) {
                matched.add(entry.getKey());
            }
        }
        if (matched.isEmpty()) {
            return Optional.empty();
        }

        List<ResourceLocation> maximal = new ArrayList<>();
        for (ResourceLocation candidate : matched) {
            boolean shadowed = matched.stream()
                    .anyMatch(other -> !other.equals(candidate) && moreSpecific.test(other, candidate));
            if (!shadowed) {
                maximal.add(candidate);
            }
        }
        if (maximal.size() != 1) {
            throw new NarrativeValidationException(owner + " produced runtime ambiguity: " + maximal + ".");
        }
        return Optional.of(maximal.getFirst());
    }

    private static boolean matches(
            DialogueRule rule,
            ServerPlayer interactor,
            DialogueTargetContext target,
            ServerLevel level
    ) {
        for (DialogueCondition condition : rule.when()) {
            StoryValue actual = NarrativeFactReader.read(condition.source(), interactor, target, level);
            if (!test(condition, actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean test(DialogueCondition condition, StoryValue actual) {
        if (actual.type() != condition.value().type()) {
            return false;
        }
        return switch (actual) {
            case NarrativeModel.BooleanValue value ->
                    value.value() == ((NarrativeModel.BooleanValue) condition.value()).value();
            case NarrativeModel.IntValue value -> {
                int expected = ((NarrativeModel.IntValue) condition.value()).value();
                yield switch (condition.operator()) {
                    case EQ -> value.value() == expected;
                    case NE -> value.value() != expected;
                    case LT -> value.value() < expected;
                    case LE -> value.value() <= expected;
                    case GT -> value.value() > expected;
                    case GE -> value.value() >= expected;
                };
            }
            case NarrativeModel.IdValue value -> {
                boolean equal = value.value().equals(((NarrativeModel.IdValue) condition.value()).value());
                yield condition.operator() == NarrativeModel.ConditionOperator.EQ ? equal : !equal;
            }
        };
    }
}
