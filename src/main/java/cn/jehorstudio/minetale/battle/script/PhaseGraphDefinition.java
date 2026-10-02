package cn.jehorstudio.minetale.battle.script;

import java.util.Map;
import java.util.Objects;

public record PhaseGraphDefinition(
        String entryPhaseId,
        Map<String, PhaseDefinition> phases
) {
    public PhaseGraphDefinition {
        Objects.requireNonNull(entryPhaseId, "entryPhaseId");
        phases = Map.copyOf(Objects.requireNonNull(phases, "phases"));
        if (!phases.containsKey(entryPhaseId)) {
            throw new IllegalArgumentException("Entry phase does not exist: " + entryPhaseId);
        }
    }

    public PhaseDefinition requirePhase(String phaseId) {
        PhaseDefinition phase = this.phases.get(phaseId);
        if (phase == null) {
            throw new IllegalArgumentException("Unknown phase: " + phaseId);
        }
        return phase;
    }
}
