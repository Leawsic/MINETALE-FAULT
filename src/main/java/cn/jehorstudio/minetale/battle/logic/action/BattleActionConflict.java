package cn.jehorstudio.minetale.battle.logic.action;

import java.util.Objects;

public record BattleActionConflict(
        String key,
        BattleActionConflictPolicy policy
) {
    private static final BattleActionConflict NONE =
            new BattleActionConflict("", BattleActionConflictPolicy.ALLOW_PARALLEL);

    public BattleActionConflict {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(policy, "policy");
        if (policy != BattleActionConflictPolicy.ALLOW_PARALLEL && key.isBlank()) {
            throw new IllegalArgumentException("conflict key must not be blank.");
        }
    }

    public static BattleActionConflict none() {
        return NONE;
    }

    public static BattleActionConflict of(String key, BattleActionConflictPolicy policy) {
        return new BattleActionConflict(key, policy);
    }

    public boolean active() {
        return this.policy != BattleActionConflictPolicy.ALLOW_PARALLEL;
    }
}
