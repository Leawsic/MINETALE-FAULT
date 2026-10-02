package cn.jehorstudio.minetale.battle.network.server;

import cn.jehorstudio.minetale.battle.network.payload.BattleParticipant;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartPayload;
import cn.jehorstudio.minetale.battle.network.payload.BattleStartSharedSection;
import cn.jehorstudio.minetale.battle.network.payload.PlayerStateInitialSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class BattleStartDispatch {
    private final BattleStartSharedSection shared;
    private final Map<UUID, PlayerStateInitialSnapshot> privatePlayerStates;

    BattleStartDispatch(BattleStartSharedSection shared, Map<UUID, PlayerStateInitialSnapshot> privatePlayerStates) {
        this.shared = Objects.requireNonNull(shared, "shared");
        this.privatePlayerStates = copyPrivatePlayerStates(shared.finalParticipants(), privatePlayerStates);
    }

    public BattleStartSharedSection shared() {
        return this.shared;
    }

    public List<BattleParticipant> finalParticipants() {
        return this.shared.finalParticipants();
    }

    public Optional<PlayerStateInitialSnapshot> privatePlayerState(UUID recipientPlayerId) {
        return Optional.ofNullable(this.privatePlayerStates.get(Objects.requireNonNull(recipientPlayerId, "recipientPlayerId")));
    }

    public BattleStartPayload payloadFor(UUID recipientPlayerId) {
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        boolean finalParticipant = this.shared.finalParticipants().stream()
                .anyMatch(participant -> participant.playerId().equals(recipientPlayerId));
        if (!finalParticipant) {
            throw new IllegalArgumentException("recipientPlayerId must be a final participant.");
        }
        return new BattleStartPayload(this.shared, privatePlayerState(recipientPlayerId));
    }

    private static Map<UUID, PlayerStateInitialSnapshot> copyPrivatePlayerStates(
            List<BattleParticipant> finalParticipants,
            Map<UUID, PlayerStateInitialSnapshot> privatePlayerStates
    ) {
        Objects.requireNonNull(privatePlayerStates, "privatePlayerStates");
        Set<UUID> finalParticipantIds = finalParticipants.stream()
                .map(BattleParticipant::playerId)
                .collect(Collectors.toUnmodifiableSet());
        Map<UUID, PlayerStateInitialSnapshot> copied = new HashMap<>();
        for (Map.Entry<UUID, PlayerStateInitialSnapshot> entry : privatePlayerStates.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), "playerId");
            PlayerStateInitialSnapshot snapshot = Objects.requireNonNull(entry.getValue(), "playerStateInitialSnapshot");
            if (!playerId.equals(snapshot.recipientPlayerId())) {
                throw new IllegalArgumentException("private player state key must match recipientPlayerId.");
            }
            if (!finalParticipantIds.contains(playerId)) {
                throw new IllegalArgumentException("private player state must belong to a final participant.");
            }
            copied.put(playerId, snapshot);
        }
        for (UUID finalParticipantId : finalParticipantIds) {
            if (!copied.containsKey(finalParticipantId)) {
                throw new IllegalArgumentException("private player state is required for every final participant.");
            }
        }
        return Map.copyOf(copied);
    }
}
