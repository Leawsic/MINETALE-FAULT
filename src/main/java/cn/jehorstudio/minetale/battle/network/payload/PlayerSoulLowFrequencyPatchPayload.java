package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.logic.actor.states.types.PlayerSoulMode;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.NetworkSequence;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

public record PlayerSoulLowFrequencyPatchPayload(
        UUID battleId,
        NetworkObjectId networkObjectId,
        UUID ownerPlayerId,
        NetworkSequence seq,
        long sourceBattleTick,
        OptionalInt hpDisplay,
        OptionalInt maxHpDisplay,
        Optional<BattleAliveState> aliveState,
        Optional<PlayerSoulMode> mode
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PlayerSoulLowFrequencyPatchPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "player_soul_low_frequency_patch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerSoulLowFrequencyPatchPayload> STREAM_CODEC =
            StreamCodec.ofMember(PlayerSoulLowFrequencyPatchPayload::write, PlayerSoulLowFrequencyPatchPayload::read);

    public PlayerSoulLowFrequencyPatchPayload {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(seq, "seq");
        hpDisplay = Objects.requireNonNull(hpDisplay, "hpDisplay");
        maxHpDisplay = Objects.requireNonNull(maxHpDisplay, "maxHpDisplay");
        aliveState = Objects.requireNonNull(aliveState, "aliveState");
        mode = Objects.requireNonNull(mode, "mode");
        if (sourceBattleTick < 0L) {
            throw new IllegalArgumentException("sourceBattleTick must be >= 0.");
        }
        hpDisplay.ifPresent(value -> requireNonNegative(value, "hpDisplay"));
        maxHpDisplay.ifPresent(value -> requirePositive(value, "maxHpDisplay"));
        if (hpDisplay.isPresent() && maxHpDisplay.isPresent() && hpDisplay.getAsInt() > maxHpDisplay.getAsInt()) {
            throw new IllegalArgumentException("hpDisplay must be <= maxHpDisplay when both are present.");
        }
        if (hpDisplay.isEmpty()
                && maxHpDisplay.isEmpty()
                && aliveState.isEmpty()
                && mode.isEmpty()) {
            throw new IllegalArgumentException("low frequency patch must contain at least one field.");
        }
    }

    public boolean emptyPatch() {
        return hpDisplay.isEmpty()
                && maxHpDisplay.isEmpty()
                && aliveState.isEmpty()
                && mode.isEmpty();
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0.");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        BattlePayloadCodecs.writeNetworkObjectId(buf, this.networkObjectId);
        buf.writeUUID(this.ownerPlayerId);
        BattlePayloadCodecs.writeNetworkSequence(buf, this.seq);
        buf.writeLong(this.sourceBattleTick);
        BattlePayloadCodecs.writeOptionalInt(buf, this.hpDisplay);
        BattlePayloadCodecs.writeOptionalInt(buf, this.maxHpDisplay);
        BattlePayloadCodecs.writeOptionalEnum(buf, this.aliveState);
        BattlePayloadCodecs.writeOptionalEnum(buf, this.mode);
    }

    private static PlayerSoulLowFrequencyPatchPayload read(RegistryFriendlyByteBuf buf) {
        return new PlayerSoulLowFrequencyPatchPayload(
                buf.readUUID(),
                BattlePayloadCodecs.readNetworkObjectId(buf),
                buf.readUUID(),
                BattlePayloadCodecs.readNetworkSequence(buf),
                buf.readLong(),
                BattlePayloadCodecs.readOptionalInt(buf),
                BattlePayloadCodecs.readOptionalInt(buf),
                BattlePayloadCodecs.readOptionalEnum(buf, BattleAliveState.class),
                BattlePayloadCodecs.readOptionalEnum(buf, PlayerSoulMode.class)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
