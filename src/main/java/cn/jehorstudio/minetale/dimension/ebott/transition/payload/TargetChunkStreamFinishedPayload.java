package cn.jehorstudio.minetale.dimension.ebott.transition.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

// 该标记只封闭此前的目标 Chunk 数据流
// 不代表客户端已经安装完成。
public record TargetChunkStreamFinishedPayload(UUID sessionId) implements CustomPacketPayload {
    public static final Type<TargetChunkStreamFinishedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "transition_target_chunk_stream_finished")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, TargetChunkStreamFinishedPayload> STREAM_CODEC =
            StreamCodec.ofMember(TargetChunkStreamFinishedPayload::write,
                    TargetChunkStreamFinishedPayload::read);

    public TargetChunkStreamFinishedPayload {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId);
    }

    private static TargetChunkStreamFinishedPayload read(RegistryFriendlyByteBuf buffer) {
        return new TargetChunkStreamFinishedPayload(buffer.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
