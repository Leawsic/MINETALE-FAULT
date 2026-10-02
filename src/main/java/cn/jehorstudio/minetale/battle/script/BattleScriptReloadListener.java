package cn.jehorstudio.minetale.battle.script;

import cn.jehorstudio.minetale.MineTale;
import com.google.gson.JsonElement;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

import java.util.Map;

public final class BattleScriptReloadListener extends SimpleJsonResourceReloadListener<JsonElement> {
    private static final FileToIdConverter BATTLE_DEFINITION_LISTER = FileToIdConverter.json("battles");

    private BattleScriptReloadListener() {
        super(ExtraCodecs.JSON, BATTLE_DEFINITION_LISTER);
    }

    public static BattleScriptReloadListener create() {
        return new BattleScriptReloadListener();
    }

    public static void register(AddServerReloadListenersEvent event) {
        event.addListener(BattleScriptIds.RELOAD_LISTENER, create());
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> loadedData, ResourceManager resourceManager, ProfilerFiller profiler) {
        BattleScriptCompiler.CompileResult result = BattleScriptCompiler.compileLenient(loadedData);
        for (Map.Entry<ResourceLocation, RuntimeException> entry : result.rejected().entrySet()) {
            MineTale.LOGGER.error("Rejected BattleScript definition {}", entry.getKey(), entry.getValue());
        }
        BattleScriptCatalog.replace(result.definitions());
        MineTale.LOGGER.info("Loaded {} BattleScript definition(s), rejected {}", BattleScriptCatalog.size(), result.rejected().size());
    }
}
