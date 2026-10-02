package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.battle.logic.states.PlayerStateSnapshot;

import java.util.Objects;
import java.util.UUID;

public record PlayerStateInitialSnapshot(
        UUID recipientPlayerId,
        PlayerStateSnapshot playerState
) {
    public PlayerStateInitialSnapshot {
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        Objects.requireNonNull(playerState, "playerState");
        if (!recipientPlayerId.equals(playerState.playerId())) {
            throw new IllegalArgumentException("recipientPlayerId must match playerState.playerId.");
        }
    }
}
