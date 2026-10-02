package cn.jehorstudio.minetale.narrative.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record DialogueAdvancePayload(UUID sessionId) implements CustomPacketPayload {
    public static final Type<DialogueAdvancePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "dialogue_advance")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueAdvancePayload> STREAM_CODEC =
            StreamCodec.ofMember(DialogueAdvancePayload::write, DialogueAdvancePayload::read);

    public DialogueAdvancePayload {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.sessionId);
    }

    private static DialogueAdvancePayload read(RegistryFriendlyByteBuf buf) {
        return new DialogueAdvancePayload(buf.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
