package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record BattleResultReportPayload(
        UUID battleId,
        UUID playerId,
        BattleResultState resultState,
        long sourceBattleTick
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BattleResultReportPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "battle_result_report"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BattleResultReportPayload> STREAM_CODEC =
            StreamCodec.ofMember(BattleResultReportPayload::write, BattleResultReportPayload::read);

    public BattleResultReportPayload {
        Objects.requireNonNull(battleId, "battleId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(resultState, "resultState");
        if (sourceBattleTick < 0L) {
            throw new IllegalArgumentException("sourceBattleTick must be >= 0.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.battleId);
        buf.writeUUID(this.playerId);
        buf.writeEnum(this.resultState);
        buf.writeLong(this.sourceBattleTick);
    }

    private static BattleResultReportPayload read(RegistryFriendlyByteBuf buf) {
        return new BattleResultReportPayload(
                buf.readUUID(),
                buf.readUUID(),
                buf.readEnum(BattleResultState.class),
                buf.readLong()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
