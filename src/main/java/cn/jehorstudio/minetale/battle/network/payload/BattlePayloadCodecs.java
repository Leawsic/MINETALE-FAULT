package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.battle.logic.actor.ActorRef;
import cn.jehorstudio.minetale.battle.logic.actor.ActorType;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.PlayerSoulMode;
import cn.jehorstudio.minetale.battle.logic.states.PlayerStateSnapshot;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.NetworkSequence;
import net.minecraft.network.RegistryFriendlyByteBuf;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public final class BattlePayloadCodecs {
    private static final int MAX_PARTICIPANTS = 64;
    private static final int MAX_STRING_LIST_SIZE = 128;
    private static final int MAX_STRING_LENGTH = 256;

    private BattlePayloadCodecs() {
    }

    static void writeNetworkObjectId(RegistryFriendlyByteBuf buf, NetworkObjectId value) {
        buf.writeUtf(value.value(), MAX_STRING_LENGTH);
    }

    static NetworkObjectId readNetworkObjectId(RegistryFriendlyByteBuf buf) {
        return new NetworkObjectId(buf.readUtf(MAX_STRING_LENGTH));
    }

    static void writeNetworkSequence(RegistryFriendlyByteBuf buf, NetworkSequence value) {
        buf.writeLong(value.value());
    }

    static NetworkSequence readNetworkSequence(RegistryFriendlyByteBuf buf) {
        return new NetworkSequence(buf.readLong());
    }

    static void writeCanonicalVec3(RegistryFriendlyByteBuf buf, CanonicalVec3 value) {
        buf.writeDouble(value.x());
        buf.writeDouble(value.y());
        buf.writeDouble(value.z());
    }

    static CanonicalVec3 readCanonicalVec3(RegistryFriendlyByteBuf buf) {
        return new CanonicalVec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    static void writeOptionalInt(RegistryFriendlyByteBuf buf, OptionalInt value) {
        buf.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            buf.writeVarInt(value.getAsInt());
        }
    }

    static OptionalInt readOptionalInt(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? OptionalInt.of(buf.readVarInt()) : OptionalInt.empty();
    }

    static <E extends Enum<E>> void writeOptionalEnum(RegistryFriendlyByteBuf buf, Optional<E> value) {
        buf.writeBoolean(value.isPresent());
        value.ifPresent(buf::writeEnum);
    }

    static <E extends Enum<E>> Optional<E> readOptionalEnum(RegistryFriendlyByteBuf buf, Class<E> type) {
        return buf.readBoolean() ? Optional.of(buf.readEnum(type)) : Optional.empty();
    }

    static void writeParticipants(RegistryFriendlyByteBuf buf, List<BattleParticipant> participants) {
        buf.writeVarInt(participants.size());
        for (BattleParticipant participant : participants) {
            buf.writeUUID(participant.playerId());
        }
    }

    static List<BattleParticipant> readParticipants(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("participant list size out of range.");
        }
        List<BattleParticipant> participants = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            participants.add(new BattleParticipant(buf.readUUID()));
        }
        return List.copyOf(participants);
    }

    static void writePlayerStateInitialSnapshot(RegistryFriendlyByteBuf buf, PlayerStateInitialSnapshot value) {
        buf.writeUUID(value.recipientPlayerId());
        writePlayerStateSnapshot(buf, value.playerState());
    }

    static PlayerStateInitialSnapshot readPlayerStateInitialSnapshot(RegistryFriendlyByteBuf buf) {
        UUID recipientPlayerId = buf.readUUID();
        return new PlayerStateInitialSnapshot(recipientPlayerId, readPlayerStateSnapshot(buf));
    }

    static void writeOptionalPlayerStateInitialSnapshot(RegistryFriendlyByteBuf buf, Optional<PlayerStateInitialSnapshot> value) {
        buf.writeBoolean(value.isPresent());
        value.ifPresent(snapshot -> writePlayerStateInitialSnapshot(buf, snapshot));
    }

    static Optional<PlayerStateInitialSnapshot> readOptionalPlayerStateInitialSnapshot(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? Optional.of(readPlayerStateInitialSnapshot(buf)) : Optional.empty();
    }

    private static void writePlayerStateSnapshot(RegistryFriendlyByteBuf buf, PlayerStateSnapshot value) {
        buf.writeUUID(value.playerId());
        writeActorRef(buf, value.soulRef());
        buf.writeVarInt(value.hp());
        buf.writeVarInt(value.maxHp());
        buf.writeVarInt(value.level());
        buf.writeBoolean(value.invincible());
        buf.writeDouble(value.invincibleTimeSeconds());
        buf.writeLong(value.invincibleStartedAtBattleTick());
        writeStringList(buf, value.equipmentIds());
        writeStringList(buf, value.itemIds());
    }

    private static PlayerStateSnapshot readPlayerStateSnapshot(RegistryFriendlyByteBuf buf) {
        return new PlayerStateSnapshot(
                buf.readUUID(),
                readActorRef(buf),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readDouble(),
                buf.readLong(),
                readStringList(buf),
                readStringList(buf)
        );
    }

    private static void writeActorRef(RegistryFriendlyByteBuf buf, ActorRef ref) {
        buf.writeEnum(ref.type());
        buf.writeVarInt(ref.index());
        buf.writeVarInt(ref.generation());
    }

    private static ActorRef readActorRef(RegistryFriendlyByteBuf buf) {
        return ActorRef.of(buf.readEnum(ActorType.class), buf.readVarInt(), buf.readVarInt());
    }

    private static void writeStringList(RegistryFriendlyByteBuf buf, List<String> values) {
        buf.writeVarInt(values.size());
        for (String value : values) {
            buf.writeUtf(value, MAX_STRING_LENGTH);
        }
    }

    private static List<String> readStringList(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_STRING_LIST_SIZE) {
            throw new IllegalArgumentException("string list size out of range.");
        }
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buf.readUtf(MAX_STRING_LENGTH));
        }
        return List.copyOf(values);
    }
}
