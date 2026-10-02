package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record BattleStartAckPayload(
        UUID battleId,
        UUID playerId
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BattleStartAckPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_start_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleStartAckPayload> STREAM_CODEC =
            StreamCodec.ofMember(BattleStartAckPayload::write, BattleStartAckPayload::read);

    public BattleStartAckPayload {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(playerId, "playerId");
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        buf.writeUUID(this.playerId);
    }

    private static BattleStartAckPayload read(RegistryFriendlyByteBuf buf) {
        return new BattleStartAckPayload(buf.readUUID(), buf.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
