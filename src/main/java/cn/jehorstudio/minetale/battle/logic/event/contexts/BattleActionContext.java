package cn.jehorstudio.minetale.battle.logic.event.contexts;

import cn.jehorstudio.minetale.battle.logic.BattleInstance;

import java.util.Objects;

public record BattleActionContext(
        BattleInstance instance,
        BattleEventContext eventContext,
        long stackId
) {
    public static final long NO_STACK = -1L;

    public BattleActionContext(BattleInstance instance, BattleEventContext eventContext) {
        this(instance, eventContext, NO_STACK);
    }

    public BattleActionContext {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(eventContext, "eventContext");
    }

    public boolean hasStack() {
        return this.stackId != NO_STACK;
    }

    public <T extends BattleEventContext> T eventContext(Class<T> contextType) {
        Objects.requireNonNull(contextType, "contextType");
        if (!contextType.isInstance(this.eventContext)) {
            throw new IllegalStateException(
                    "Current battle action context is %s, not %s.".formatted(
                            this.eventContext.getClass().getSimpleName(),
                            contextType.getSimpleName()
                    )
            );
        }
        return contextType.cast(this.eventContext);
    }
}
