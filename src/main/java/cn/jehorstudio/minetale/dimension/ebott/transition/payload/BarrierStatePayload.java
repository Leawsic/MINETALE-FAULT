package cn.jehorstudio.minetale.dimension.ebott.transition.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

// 同步指定会话的权威结界碰撞状态。
public record BarrierStatePayload(UUID sessionId, boolean passable) implements CustomPacketPayload {
    public static final Type<BarrierStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "transition_barrier_state")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, BarrierStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(BarrierStatePayload::write, BarrierStatePayload::read);

    public BarrierStatePayload {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(this.sessionId);
        buffer.writeBoolean(this.passable);
    }

    private static BarrierStatePayload read(RegistryFriendlyByteBuf buffer) {
        return new BarrierStatePayload(buffer.readUUID(), buffer.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
