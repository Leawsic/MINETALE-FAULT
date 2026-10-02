package cn.jehorstudio.minetale.battle.logic.states;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PlayerStateSnapshot(
        UUID playerId,
        ActorRef soulRef,
        int hp,
        int maxHp,
        int level,
        boolean invincible,
        double invincibleTimeSeconds,
        long invincibleStartedAtBattleTick,
        List<String> equipmentIds,
        List<String> itemIds
) {
    public PlayerStateSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(soulRef, "soulRef");
        if (hp < 0) {
            throw new IllegalArgumentException("hp must be >= 0.");
        }
        if (maxHp < 1) {
            throw new IllegalArgumentException("maxHp must be >= 1.");
        }
        if (hp > maxHp) {
            throw new IllegalArgumentException("hp must be <= maxHp.");
        }
        if (level < 1) {
            throw new IllegalArgumentException("level must be >= 1.");
        }
        if (!Double.isFinite(invincibleTimeSeconds) || invincibleTimeSeconds < 0.0D) {
            throw new IllegalArgumentException("invincibleTimeSeconds must be finite and >= 0.");
        }
        if (invincibleStartedAtBattleTick < 0L) {
            throw new IllegalArgumentException("invincibleStartedAtBattleTick must be >= 0.");
        }
        equipmentIds = List.copyOf(equipmentIds);
        itemIds = List.copyOf(itemIds);
    }
}
