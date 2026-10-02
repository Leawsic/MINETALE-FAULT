package cn.jehorstudio.minetale.dimension.ebott.transition.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

// 确认当前会话的目标数据已经安装
// 协议不以 mesh 或画面结果作为就绪条件
public record TargetPrewarmReadyPayload(UUID sessionId) implements CustomPacketPayload {
    public static final Type<TargetPrewarmReadyPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "transition_target_prewarm_ready")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, TargetPrewarmReadyPayload> STREAM_CODEC =
            StreamCodec.ofMember(TargetPrewarmReadyPayload::write, TargetPrewarmReadyPayload::read);

    public TargetPrewarmReadyPayload {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(this.sessionId);
    }

    private static TargetPrewarmReadyPayload read(RegistryFriendlyByteBuf buffer) {
        return new TargetPrewarmReadyPayload(buffer.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
