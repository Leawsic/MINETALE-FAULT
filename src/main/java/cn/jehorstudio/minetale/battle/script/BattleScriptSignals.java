package cn.jehorstudio.minetale.battle.script;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class BattleScriptSignals {
    private final Map<String, Integer> currentTickSignals = new HashMap<>();
    private final Map<String, Long> lastReceivedTicks = new HashMap<>();
    private final Map<String, Long> lastReceivedSequences = new HashMap<>();
    private long currentBattleTick = -1L;
    private long currentSignalSequence;
    private int dispatchCount;

    public void emit(String signal, long battleTick, BattleScriptBudgets budgets) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(budgets, "budgets");
        if (signal.isBlank()) {
            throw new IllegalArgumentException("signal must not be blank.");
        }
        ensureTick(battleTick);
        this.dispatchCount++;
        if (this.dispatchCount > budgets.maxSignalDispatchPerTick()) {
            throw new IllegalStateException("BattleScript signal dispatch exceeded maxSignalDispatchPerTick.");
        }
        long sequence = ++this.currentSignalSequence;
        this.currentTickSignals.merge(signal, 1, Integer::sum);
        this.lastReceivedTicks.put(signal, battleTick);
        this.lastReceivedSequences.put(signal, sequence);
    }

    public boolean currentTickReceived(String signal, long battleTick) {
        ensureTick(battleTick);
        return this.currentTickSignals.getOrDefault(signal, 0) > 0;
    }

    public boolean received(String signal, long battleTick) {
        ensureTick(battleTick);
        return this.lastReceivedTicks.containsKey(signal);
    }

    public boolean receivedSince(String signal, long battleTick, long sinceTickInclusive) {
        ensureTick(battleTick);
        Long tick = this.lastReceivedTicks.get(signal);
        return tick != null && tick >= sinceTickInclusive;
    }

    public boolean receivedAfterSequence(String signal, long battleTick, long sequenceExclusive) {
        ensureTick(battleTick);
        Long sequence = this.lastReceivedSequences.get(signal);
        return sequence != null && sequence > sequenceExclusive;
    }

    public long currentSequence() {
        return this.currentSignalSequence;
    }

    public void endTick(long battleTick) {
        ensureTick(battleTick);
        this.currentTickSignals.clear();
        this.dispatchCount = 0;
    }

    private void ensureTick(long battleTick) {
        if (this.currentBattleTick != battleTick) {
            this.currentBattleTick = battleTick;
            this.currentTickSignals.clear();
            this.dispatchCount = 0;
        }
    }
}
