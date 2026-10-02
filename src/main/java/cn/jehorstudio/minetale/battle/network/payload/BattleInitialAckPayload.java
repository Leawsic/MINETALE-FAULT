package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record BattleInitialAckPayload(
        UUID battleId,
        UUID playerId
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BattleInitialAckPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_initial_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleInitialAckPayload> STREAM_CODEC =
            StreamCodec.ofMember(BattleInitialAckPayload::write, BattleInitialAckPayload::read);

    public BattleInitialAckPayload {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(playerId, "playerId");
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        buf.writeUUID(this.playerId);
    }

    private static BattleInitialAckPayload read(RegistryFriendlyByteBuf buf) {
        return new BattleInitialAckPayload(buf.readUUID(), buf.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
