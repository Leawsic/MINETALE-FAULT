package cn.jehorstudio.minetale.battle.logic.action;

import java.util.Objects;

// sequence 保存全局入队顺序，使同一批次中的音效消费顺序可复现。
public record BattleSoundRequestOccurrence(
        BattleSoundRequest request,
        long requestedAtBattleTick,
        long sequence
) {
    public BattleSoundRequestOccurrence {
        Objects.requireNonNull(request, "request");
        if (requestedAtBattleTick < 0L) {
            throw new IllegalArgumentException("requestedAtBattleTick must be >= 0.");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be >= 0.");
        }
    }
}
