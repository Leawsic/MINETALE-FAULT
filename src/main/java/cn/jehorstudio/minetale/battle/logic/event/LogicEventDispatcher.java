package cn.jehorstudio.minetale.battle.logic.event;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.BattleLogicStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleEventContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.NoContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class LogicEventDispatcher {
    public static final int DEFAULT_MAX_EVENTS_PER_BATTLE_TICK = 2048;

    private final UUID battleId;
    private final LogicEventListenerRegistry listeners;
    private final Deque<LogicEventOccurrence> queue = new ArrayDeque<>();
    private final Deque<BattleEventContext> contextStack = new ArrayDeque<>();
    private final Deque<LogicEvents> eventStack = new ArrayDeque<>();
    private final List<LogicEvents> recentEvents = new ArrayList<>();
    private final int maxEventsPerBattleTick;

    public LogicEventDispatcher(UUID battleId, LogicEventListenerRegistry listeners) {
        this(battleId, listeners, DEFAULT_MAX_EVENTS_PER_BATTLE_TICK);
    }

    public LogicEventDispatcher(
            UUID battleId,
            LogicEventListenerRegistry listeners,
            int maxEventsPerBattleTick
    ) {
        this.battleId = Objects.requireNonNull(battleId, "battleId");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        if (maxEventsPerBattleTick <= 0) {
            throw new IllegalArgumentException("maxEventsPerBattleTick must be > 0.");
        }
        this.maxEventsPerBattleTick = maxEventsPerBattleTick;
    }

    public void register(Object listener) {
        this.listeners.register(listener);
    }

    public void send(LogicEvents event) {
        send(event, NoContext.INSTANCE);
    }

    public void send(LogicEvents event, BattleEventContext context) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(context, "context");
        if (!event.contextType().isInstance(context)) {
            throw new IllegalArgumentException(
                    "Battle event %s requires context %s, got %s.".formatted(
                            event,
                            event.contextType().getSimpleName(),
                            context.getClass().getSimpleName()
                    )
            );
        }
        this.queue.addLast(new LogicEventOccurrence(event, context));
    }

    public void enqueueSnapshotEvents(LogicEvents[] dispatchOrder, BattleLogicStateSnapshot snapshot) {
        for (LogicEvents event : dispatchOrder) {
            if (event.shouldDispatch(snapshot)) {
                send(event, NoContext.INSTANCE);
            }
        }
    }

    public void drain(long battleTick) {
        int processedEvents = 0;

        while (!this.queue.isEmpty()) {
            LogicEventOccurrence occurrence = this.queue.removeFirst();
            processedEvents++;

            if (processedEvents > this.maxEventsPerBattleTick) {
                logEventDrainLimit(battleTick, processedEvents, occurrence);
                this.queue.clear();
                return;
            }

            rememberRecentEvent(occurrence.event());
            this.eventStack.push(occurrence.event());
            this.contextStack.push(occurrence.context());
            try {
                this.listeners.dispatch(occurrence.event());
            } finally {
                this.contextStack.pop();
                this.eventStack.pop();
            }
        }
    }

    public BattleEventContext currentContext() {
        return this.contextStack.isEmpty() ? NoContext.INSTANCE : this.contextStack.peek();
    }

    public <T extends BattleEventContext> T currentContext(Class<T> contextType) {
        Objects.requireNonNull(contextType, "contextType");
        BattleEventContext context = currentContext();
        if (!contextType.isInstance(context)) {
            throw new IllegalStateException(
                    "Current battle event context is %s, not %s.".formatted(
                            context.getClass().getSimpleName(),
                            contextType.getSimpleName()
                    )
            );
        }
        return contextType.cast(context);
    }

    public LogicEvents currentEvent() {
        return this.eventStack.peek();
    }

    private void rememberRecentEvent(LogicEvents event) {
        this.recentEvents.add(event);
        if (this.recentEvents.size() > 32) {
            this.recentEvents.remove(0);
        }
    }

    private void logEventDrainLimit(long battleTick, int processedEvents, LogicEventOccurrence current) {
        MineTale.LOGGER.error(
                "Battle event drain exceeded maxEventsPerBattleTick. battleId={}, battleTick={}, processedEvents={}, currentEvent={}, queueSize={}, recentEvents={}",
                this.battleId,
                battleTick,
                processedEvents,
                current.event(),
                this.queue.size(),
                this.recentEvents
        );
    }
}
