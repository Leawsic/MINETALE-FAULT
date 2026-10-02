package cn.jehorstudio.minetale.battle.logic;

import java.util.Objects;
import java.util.UUID;

// 服务端同步客户端镜像时钟所需的最小锚点。
public record LogicTimelineAnchor(
        UUID battleId,
        long gameTime,
        long battleTick,
        int battleStepsPerGameTick,
        boolean paused
) {
    public LogicTimelineAnchor {
        Objects.requireNonNull(battleId, "battleId");
        if (gameTime < 0L) {
            throw new IllegalArgumentException("gameTime must be >= 0.");
        }
        if (battleTick < 0L) {
            throw new IllegalArgumentException("battleTick must be >= 0.");
        }
        if (battleStepsPerGameTick <= 0) {
            throw new IllegalArgumentException("battleStepsPerGameTick must be positive.");
        }
    }
}
