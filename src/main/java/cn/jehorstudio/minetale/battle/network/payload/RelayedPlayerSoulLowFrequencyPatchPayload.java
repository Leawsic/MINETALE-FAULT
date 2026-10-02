package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record RelayedPlayerSoulLowFrequencyPatchPayload(
        PlayerSoulLowFrequencyPatchPayload patch,
        UUID sourcePlayerId,
        long relayGameTick
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RelayedPlayerSoulLowFrequencyPatchPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "relayed_player_soul_low_frequency_patch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RelayedPlayerSoulLowFrequencyPatchPayload> STREAM_CODEC =
            StreamCodec.ofMember(RelayedPlayerSoulLowFrequencyPatchPayload::write, RelayedPlayerSoulLowFrequencyPatchPayload::read);

    public RelayedPlayerSoulLowFrequencyPatchPayload {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(sourcePlayerId, "sourcePlayerId");
        if (!sourcePlayerId.equals(patch.ownerPlayerId())) {
            throw new IllegalArgumentException("sourcePlayerId must match patch.ownerPlayerId.");
        }
        if (relayGameTick < 0L) {
            throw new IllegalArgumentException("relayGameTick must be >= 0.");
        }
    }

    public boolean shouldSendTo(UUID recipientPlayerId) {
        Objects.requireNonNull(recipientPlayerId, "recipientPlayerId");
        return !this.sourcePlayerId.equals(recipientPlayerId);
    }

    private void write(RegistryFriendlyByteBuf buf) {
        PlayerSoulLowFrequencyPatchPayload.STREAM_CODEC.encode(buf, this.patch);
        buf.writeUUID(this.sourcePlayerId);
        buf.writeLong(this.relayGameTick);
    }

    private static RelayedPlayerSoulLowFrequencyPatchPayload read(RegistryFriendlyByteBuf buf) {
        return new RelayedPlayerSoulLowFrequencyPatchPayload(
                PlayerSoulLowFrequencyPatchPayload.STREAM_CODEC.decode(buf),
                buf.readUUID(),
                buf.readLong()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
