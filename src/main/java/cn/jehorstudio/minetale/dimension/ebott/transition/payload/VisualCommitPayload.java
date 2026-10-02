package cn.jehorstudio.minetale.dimension.ebott.transition.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

// 一次性授权下一条原版 Respawn 采纳当前会话已暂存的目标数据。
public record VisualCommitPayload(UUID sessionId) implements CustomPacketPayload {
    public static final Type<VisualCommitPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "transition_visual_commit")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, VisualCommitPayload> STREAM_CODEC =
            StreamCodec.ofMember(VisualCommitPayload::write, VisualCommitPayload::read);

    public VisualCommitPayload {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId);
    }

    private static VisualCommitPayload read(RegistryFriendlyByteBuf buffer) {
        return new VisualCommitPayload(buffer.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
