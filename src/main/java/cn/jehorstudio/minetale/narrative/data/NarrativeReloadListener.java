package cn.jehorstudio.minetale.narrative.data;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.narrative.runtime.DialogueSessionManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.jehorstudio.minetale.narrative.data.NarrativeCompiler.DialogueResourceId;

public final class NarrativeReloadListener implements ResourceManagerReloadListener {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "narrative");

    private static final FileToIdConverter STORY_STATES = FileToIdConverter.json("story_states");
    private static final FileToIdConverter DIALOGUE_PROFILES = FileToIdConverter.json("dialogue_profiles");
    private static final FileToIdConverter DIALOGUES = FileToIdConverter.json("dialogues");

    private final RegistryAccess registryAccess;

    private NarrativeReloadListener(RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;
    }

    public static void register(AddServerReloadListenersEvent event) {
        event.addListener(ID, new NarrativeReloadListener(event.getRegistryAccess()));
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        DialogueSessionManager.interruptAll("resource_reload");
        try {
            Map<ResourceLocation, JsonElement> states = read(STORY_STATES, resourceManager);
            Map<ResourceLocation, JsonElement> profiles = read(DIALOGUE_PROFILES, resourceManager);
            Map<DialogueResourceId, JsonElement> dialogues = readDialogues(resourceManager);
            NarrativeSnapshot snapshot = NarrativeCompiler.compile(
                    states,
                    profiles,
                    dialogues,
                    this.registryAccess
            );
            NarrativeCatalog.publish(snapshot);
            MineTale.LOGGER.info(
                    "Published narrative snapshot: {} story states, {} profiles, {} dialogue packages",
                    snapshot.storyStates().size(),
                    snapshot.profiles().size(),
                    snapshot.dialoguePackages().size()
            );
        } catch (RuntimeException exception) {
            MineTale.LOGGER.error(
                    "Rejected complete narrative snapshot; keeping the previous valid snapshot",
                    exception
            );
        }
    }

    private static Map<ResourceLocation, JsonElement> read(
            FileToIdConverter converter,
            ResourceManager resourceManager
    ) {
        Map<ResourceLocation, JsonElement> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : converter.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation id = converter.fileToId(entry.getKey());
            result.put(id, readJson(entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static Map<DialogueResourceId, JsonElement> readDialogues(ResourceManager resourceManager) {
        Map<DialogueResourceId, JsonElement> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : DIALOGUES.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation localizedId = DIALOGUES.fileToId(entry.getKey());
            String path = localizedId.getPath();
            int separator = path.indexOf('/');
            if (separator <= 0 || separator == path.length() - 1) {
                throw new NarrativeValidationException(
                        entry.getKey() + " must be under dialogues/<locale>/<path>.json."
                );
            }
            String locale = path.substring(0, separator);
            ResourceLocation logicalId = ResourceLocation.fromNamespaceAndPath(
                    localizedId.getNamespace(),
                    path.substring(separator + 1)
            );
            DialogueResourceId resourceId = new DialogueResourceId(logicalId, locale);
            if (result.put(resourceId, readJson(entry.getKey(), entry.getValue())) != null) {
                throw new NarrativeValidationException("Duplicate dialogue resource " + resourceId + ".");
            }
        }
        return Map.copyOf(result);
    }

    private static JsonElement readJson(ResourceLocation file, Resource resource) {
        try (BufferedReader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader);
        } catch (IOException | RuntimeException exception) {
            throw new NarrativeValidationException("Could not read narrative resource " + file + ".", exception);
        }
    }
}
