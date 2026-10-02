package cn.jehorstudio.minetale.narrative.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialoguePackage;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueProfile;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryStateDefinition;

public record NarrativeSnapshot(
        Map<ResourceLocation, StoryStateDefinition> storyStates,
        Map<ResourceLocation, DialogueProfile> profiles,
        Map<ResourceLocation, Map<String, DialoguePackage>> dialoguePackages
) {
    public static final String FALLBACK_LOCALE = "zh_cn";
    public static final NarrativeSnapshot EMPTY = new NarrativeSnapshot(Map.of(), Map.of(), Map.of());

    public NarrativeSnapshot {
        storyStates = Map.copyOf(storyStates);
        profiles = Map.copyOf(profiles);
        dialoguePackages = dialoguePackages.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Map.copyOf(entry.getValue()))
        );
    }

    public Optional<DialoguePackage> dialoguePackage(ResourceLocation id, String locale) {
        Map<String, DialoguePackage> variants = this.dialoguePackages.get(id);
        if (variants == null) {
            return Optional.empty();
        }
        String normalized = normalizeLocale(locale);
        DialoguePackage selected = variants.get(normalized);
        return Optional.ofNullable(selected == null ? variants.get(FALLBACK_LOCALE) : selected);
    }

    public DialoguePackage requireDialoguePackage(ResourceLocation id, String locale) {
        return dialoguePackage(id, locale)
                .orElseThrow(() -> new NarrativeValidationException("Missing dialogue package " + id + "."));
    }

    public static String normalizeLocale(String locale) {
        return Objects.requireNonNullElse(locale, FALLBACK_LOCALE).toLowerCase(java.util.Locale.ROOT);
    }
}
