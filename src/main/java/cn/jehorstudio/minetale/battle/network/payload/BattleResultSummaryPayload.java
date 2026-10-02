package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record BattleResultSummaryPayload(
        UUID battleId,
        BattleResultState resultState,
        boolean accepted
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BattleResultSummaryPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_result_summary"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleResultSummaryPayload> STREAM_CODEC =
            StreamCodec.ofMember(BattleResultSummaryPayload::write, BattleResultSummaryPayload::read);

    public BattleResultSummaryPayload {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(resultState, "resultState");
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        buf.writeEnum(this.resultState);
        buf.writeBoolean(this.accepted);
    }

    private static BattleResultSummaryPayload read(RegistryFriendlyByteBuf buf) {
        return new BattleResultSummaryPayload(
                buf.readUUID(),
                buf.readEnum(BattleResultState.class),
                buf.readBoolean()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
