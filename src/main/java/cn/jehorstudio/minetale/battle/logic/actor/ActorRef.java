package cn.jehorstudio.minetale.battle.logic.actor;

import java.util.Objects;

public record ActorRef(
        ActorType type,
        int index,
        int generation
) {
    public ActorRef {
        Objects.requireNonNull(type, "type");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0.");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0.");
        }
    }

    public static ActorRef of(ActorType type, int index, int generation) {
        return new ActorRef(type, index, generation);
    }
}
