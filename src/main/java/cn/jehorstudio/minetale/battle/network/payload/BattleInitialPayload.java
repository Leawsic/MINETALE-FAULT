package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BattleInitialPayload(
        UUID battleId,
        List<BattleParticipant> invitedParticipants
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BattleInitialPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_initial"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleInitialPayload> STREAM_CODEC =
            StreamCodec.ofMember(BattleInitialPayload::write, BattleInitialPayload::read);

    public BattleInitialPayload {
        Objects.requireNonNull(battleId, "battleId");
        invitedParticipants = List.copyOf(invitedParticipants);
        if (invitedParticipants.isEmpty()) {
            throw new IllegalArgumentException("invitedParticipants must not be empty.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        BattlePayloadCodecs.writeParticipants(buf, this.invitedParticipants);
    }

    private static BattleInitialPayload read(RegistryFriendlyByteBuf buf) {
        return new BattleInitialPayload(buf.readUUID(), BattlePayloadCodecs.readParticipants(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
