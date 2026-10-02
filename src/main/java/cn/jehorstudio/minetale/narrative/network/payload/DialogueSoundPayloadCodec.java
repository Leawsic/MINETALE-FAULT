package cn.jehorstudio.minetale.narrative.network.payload;

import cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueSoundSet;
import cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueSoundSpec;
import cn.jehorstudio.minetale.narrative.data.NarrativeModel.SoundMultiplierRange;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.sounds.SoundSource;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

final class DialogueSoundPayloadCodec {
    private DialogueSoundPayloadCodec() {
    }

    static void write(RegistryFriendlyByteBuf buf, DialogueSoundSet sounds) {
        writeCandidates(buf, sounds.perPage());
        writeCandidates(buf, sounds.perGrapheme());
    }

    static DialogueSoundSet read(RegistryFriendlyByteBuf buf) {
        return new DialogueSoundSet(readCandidates(buf), readCandidates(buf));
    }

    private static void writeCandidates(RegistryFriendlyByteBuf buf, List<DialogueSoundSpec> candidates) {
        buf.writeVarInt(candidates.size());
        candidates.forEach(spec -> writeSpec(buf, spec));
    }

    private static List<DialogueSoundSpec> readCandidates(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 64) {
            throw new IllegalArgumentException("Dialogue sound candidate count must be between 0 and 64.");
        }
        List<DialogueSoundSpec> candidates = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            candidates.add(readSpec(buf));
        }
        return List.copyOf(candidates);
    }

    private static void writeSpec(RegistryFriendlyByteBuf buf, DialogueSoundSpec spec) {
        buf.writeResourceLocation(spec.event());
        buf.writeUtf(spec.source().getName());
        writeRange(buf, spec.volumeMultiplier());
        writeRange(buf, spec.pitchMultiplier());
    }

    private static DialogueSoundSpec readSpec(RegistryFriendlyByteBuf buf) {
        var event = buf.readResourceLocation();
        String sourceName = buf.readUtf();
        SoundSource source = Arrays.stream(SoundSource.values())
                .filter(candidate -> candidate.getName().equals(sourceName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dialogue SoundSource " + sourceName + "."));
        return new DialogueSoundSpec(event, source, readRange(buf), readRange(buf));
    }

    private static void writeRange(RegistryFriendlyByteBuf buf, SoundMultiplierRange range) {
        buf.writeDouble(range.min());
        buf.writeDouble(range.max());
    }

    private static SoundMultiplierRange readRange(RegistryFriendlyByteBuf buf) {
        return new SoundMultiplierRange(buf.readDouble(), buf.readDouble());
    }
}
