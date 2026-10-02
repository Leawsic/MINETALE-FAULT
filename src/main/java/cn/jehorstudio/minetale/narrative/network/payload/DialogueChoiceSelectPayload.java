package cn.jehorstudio.minetale.narrative.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record DialogueChoiceSelectPayload(
        UUID sessionId,
        String choiceId,
        String optionId
) implements CustomPacketPayload {
    public static final Type<DialogueChoiceSelectPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "dialogue_choice_select")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoiceSelectPayload> STREAM_CODEC =
            StreamCodec.ofMember(DialogueChoiceSelectPayload::write, DialogueChoiceSelectPayload::read);

    public DialogueChoiceSelectPayload {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null.");
        }
        if (choiceId == null || choiceId.isBlank()) {
            throw new IllegalArgumentException("choiceId must not be blank.");
        }
        if (optionId == null || optionId.isBlank()) {
            throw new IllegalArgumentException("optionId must not be blank.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.sessionId);
        buf.writeUtf(this.choiceId);
        buf.writeUtf(this.optionId);
    }

    private static DialogueChoiceSelectPayload read(RegistryFriendlyByteBuf buf) {
        return new DialogueChoiceSelectPayload(buf.readUUID(), buf.readUtf(), buf.readUtf());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
