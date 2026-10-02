package cn.jehorstudio.minetale.battle.logic.event;

import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleEventContext;

import java.util.Objects;

public record LogicEventOccurrence(
        LogicEvents event,
        BattleEventContext context
) {
    public LogicEventOccurrence {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(context, "context");
        if (!event.contextType().isInstance(context)) {
            throw new IllegalArgumentException(
                    "Battle event %s requires context %s, got %s.".formatted(
                            event.name(),
                            event.contextType().getSimpleName(),
                            context.getClass().getSimpleName()
                    )
            );
        }
    }
}
