package cn.jehorstudio.minetale.battle.logic.states;

import java.util.List;
import java.util.Objects;

public record RuleStateSnapshot(
        List<AppliedRule> activeRules
) {
    public RuleStateSnapshot {
        activeRules = List.copyOf(Objects.requireNonNull(activeRules, "activeRules"));
    }
}
