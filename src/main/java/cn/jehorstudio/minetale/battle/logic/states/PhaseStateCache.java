package cn.jehorstudio.minetale.battle.logic.states;

// 只持有 phase 生命周期及 entry stack 完成状态；Action 自身进度由 Action Stack 持有。
public final class PhaseStateCache {
    private String currentPhaseId;
    private long enteredAtBattleTick;
    private long completedEntryStackId = -1L;

    public void enterPhase(String phaseId, long battleTick) {
        if (phaseId == null || phaseId.isBlank()) {
            throw new IllegalArgumentException("phaseId must not be blank.");
        }
        if (battleTick < 0L) {
            throw new IllegalArgumentException("battleTick must be >= 0.");
        }
        this.currentPhaseId = phaseId;
        this.enteredAtBattleTick = battleTick;
        this.completedEntryStackId = -1L;
    }

    public boolean initialized() {
        return this.currentPhaseId != null;
    }

    public String currentPhaseId() {
        if (!initialized()) {
            throw new IllegalStateException("PhaseStateCache has not been initialized.");
        }
        return this.currentPhaseId;
    }

    public long enteredAtBattleTick() {
        return this.enteredAtBattleTick;
    }

    public long elapsedTicks(long battleTick) {
        return Math.max(0L, battleTick - this.enteredAtBattleTick);
    }

    public void markEntryStackCompleted(long stackId) {
        this.completedEntryStackId = stackId;
    }

    public boolean entryStackCompleted(long stackId) {
        return stackId >= 0L && this.completedEntryStackId == stackId;
    }

    public PhaseStateSnapshot snapshot() {
        return new PhaseStateSnapshot(this.currentPhaseId, this.enteredAtBattleTick, this.completedEntryStackId);
    }
}
