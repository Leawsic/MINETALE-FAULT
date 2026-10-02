package cn.jehorstudio.minetale.battle.logic.action;

import java.util.Objects;

// 请求时间在入队时固定。
public record BattleRenderRequestOccurrence(
        BattleRenderRequest request,
        long requestedAtBattleTick,
        long durationBattleTicks
) {
    public BattleRenderRequestOccurrence {
        Objects.requireNonNull(request, "request");
        if (requestedAtBattleTick < 0L) {
            throw new IllegalArgumentException("requestedAtBattleTick must be >= 0.");
        }
        if (durationBattleTicks < 0L) {
            throw new IllegalArgumentException("durationBattleTicks must be >= 0.");
        }
    }
}
