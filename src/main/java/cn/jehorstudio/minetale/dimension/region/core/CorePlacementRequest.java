package cn.jehorstudio.minetale.dimension.region.core;

import cn.jehorstudio.minetale.MineTale;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端请求服务端放置或清除 Core；placement 为 null 表示清除。坐标已取整到方块。 */
public record CorePlacementRequest(CorePlacement placement) implements CustomPacketPayload {
    public static final Type<CorePlacementRequest> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "core_placement_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CorePlacementRequest> STREAM_CODEC =
            StreamCodec.ofMember(CorePlacementRequest::write, CorePlacementRequest::read);

    public static CorePlacementRequest clear() {
        return new CorePlacementRequest(null);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(placement != null);
        if (placement != null) {
            ResourceLocation.STREAM_CODEC.encode(buffer, placement.scene());
            buffer.writeInt(placement.x());
            buffer.writeInt(placement.y());
            buffer.writeInt(placement.z());
        }
    }

    private static CorePlacementRequest read(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return clear();
        return new CorePlacementRequest(new CorePlacement(
                ResourceLocation.STREAM_CODEC.decode(buffer),
                buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
