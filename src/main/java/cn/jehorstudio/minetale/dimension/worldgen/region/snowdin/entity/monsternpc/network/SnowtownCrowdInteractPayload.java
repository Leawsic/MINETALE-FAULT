package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

// 提交客户端 GPU 拾取结果，服务端仍独立验证人口归属、距离与视线。
public record SnowtownCrowdInteractPayload(
        int areaX,
        int areaZ,
        int componentIndex,
        int sectorX,
        int sectorZ,
        int surfaceId,
        int agentId,
        double agentX,
        double agentY,
        double agentZ
) implements CustomPacketPayload {
    public static final Type<SnowtownCrowdInteractPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "snowtown_crowd_interact")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SnowtownCrowdInteractPayload> STREAM_CODEC =
            StreamCodec.ofMember(SnowtownCrowdInteractPayload::write, SnowtownCrowdInteractPayload::read);

    public SnowtownCrowdInteractPayload {
        if (componentIndex < 0 || surfaceId < 0 || agentId < 0
                || !Double.isFinite(agentX)
                || !Double.isFinite(agentY)
                || !Double.isFinite(agentZ)) {
            throw new IllegalArgumentException("Snowtown GPU 居民交互请求包含非法字段");
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(this.areaX);
        buffer.writeInt(this.areaZ);
        buffer.writeVarInt(this.componentIndex);
        buffer.writeInt(this.sectorX);
        buffer.writeInt(this.sectorZ);
        buffer.writeVarInt(this.surfaceId);
        buffer.writeVarInt(this.agentId);
        buffer.writeDouble(this.agentX);
        buffer.writeDouble(this.agentY);
        buffer.writeDouble(this.agentZ);
    }

    private static SnowtownCrowdInteractPayload read(RegistryFriendlyByteBuf buffer) {
        return new SnowtownCrowdInteractPayload(
                buffer.readInt(),
                buffer.readInt(),
                buffer.readVarInt(),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
