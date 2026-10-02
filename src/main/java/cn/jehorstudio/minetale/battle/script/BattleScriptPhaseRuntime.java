package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.battle.logic.action.ActionStack;
import cn.jehorstudio.minetale.battle.logic.action.BattleActionResult;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

final class BattleScriptPhaseRuntime {
    private final BattleScriptRuntime runtime;
    private final BattleScriptActionFactory actions;

    private PhaseDefinition activePhase;
    private Mode mode = Mode.INACTIVE;
    private long entryStackId = -1L;
    private long exitStackId = -1L;
    private long tickStackId = -1L;
    private Optional<String> requestedPhaseTarget = Optional.empty();
    private boolean requestedPhaseEnd;
    private boolean requestedBattleEnd;

    BattleScriptPhaseRuntime(BattleScriptRuntime runtime, BattleScriptActionFactory actions) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    void initialize() {
        enterPhase(this.runtime.definition().compiled().phaseGraph().entryPhaseId());
    }

    void advance() {
        if (this.mode == Mode.INACTIVE || this.activePhase == null || this.runtime.instance().ended()) {
            return;
        }
        observeCompletedStacks();
        if (this.mode == Mode.EXITING) {
            finishExitIfReady();
            return;
        }
        if (this.mode == Mode.ENTERING) {
            return;
        }
        if (this.requestedBattleEnd) {
            this.runtime.instance().endBattle();
            return;
        }
        if (this.requestedPhaseEnd) {
            beginExit(this.requestedPhaseTarget);
            return;
        }
        scheduleOnTickIfReady();
        evaluateTransitions();
    }

    void requestPhaseEnd(Optional<String> targetPhase) {
        this.requestedPhaseEnd = true;
        this.requestedPhaseTarget = Objects.requireNonNull(targetPhase, "targetPhase");
    }

    void requestBattleEnd() {
        this.requestedBattleEnd = true;
    }

    boolean entryStackCompleted() {
        return this.mode == Mode.RUNNING && (this.entryStackId < 0L
                || this.runtime.instance().stateCache().phase().entryStackCompleted(this.entryStackId));
    }

    boolean activePhaseDurationElapsed() {
        if (this.activePhase == null) {
            return false;
        }
        OptionalLong durationTicks = activePhaseDurationTicks();
        return durationTicks.isPresent() && activePhaseElapsedTicks() >= durationTicks.getAsLong();
    }

    long activePhaseElapsedTicks() {
        return this.runtime.instance().stateCache().phase().elapsedTicks(this.runtime.instance().timeline().battleTick());
    }

    double activePhaseElapsedSeconds() {
        return activePhaseElapsedTicks() / (double) this.runtime.instance().timeline().battleTicksPerSecond();
    }

    String activePhaseId() {
        return this.activePhase == null ? "" : this.activePhase.id();
    }

    private void observeCompletedStacks() {
        if (this.entryStackId >= 0L && !this.runtime.instance().actionStacks().hasStack(this.entryStackId)) {
            this.runtime.instance().stateCache().phase().markEntryStackCompleted(this.entryStackId);
            if (this.mode == Mode.ENTERING) {
                this.mode = Mode.RUNNING;
            }
        }
        if (this.tickStackId >= 0L && !this.runtime.instance().actionStacks().hasStack(this.tickStackId)) {
            this.tickStackId = -1L;
        }
    }

    private void scheduleOnTickIfReady() {
        if (this.tickStackId >= 0L || this.activePhase.onTickActions().isEmpty()) {
            return;
        }
        this.tickStackId = schedule(this.activePhase.onTickActions());
    }

    private void evaluateTransitions() {
        for (TransitionDefinition transition : this.activePhase.transitions()) {
            if (this.runtime.conditions().evaluate(transition.condition())) {
                if (transition.endsBattle()) {
                    this.runtime.instance().endBattle();
                } else {
                    beginExit(transition.targetPhase());
                }
                return;
            }
        }
    }

    private void enterPhase(String phaseId) {
        this.activePhase = this.runtime.definition().compiled().phaseGraph().requirePhase(phaseId);
        this.mode = Mode.ENTERING;
        this.entryStackId = -1L;
        this.exitStackId = -1L;
        this.tickStackId = -1L;
        this.requestedPhaseEnd = false;
        this.requestedPhaseTarget = Optional.empty();
        this.requestedBattleEnd = false;

        long battleTick = this.runtime.instance().timeline().battleTick();
        this.runtime.instance().stateCache().phase().enterPhase(this.activePhase.id(), battleTick);
        pushPhaseRules(this.activePhase);
        if (this.activePhase.onEnterActions().isEmpty()) {
            this.mode = Mode.RUNNING;
        } else {
            this.entryStackId = schedule(this.activePhase.onEnterActions());
        }
    }

    private void beginExit(Optional<String> targetPhase) {
        if (this.mode == Mode.EXITING) {
            return;
        }
        cancelPhaseTickStack();
        if (targetPhase.isEmpty()) {
            this.runtime.instance().endBattle();
            return;
        }
        this.requestedPhaseEnd = false;
        this.requestedPhaseTarget = targetPhase;
        this.mode = Mode.EXITING;
        if (this.activePhase.onExitActions().isEmpty()) {
            finishExitIfReady();
        } else {
            this.exitStackId = schedule(this.activePhase.onExitActions());
        }
    }

    private void finishExitIfReady() {
        if (this.exitStackId >= 0L && this.runtime.instance().actionStacks().hasStack(this.exitStackId)) {
            return;
        }
        String oldPhaseId = this.activePhase.id();
        this.runtime.instance().stateCache().rules().popSource(phaseRuleSource(oldPhaseId));
        String target = this.requestedPhaseTarget.orElseThrow();
        enterPhase(target);
    }

    private void cancelPhaseTickStack() {
        if (this.tickStackId >= 0L) {
            this.runtime.instance().actionStacks().cancelStack(this.tickStackId);
            this.tickStackId = -1L;
        }
    }

    private void pushPhaseRules(PhaseDefinition phase) {
        for (String rulesetRef : phase.ruleSetRefs()) {
            this.runtime.pushRuleset(phaseRuleSource(phase.id()), rulesetRef);
        }
    }

    private long schedule(java.util.List<com.google.gson.JsonObject> actionData) {
        ActionStack stack = this.runtime.instance().actionStacks().createStack(0);
        for (com.google.gson.JsonObject action : actionData) {
            stack.push(this.actions.create(action));
        }
        return stack.stackId();
    }

    private OptionalLong activePhaseDurationTicks() {
        if (this.activePhase.durationTicks().isPresent()) {
            return this.activePhase.durationTicks();
        }
        if (this.activePhase.durationSeconds().isPresent()) {
            return OptionalLong.of(this.runtime.instance().timeline().secondsToBattleTicks(this.activePhase.durationSeconds().getAsDouble()));
        }
        return OptionalLong.empty();
    }

    private static String phaseRuleSource(String phaseId) {
        return "phase:" + phaseId;
    }

    private enum Mode {
        INACTIVE,
        ENTERING,
        RUNNING,
        EXITING
    }
}
