package cn.jehorstudio.minetale.narrative.network.payload;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueSoundSet;

public record DialogueChoicePayload(
        UUID sessionId,
        String choiceId,
        boolean top,
        Optional<ResourceLocation> portrait,
        boolean cameraFree,
        boolean skippable,
        double charactersPerSecond,
        DialogueSoundSet sound,
        List<Option> options
) implements CustomPacketPayload {
    public static final Type<DialogueChoicePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "dialogue_choice")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueChoicePayload> STREAM_CODEC =
            StreamCodec.ofMember(DialogueChoicePayload::write, DialogueChoicePayload::read);

    public DialogueChoicePayload {
        Objects.requireNonNull(sessionId, "sessionId");
        if (choiceId == null || choiceId.isBlank()) {
            throw new IllegalArgumentException("choiceId must not be blank.");
        }
        portrait = Objects.requireNonNull(portrait, "portrait");
        sound = Objects.requireNonNull(sound, "sound");
        if (!Double.isFinite(charactersPerSecond) || charactersPerSecond <= 0.0D) {
            throw new IllegalArgumentException("charactersPerSecond must be finite and > 0.");
        }
        options = List.copyOf(options);
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.sessionId);
        buf.writeUtf(this.choiceId);
        buf.writeBoolean(this.top);
        buf.writeBoolean(this.portrait.isPresent());
        this.portrait.ifPresent(buf::writeResourceLocation);
        buf.writeBoolean(this.cameraFree);
        buf.writeBoolean(this.skippable);
        buf.writeDouble(this.charactersPerSecond);
        DialogueSoundPayloadCodec.write(buf, this.sound);
        buf.writeVarInt(this.options.size());
        for (Option option : this.options) {
            buf.writeUtf(option.id());
            buf.writeUtf(option.literal());
        }
    }

    private static DialogueChoicePayload read(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        String choiceId = buf.readUtf();
        boolean top = buf.readBoolean();
        Optional<ResourceLocation> portrait = buf.readBoolean()
                ? Optional.of(buf.readResourceLocation())
                : Optional.empty();
        boolean cameraFree = buf.readBoolean();
        boolean skippable = buf.readBoolean();
        double charactersPerSecond = buf.readDouble();
        DialogueSoundSet sound = DialogueSoundPayloadCodec.read(buf);
        int optionCount = buf.readVarInt();
        if (optionCount <= 0 || optionCount > 128) {
            throw new IllegalArgumentException("Invalid dialogue option count " + optionCount + ".");
        }
        List<Option> options = new ArrayList<>(optionCount);
        for (int i = 0; i < optionCount; i++) {
            options.add(new Option(buf.readUtf(), buf.readUtf()));
        }
        return new DialogueChoicePayload(
                sessionId,
                choiceId,
                top,
                portrait,
                cameraFree,
                skippable,
                charactersPerSecond,
                sound,
                options
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Option(String id, String literal) {
        public Option {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Dialogue option id must not be blank.");
            }
            if (literal == null || literal.isEmpty()) {
                throw new IllegalArgumentException("Dialogue option literal must not be empty.");
            }
        }
    }
}
