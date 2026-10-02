package cn.jehorstudio.minetale.narrative.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record DialogueClosePayload(UUID sessionId, String reason) implements CustomPacketPayload {
    public static final Type<DialogueClosePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "dialogue_close")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueClosePayload> STREAM_CODEC =
            StreamCodec.ofMember(DialogueClosePayload::write, DialogueClosePayload::read);

    public DialogueClosePayload {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(reason, "reason");
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.sessionId);
        buf.writeUtf(this.reason);
    }

    private static DialogueClosePayload read(RegistryFriendlyByteBuf buf) {
        return new DialogueClosePayload(buf.readUUID(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
