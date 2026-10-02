package cn.jehorstudio.minetale.battle.network;

import cn.jehorstudio.minetale.battle.logic.actor.ActorType;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record NetworkObjectId(String value) {
    public NetworkObjectId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("networkObjectId must not be blank.");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException("networkObjectId must not have leading or trailing whitespace.");
        }
    }

    public static NetworkObjectId playerSoul(UUID battleId, UUID ownerPlayerId) {
        return derived(battleId, ownerPlayerId, ActorType.PLAYER_SOUL);
    }

    public static NetworkObjectId derived(UUID battleId, UUID ownerPlayerId, ActorType proxyType) {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(proxyType, "proxyType");
        if (proxyType == ActorType.NETWORK_PROXY) {
            throw new IllegalArgumentException("proxyType cannot be NETWORK_PROXY.");
        }
        String typeKey = proxyType.name().toLowerCase(Locale.ROOT);
        return new NetworkObjectId("battle:%s:owner:%s:proxy:%s".formatted(battleId, ownerPlayerId, typeKey));
    }

    @Override
    public String toString() {
        return this.value;
    }
}
