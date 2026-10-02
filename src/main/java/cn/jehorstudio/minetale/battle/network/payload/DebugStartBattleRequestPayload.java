package cn.jehorstudio.minetale.battle.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

// 客户端只提交所选定义 ID。
public record DebugStartBattleRequestPayload(ResourceLocation battleDefinitionId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DebugStartBattleRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "debug_start_battle_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DebugStartBattleRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC,
                    DebugStartBattleRequestPayload::battleDefinitionId,
                    DebugStartBattleRequestPayload::new
            );

    public DebugStartBattleRequestPayload {
        Objects.requireNonNull(battleDefinitionId, "battleDefinitionId");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
