package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record RelayedPlayerSoulPositionSnapshotPayload(
        PlayerSoulPositionSnapshotPayload snapshot,
        UUID sourcePlayerId,
        long relayGameTick
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RelayedPlayerSoulPositionSnapshotPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "relayed_player_soul_position"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RelayedPlayerSoulPositionSnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(RelayedPlayerSoulPositionSnapshotPayload::write, RelayedPlayerSoulPositionSnapshotPayload::read);

    public RelayedPlayerSoulPositionSnapshotPayload {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(sourcePlayerId, "sourcePlayerId");
        if (!sourcePlayerId.equals(snapshot.ownerPlayerId())) {
            throw new IllegalArgumentException("sourcePlayerId must match snapshot.ownerPlayerId.");
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
        PlayerSoulPositionSnapshotPayload.STREAM_CODEC.encode(buf, this.snapshot);
        buf.writeUUID(this.sourcePlayerId);
        buf.writeLong(this.relayGameTick);
    }

    private static RelayedPlayerSoulPositionSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        return new RelayedPlayerSoulPositionSnapshotPayload(
                PlayerSoulPositionSnapshotPayload.STREAM_CODEC.decode(buf),
                buf.readUUID(),
                buf.readLong()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
