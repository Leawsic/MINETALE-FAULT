package cn.jehorstudio.minetale.dimension.ebott.transition.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

// 终止指定会话，并释放其 prepared target 与渲染附件。
public record AbortTransitionPayload(UUID sessionId, String reason) implements CustomPacketPayload {
    public static final int MAX_REASON_LENGTH = 256;
    public static final Type<AbortTransitionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "transition_abort")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, AbortTransitionPayload> STREAM_CODEC =
            StreamCodec.ofMember(AbortTransitionPayload::write, AbortTransitionPayload::read);

    public AbortTransitionPayload {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("abort reason is too long");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(sessionId);
        buf.writeUtf(reason, MAX_REASON_LENGTH);
    }

    private static AbortTransitionPayload read(RegistryFriendlyByteBuf buf) {
        return new AbortTransitionPayload(buf.readUUID(), buf.readUtf(MAX_REASON_LENGTH));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
