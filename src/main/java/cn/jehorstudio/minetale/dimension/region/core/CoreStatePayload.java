package cn.jehorstudio.minetale.dimension.region.core;

import cn.jehorstudio.minetale.MineTale;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 同步某维度的权威 Core 放置状态；placement 为 null 表示该维度没有 Core。 */
public record CoreStatePayload(CorePlacement placement) implements CustomPacketPayload {
    public static final Type<CoreStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "core_region_state")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CoreStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(CoreStatePayload::write, CoreStatePayload::read);

    public static CoreStatePayload empty() {
        return new CoreStatePayload(null);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(placement != null);
        if (placement != null) {
            ResourceLocation.STREAM_CODEC.encode(buffer, placement.scene());
            buffer.writeVarInt(placement.x());
            buffer.writeVarInt(placement.y());
            buffer.writeVarInt(placement.z());
        }
    }

    private static CoreStatePayload read(RegistryFriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) return new CoreStatePayload(null);
        return new CoreStatePayload(new CorePlacement(
                ResourceLocation.STREAM_CODEC.decode(buffer),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
