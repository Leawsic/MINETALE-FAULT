package cn.jehorstudio.minetale.battle.logic.states;

import java.util.Optional;

public record PhaseStateSnapshot(
        String currentPhaseId,
        long enteredAtBattleTick,
        long completedEntryStackId
) {
    public Optional<String> currentPhase() {
        return Optional.ofNullable(this.currentPhaseId);
    }
}
