package cn.jehorstudio.minetale.battle.logic.event;

import cn.jehorstudio.minetale.battle.logic.BattleLogicStateSnapshot;
import cn.jehorstudio.minetale.battle.logic.event.contexts.ActorTargetContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.BattleEventContext;
import cn.jehorstudio.minetale.battle.logic.event.contexts.NoContext;

import java.util.Arrays;
import java.util.Comparator;

public enum LogicEvents {
    ON_INITIALIZE(2_000, NoContext.class),
    ON_STEP(1_000, NoContext.class),

    BULLET_HIT_PLAYER(800, ActorTargetContext.class);

    private static final LogicEvents[] DISPATCH_ORDER = Arrays.stream(values())
            .sorted(Comparator.comparingInt(LogicEvents::priority).reversed())
            .toArray(LogicEvents[]::new);

    private final int priority;
    private final Class<? extends BattleEventContext> contextType;

    LogicEvents() {
        this(0, NoContext.class);
    }

    LogicEvents(int priority, Class<? extends BattleEventContext> contextType) {
        this.priority = priority;
        this.contextType = contextType;
    }

    public int priority() {
        return this.priority;
    }

    public Class<? extends BattleEventContext> contextType() {
        return this.contextType;
    }

    public boolean shouldDispatch(BattleLogicStateSnapshot snapshot) {
        return false;
    }

    public static LogicEvents[] dispatchOrder() {
        return DISPATCH_ORDER.clone();
    }
}
