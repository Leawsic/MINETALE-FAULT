package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record BattleStartFailedPayload(
        UUID battleId,
        UUID playerId,
        String reason
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BattleStartFailedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_start_failed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleStartFailedPayload> STREAM_CODEC =
            StreamCodec.ofMember(BattleStartFailedPayload::write, BattleStartFailedPayload::read);

    public BattleStartFailedPayload {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(playerId, "playerId");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        buf.writeUUID(this.playerId);
        buf.writeUtf(this.reason);
    }

    private static BattleStartFailedPayload read(RegistryFriendlyByteBuf buf) {
        return new BattleStartFailedPayload(buf.readUUID(), buf.readUUID(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
