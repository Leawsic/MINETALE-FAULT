package cn.jehorstudio.minetale.battle.logic.action;

import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleEventContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.NoContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class ActionStack {
    private final long stackId;
    private final int priority;
    private final long createdOrder;
    private final BattleEventContext eventContext;
    private final Deque<BattleAction> actions = new ArrayDeque<>();

    ActionStack(long stackId, int priority, long createdOrder) {
        this(stackId, priority, createdOrder, NoContext.INSTANCE);
    }

    ActionStack(long stackId, int priority, long createdOrder, BattleEventContext eventContext) {
        this.stackId = stackId;
        this.priority = priority;
        this.createdOrder = createdOrder;
        this.eventContext = Objects.requireNonNull(eventContext, "eventContext");
    }

    public long stackId() {
        return this.stackId;
    }

    public int priority() {
        return this.priority;
    }

    long createdOrder() {
        return this.createdOrder;
    }

    BattleEventContext eventContext() {
        return this.eventContext;
    }

    public void push(BattleAction action) {
        this.actions.addLast(Objects.requireNonNull(action, "action"));
    }

    BattleAction peek() {
        return this.actions.peekFirst();
    }

    void pop() {
        this.actions.removeFirst();
    }

    boolean isEmpty() {
        return this.actions.isEmpty();
    }

    boolean containsConflict(String conflictKey) {
        for (BattleAction action : this.actions) {
            BattleActionConflict conflict = action.conflict();
            if (conflict.active() && conflict.key().equals(conflictKey)) {
                return true;
            }
        }
        return false;
    }

    int removeConflictingActions(String conflictKey) {
        int before = this.actions.size();
        this.actions.removeIf(action -> {
            BattleActionConflict conflict = action.conflict();
            return conflict.active() && conflict.key().equals(conflictKey);
        });
        return before - this.actions.size();
    }
}
