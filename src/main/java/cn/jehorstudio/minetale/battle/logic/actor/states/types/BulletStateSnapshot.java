package cn.jehorstudio.minetale.battle.logic.actor.states.types;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;

public record BulletStateSnapshot(
        double damage,
        boolean damagesPlayer,
        boolean displayGlobally,
        int remainingRebounds,
        double spinDegPerSecond
) implements ActorTypeSnapshot {
    public static final BulletStateSnapshot EMPTY = new BulletStateSnapshot(0.0D, false, false, 0, 0.0D);

    public BulletStateSnapshot {
        if (!Double.isFinite(damage)) {
            throw new IllegalArgumentException("damage must be finite.");
        }
        if (damage < 0.0D) {
            throw new IllegalArgumentException("damage must be >= 0.");
        }
        if (remainingRebounds < 0) {
            throw new IllegalArgumentException("remainingRebounds must be >= 0.");
        }
        if (!Double.isFinite(spinDegPerSecond)) {
            throw new IllegalArgumentException("spinDegPerSecond must be finite.");
        }
    }

    @Override
    public ActorType actorType() {
        return ActorType.BULLET;
    }
}
