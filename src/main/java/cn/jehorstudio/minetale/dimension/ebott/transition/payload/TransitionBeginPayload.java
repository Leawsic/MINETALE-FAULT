package cn.jehorstudio.minetale.dimension.ebott.transition.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

// 建立无 Screen 预热会话，并一次性传递目标 Chunk 流与接缝渲染所需的冻结几何。
public record TransitionBeginPayload(
        UUID sessionId,
        int targetCenterBlockX,
        int targetCenterBlockZ,
        int envelopeRadius,
        int fallbackTargetArrivalY,
        int sourceCenterBlockX,
        int sourceCenterBlockZ,
        double sourceSeamY,
        double targetSeamY,
        int seamGeometryVersion,
        double apertureCenterOffsetX,
        double apertureCenterOffsetZ,
        double apertureRadius,
        int targetPreviewRadius
) implements CustomPacketPayload {
    public static final Type<TransitionBeginPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "transition_begin")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, TransitionBeginPayload> STREAM_CODEC =
            StreamCodec.ofMember(TransitionBeginPayload::write, TransitionBeginPayload::read);

    public TransitionBeginPayload {
        Objects.requireNonNull(sessionId, "sessionId");
        if (envelopeRadius < 0 || targetPreviewRadius <= 0) {
            throw new IllegalArgumentException("invalid transition envelope");
        }
        if (!Double.isFinite(sourceSeamY)
                || !Double.isFinite(targetSeamY)
                || seamGeometryVersion <= 0
                || !Double.isFinite(apertureCenterOffsetX)
                || !Double.isFinite(apertureCenterOffsetZ)
                || !Double.isFinite(apertureRadius)
                || !(apertureRadius > 0.0)) {
            throw new IllegalArgumentException("invalid transition seam geometry");
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId);
        buffer.writeVarInt(targetCenterBlockX);
        buffer.writeVarInt(targetCenterBlockZ);
        buffer.writeVarInt(envelopeRadius);
        buffer.writeVarInt(fallbackTargetArrivalY);
        buffer.writeVarInt(sourceCenterBlockX);
        buffer.writeVarInt(sourceCenterBlockZ);
        buffer.writeDouble(sourceSeamY);
        buffer.writeDouble(targetSeamY);
        buffer.writeVarInt(seamGeometryVersion);
        buffer.writeDouble(apertureCenterOffsetX);
        buffer.writeDouble(apertureCenterOffsetZ);
        buffer.writeDouble(apertureRadius);
        buffer.writeVarInt(targetPreviewRadius);
    }

    private static TransitionBeginPayload read(RegistryFriendlyByteBuf buffer) {
        return new TransitionBeginPayload(
                buffer.readUUID(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readVarInt()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
