package cn.jehorstudio.minetale.battle.logic.states;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 只持有带来源的 rule overlay stack；一次性遭遇标记不属于规则状态。
public final class RuleStateCache {
    private final List<AppliedRule> stack = new ArrayList<>();

    public void pushRules(List<AppliedRule> rules, int maxRuleStackDepth) {
        Objects.requireNonNull(rules, "rules");
        if (this.stack.size() + rules.size() > maxRuleStackDepth) {
            throw new IllegalStateException("Rule overlay stack exceeded maxRuleStackDepth.");
        }
        this.stack.addAll(rules);
    }

    public int popSource(String source) {
        Objects.requireNonNull(source, "source");
        int before = this.stack.size();
        this.stack.removeIf(rule -> rule.source().equals(source));
        return before - this.stack.size();
    }

    public boolean popLastRuleset(String rulesetId) {
        Objects.requireNonNull(rulesetId, "rulesetId");
        for (int i = this.stack.size() - 1; i >= 0; i--) {
            AppliedRule rule = this.stack.get(i);
            if (rule.rulesetId().equals(rulesetId)) {
                String source = rule.source();
                popSource(source);
                return true;
            }
        }
        return false;
    }

    public List<AppliedRule> activeRules() {
        return List.copyOf(this.stack);
    }

    public RuleStateSnapshot snapshot() {
        return new RuleStateSnapshot(this.stack);
    }
}
