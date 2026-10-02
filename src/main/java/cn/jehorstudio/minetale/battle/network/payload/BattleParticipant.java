package cn.jehorstudio.minetale.battle.network.payload;

import java.util.Objects;
import java.util.UUID;

public record BattleParticipant(UUID playerId) {
    public BattleParticipant {
        Objects.requireNonNull(playerId, "playerId");
    }
}
