package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.network.NetworkObjectId;
import cn.jehorstudio.minetale.battle.network.NetworkSequence;
import cn.jehorstudio.minetale.battle.logic.coordinate.CanonicalVec3;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record PlayerSoulPositionSnapshotPayload(
        UUID battleId,
        NetworkObjectId networkObjectId,
        UUID ownerPlayerId,
        NetworkSequence seq,
        long sourceBattleTick,
        CanonicalVec3 position,
        double yawYDeg
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PlayerSoulPositionSnapshotPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "player_soul_position"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerSoulPositionSnapshotPayload> STREAM_CODEC =
            StreamCodec.ofMember(PlayerSoulPositionSnapshotPayload::write, PlayerSoulPositionSnapshotPayload::read);

    public PlayerSoulPositionSnapshotPayload {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(networkObjectId, "networkObjectId");
        Objects.requireNonNull(ownerPlayerId, "ownerPlayerId");
        Objects.requireNonNull(seq, "seq");
        Objects.requireNonNull(position, "position");
        if (sourceBattleTick < 0L) {
            throw new IllegalArgumentException("sourceBattleTick must be >= 0.");
        }
        requireFinite(yawYDeg, "yawYDeg");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        BattlePayloadCodecs.writeNetworkObjectId(buf, this.networkObjectId);
        buf.writeUUID(this.ownerPlayerId);
        BattlePayloadCodecs.writeNetworkSequence(buf, this.seq);
        buf.writeLong(this.sourceBattleTick);
        BattlePayloadCodecs.writeCanonicalVec3(buf, this.position);
        buf.writeDouble(this.yawYDeg);
    }

    private static PlayerSoulPositionSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        return new PlayerSoulPositionSnapshotPayload(
                buf.readUUID(),
                BattlePayloadCodecs.readNetworkObjectId(buf),
                buf.readUUID(),
                BattlePayloadCodecs.readNetworkSequence(buf),
                buf.readLong(),
                BattlePayloadCodecs.readCanonicalVec3(buf),
                buf.readDouble()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
