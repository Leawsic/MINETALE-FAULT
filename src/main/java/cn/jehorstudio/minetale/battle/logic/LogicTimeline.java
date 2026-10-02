package cn.jehorstudio.minetale.battle.logic;

import java.util.Objects;
import java.util.UUID;

// 每场 Battle 独占的离散时钟；客户端只通过 anchor 追赶，不直接回拨 battleTick。
public final class LogicTimeline {
    private final UUID battleId;
    private final int battleStepsPerGameTick;
    private final int maxBattleStepsPerGameTick;

    private long battleTick;
    private long catchUpTargetBattleTick;
    private boolean paused;

    public LogicTimeline(UUID battleId) {
        this(
                battleId,
                0L,
                LogicTimelineSettings.DEFAULT_BATTLE_STEPS_PER_GAME_TICK,
                LogicTimelineSettings.DEFAULT_MAX_BATTLE_STEPS_PER_GAME_TICK
        );
    }

    public LogicTimeline(
            UUID battleId,
            long initialBattleTick,
            int battleStepsPerGameTick,
            int maxBattleStepsPerGameTick
    ) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        if (initialBattleTick < 0L) {
            throw new IllegalArgumentException("initialBattleTick must be >= 0.");
        }

        LogicTimelineSettings.validate(battleStepsPerGameTick, maxBattleStepsPerGameTick);

        this.battleTick = initialBattleTick;
        this.catchUpTargetBattleTick = initialBattleTick;
        this.battleStepsPerGameTick = battleStepsPerGameTick;
        this.maxBattleStepsPerGameTick = maxBattleStepsPerGameTick;
    }

    public UUID battleId() {
        return this.battleId;
    }

    public long battleTick() {
        return this.battleTick;
    }

    public int battleStepsPerGameTick() {
        return this.battleStepsPerGameTick;
    }

    public int maxBattleStepsPerGameTick() {
        return this.maxBattleStepsPerGameTick;
    }

    public int battleTicksPerSecond() {
        return this.battleStepsPerGameTick * 20;
    }

    public double secondsPerBattleStep() {
        return 1.0 / this.battleTicksPerSecond();
    }

    public long secondsToBattleTicks(double seconds) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("seconds must be >= 0.");
        }
        return Math.round(seconds * this.battleTicksPerSecond());
    }

    //package-private
    boolean advanceOneStep() {
        if (this.paused) {
            return false;
        }

        this.battleTick++;
        return true;
    }

    public int advanceSteps(int steps) {
        if (steps < 0) {
            throw new IllegalArgumentException("steps must be >= 0.");
        }
        if (this.paused || steps == 0) {
            return 0;
        }

        this.battleTick += steps;
        return steps;
    }

    //package-private
    int advanceGameTick() {
        return advanceSteps(this.battleStepsPerGameTick);
    }

    int advanceGameTickWithCatchUp() {
        int steps = stepsForNextGameTickWithCatchUp();
        return advanceSteps(steps);
    }

    public int stepsForNextGameTickWithCatchUp() {
        if (this.paused) {
            return 0;
        }

        long drift = this.catchUpTargetBattleTick - this.battleTick;
        if (drift <= 0L) {
            return this.battleStepsPerGameTick;
        }

        long desiredSteps = this.battleStepsPerGameTick + drift;
        return (int) Math.min(desiredSteps, this.maxBattleStepsPerGameTick);
    }

    public LogicTimelineAnchor createAnchor(long gameTime) {
        return new LogicTimelineAnchor(
                this.battleId,
                gameTime,
                this.battleTick,
                this.battleStepsPerGameTick,
                this.paused
        );
    }

    public void applyAnchorForCatchUp(LogicTimelineAnchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (!this.battleId.equals(anchor.battleId())) {
            throw new IllegalArgumentException("anchor battleId does not match this timeline.");
        }
        if (anchor.battleStepsPerGameTick() != this.battleStepsPerGameTick) {
            throw new IllegalArgumentException("anchor battleStepsPerGameTick does not match this timeline.");
        }

        this.paused = anchor.paused();
        if (anchor.battleTick() > this.catchUpTargetBattleTick) {
            this.catchUpTargetBattleTick = anchor.battleTick();
        }
    }
}
