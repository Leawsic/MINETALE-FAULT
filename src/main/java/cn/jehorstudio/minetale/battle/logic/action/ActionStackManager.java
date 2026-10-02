package cn.jehorstudio.minetale.battle.logic.action;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.action.actions.WaitForActionConflictAction;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleActionContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleEventContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.NoContext;
import cn.jehorstudio.minetale.battle.logic.BattleInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ActionStackManager {
    public static final int DEFAULT_MAX_ACTIONS_PER_STACK_PER_TICK = 256;
    public static final int DEFAULT_MAX_ACTIONS_PER_BATTLE_TICK = 1_024;

    private final BattleInstance instance;
    private final List<ActionStack> stacks = new ArrayList<>();
    private long nextStackId = 1L;
    private long nextCreatedOrder = 1L;
    private int maxActionsPerStackPerTick = DEFAULT_MAX_ACTIONS_PER_STACK_PER_TICK;
    private int maxActionsPerBattleTick = DEFAULT_MAX_ACTIONS_PER_BATTLE_TICK;

    public ActionStackManager(BattleInstance instance) {
        this.instance = Objects.requireNonNull(instance, "instance");
    }

    public void setBudgetLimits(int maxActionsPerStackPerTick, int maxActionsPerBattleTick) {
        if (maxActionsPerStackPerTick <= 0 || maxActionsPerBattleTick <= 0) {
            throw new IllegalArgumentException("Action stack budget limits must be positive.");
        }
        this.maxActionsPerStackPerTick = maxActionsPerStackPerTick;
        this.maxActionsPerBattleTick = maxActionsPerBattleTick;
    }

    public ActionStack createStack(int priority) {
        return createStack(priority, NoContext.INSTANCE);
    }

    public ActionStack createStack(int priority, BattleEventContext eventContext) {
        ActionStack stack = new ActionStack(
                this.nextStackId++,
                priority,
                this.nextCreatedOrder++,
                Objects.requireNonNull(eventContext, "eventContext")
        );
        this.stacks.add(stack);
        sortStacks();
        return stack;
    }

    public BattleActionResult push(long stackId, BattleAction action) {
        Objects.requireNonNull(action, "action");
        ActionStack stack = findStack(stackId);
        BattleActionResult conflictResult = resolveConflict(stack, action);
        if (conflictResult != BattleActionResult.COMPLETED) {
            removeEmptyStacks();
            return conflictResult;
        }

        stack.push(action);
        return BattleActionResult.COMPLETED;
    }

    public boolean hasConflict(String conflictKey) {
        return hasConflict(conflictKey, BattleActionContext.NO_STACK);
    }

    public boolean hasConflict(String conflictKey, long ignoredStackId) {
        Objects.requireNonNull(conflictKey, "conflictKey");
        for (ActionStack stack : this.stacks) {
            if (stack.stackId() == ignoredStackId) {
                continue;
            }
            if (stack.containsConflict(conflictKey)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasStack(long stackId) {
        for (ActionStack stack : this.stacks) {
            if (stack.stackId() == stackId) {
                return true;
            }
        }
        return false;
    }

    public boolean cancelStack(long stackId) {
        return this.stacks.removeIf(stack -> stack.stackId() == stackId);
    }

    public boolean hasAnyConflict(String... conflictKeys) {
        Objects.requireNonNull(conflictKeys, "conflictKeys");
        for (String conflictKey : conflictKeys) {
            if (hasConflict(conflictKey)) {
                return true;
            }
        }
        return false;
    }

    public void advance() {
        int actionsThisBattleTick = 0;
        List<ActionStack> advancingStacks = new ArrayList<>(this.stacks);

        for (ActionStack stack : advancingStacks) {
            if (!this.stacks.contains(stack)) {
                continue;
            }
            StackAdvanceResult result = advanceStack(stack, actionsThisBattleTick);
            actionsThisBattleTick = result.actionsThisBattleTick();

            if (result.releaseStack() || stack.isEmpty()) {
                this.stacks.remove(stack);
            }
            if (result.stopBattleTick()) {
                return;
            }
        }
    }

    private StackAdvanceResult advanceStack(ActionStack stack, int actionsThisBattleTick) {
        int actionsThisStack = 0;

        while (!stack.isEmpty()) {
            if (this.instance.ended()) {
                return new StackAdvanceResult(actionsThisBattleTick, true, true);
            }
            actionsThisStack++;
            actionsThisBattleTick++;

            BattleAction action = stack.peek();
            if (actionsThisStack > this.maxActionsPerStackPerTick) {
                logStackFailed(stack, action, "Action stack exceeded maxActionsPerStackPerTick.");
                return new StackAdvanceResult(actionsThisBattleTick, true, false);
            }
            if (actionsThisBattleTick > this.maxActionsPerBattleTick) {
                logStackFailed(stack, action, "Battle tick exceeded maxActionsPerBattleTick.");
                return new StackAdvanceResult(actionsThisBattleTick, true, true);
            }

            BattleActionResult result = action.run(new BattleActionContext(
                    this.instance,
                    stack.eventContext(),
                    stack.stackId()
            ));

            if (this.instance.ended()) {
                return new StackAdvanceResult(actionsThisBattleTick, true, true);
            }
            if (result == BattleActionResult.RUNNING) {
                return new StackAdvanceResult(actionsThisBattleTick, false, false);
            }
            if (result == BattleActionResult.COMPLETED || result == BattleActionResult.CANCELLED) {
                stack.pop();
                continue;
            }
            if (result == BattleActionResult.BLOCKED) {
                return new StackAdvanceResult(actionsThisBattleTick, true, false);
            }

            logStackFailed(stack, action, "Action returned FAILED.");
            return new StackAdvanceResult(actionsThisBattleTick, true, false);
        }

        return new StackAdvanceResult(actionsThisBattleTick, true, false);
    }

    private ActionStack findStack(long stackId) {
        for (ActionStack stack : this.stacks) {
            if (stack.stackId() == stackId) {
                return stack;
            }
        }
        throw new IllegalArgumentException("Unknown action stack id: " + stackId);
    }

    private BattleActionResult resolveConflict(ActionStack targetStack, BattleAction action) {
        BattleActionConflict conflict = action.conflict();
        if (!conflict.active() || !hasConflict(conflict.key(), targetStack.stackId())) {
            return BattleActionResult.COMPLETED;
        }

        return switch (conflict.policy()) {
            case CANCEL_NEW -> BattleActionResult.CANCELLED;
            case BLOCK_NEW -> BattleActionResult.BLOCKED;
            case CANCEL_OLD -> {
                cancelConflictingActions(conflict.key(), targetStack.stackId());
                yield BattleActionResult.COMPLETED;
            }
            case WAIT_OLD -> {
                targetStack.push(new WaitForActionConflictAction(conflict.key()));
                yield BattleActionResult.COMPLETED;
            }
            case ALLOW_PARALLEL -> BattleActionResult.COMPLETED;
        };
    }

    private void cancelConflictingActions(String conflictKey, long ignoredStackId) {
        for (ActionStack stack : this.stacks) {
            if (stack.stackId() == ignoredStackId) {
                continue;
            }
            stack.removeConflictingActions(conflictKey);
        }
        removeEmptyStacks();
    }

    private void removeEmptyStacks() {
        this.stacks.removeIf(ActionStack::isEmpty);
    }

    private void sortStacks() {
        this.stacks.sort(
                Comparator.comparingInt(ActionStack::priority).reversed()
                        .thenComparingLong(ActionStack::createdOrder)
        );
    }

    private void logStackFailed(ActionStack stack, BattleAction action, String reason) {
        MineTale.LOGGER.error(
                "Battle action stack failed. battleId={}, battleTick={}, stackId={}, actionType={}, reason={}",
                this.instance.battleId(),
                this.instance.timeline().battleTick(),
                stack.stackId(),
                action == null ? "<none>" : action.actionType(),
                reason
        );
    }

    private record StackAdvanceResult(
            int actionsThisBattleTick,
            boolean releaseStack,
            boolean stopBattleTick
    ) {
    }
}
