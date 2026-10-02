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

public record DialoguePagePayload(
        UUID sessionId,
        String pageId,
        boolean top,
        Optional<ResourceLocation> portrait,
        boolean cameraFree,
        boolean skippable,
        boolean automatic,
        int autoDelayTicks,
        double charactersPerSecond,
        DialogueSoundSet sound,
        List<String> lines
) implements CustomPacketPayload {
    public static final Type<DialoguePagePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "dialogue_page")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, DialoguePagePayload> STREAM_CODEC =
            StreamCodec.ofMember(DialoguePagePayload::write, DialoguePagePayload::read);

    public DialoguePagePayload {
        Objects.requireNonNull(sessionId, "sessionId");
        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("pageId must not be blank.");
        }
        portrait = Objects.requireNonNull(portrait, "portrait");
        sound = Objects.requireNonNull(sound, "sound");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty.");
        }
        if (!Double.isFinite(charactersPerSecond) || charactersPerSecond <= 0.0D) {
            throw new IllegalArgumentException("charactersPerSecond must be finite and > 0.");
        }
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(this.sessionId);
        buf.writeUtf(this.pageId);
        buf.writeBoolean(this.top);
        buf.writeBoolean(this.portrait.isPresent());
        this.portrait.ifPresent(buf::writeResourceLocation);
        buf.writeBoolean(this.cameraFree);
        buf.writeBoolean(this.skippable);
        buf.writeBoolean(this.automatic);
        buf.writeVarInt(this.autoDelayTicks);
        buf.writeDouble(this.charactersPerSecond);
        DialogueSoundPayloadCodec.write(buf, this.sound);
        buf.writeVarInt(this.lines.size());
        this.lines.forEach(buf::writeUtf);
    }

    private static DialoguePagePayload read(RegistryFriendlyByteBuf buf) {
        UUID sessionId = buf.readUUID();
        String pageId = buf.readUtf();
        boolean top = buf.readBoolean();
        Optional<ResourceLocation> portrait = buf.readBoolean()
                ? Optional.of(buf.readResourceLocation())
                : Optional.empty();
        boolean cameraFree = buf.readBoolean();
        boolean skippable = buf.readBoolean();
        boolean automatic = buf.readBoolean();
        int autoDelayTicks = buf.readVarInt();
        double charactersPerSecond = buf.readDouble();
        DialogueSoundSet sound = DialogueSoundPayloadCodec.read(buf);
        int lineCount = buf.readVarInt();
        if (lineCount <= 0 || lineCount > 128) {
            throw new IllegalArgumentException("Invalid dialogue line count " + lineCount + ".");
        }
        List<String> lines = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            lines.add(buf.readUtf());
        }
        return new DialoguePagePayload(
                sessionId,
                pageId,
                top,
                portrait,
                cameraFree,
                skippable,
                automatic,
                autoDelayTicks,
                charactersPerSecond,
                sound,
                lines
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
