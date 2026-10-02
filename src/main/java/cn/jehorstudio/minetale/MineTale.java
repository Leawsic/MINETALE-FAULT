package cn.jehorstudio.minetale;

import cn.jehorstudio.minetale.content.block.common.CommonBlocksRegistry;
import cn.jehorstudio.minetale.content.MineTaleCreativeTab;
import cn.jehorstudio.minetale.content.entity.flowey.FloweyRegistry;
import cn.jehorstudio.minetale.content.item.common.CommonItemsRegistry;
import cn.jehorstudio.minetale.content.player.soul.SoulRegistry;
import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.MysteriousCampfireRegistry;
import cn.jehorstudio.minetale.dimension.ebott.mountain.EbottMountainAttachments;
import cn.jehorstudio.minetale.dimension.ebott.transition.TransitionTicketTypes;
import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerRegistry;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerRegistry;
import cn.jehorstudio.minetale.battle.network.BattleNetworking;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.script.BattleScriptReloadListener;
import cn.jehorstudio.minetale.narrative.data.NarrativeReloadListener;
import cn.jehorstudio.minetale.narrative.NarrativeAttachments;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.SnowtownCrowdCoordinator;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.network.SnowtownCrowdNetworking;
import cn.jehorstudio.minetale.narrative.network.DialogueNetworking;
import cn.jehorstudio.minetale.contentpack.ContentPackActiveStackReloadListener;
import cn.jehorstudio.minetale.contentpack.ContentPackActiveProfile;
import cn.jehorstudio.minetale.contentpack.ContentPackRepository;
import cn.jehorstudio.minetale.content.player.soul.SoulRecall;
import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetReloadListener;
import cn.jehorstudio.minetale.dimension.worldgen.asset.importer.StructureAssetImportConfig;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerCommands;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.TemplateMarkerNetworking;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModBiomeSources;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModChunkGenerators;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModStructureProcessors;
import cn.jehorstudio.minetale.dimension.ebott.EbottCommands;
import cn.jehorstudio.minetale.dimension.ebott.mountain.MountainGenerator;
import cn.jehorstudio.minetale.dimension.ebott.transition.TransitionManager;
import cn.jehorstudio.minetale.dimension.ebott.transition.TransitionNetwork;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownLocateGameTests;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownCommands;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(MineTale.MODID)
public class MineTale {
    public static final String MODID = "minetale";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MineTale(IEventBus modEventBus, ModContainer modContainer) {
        Magic.register(modEventBus);
        cn.jehorstudio.minetale.magic.visual.vfx.CameraShakeConfig.register(modContainer);
        modEventBus.addListener(MineTaleDataGenerators::gatherClientData);
        modEventBus.addListener(MineTaleDataGenerators::gatherServerData);
        modEventBus.addListener(TemplateMarkerNetworking::register);
        modEventBus.addListener(BattleNetworking::register);
        modEventBus.addListener(DialogueNetworking::register);
        modEventBus.addListener(SnowtownCrowdNetworking::register);
        modEventBus.addListener(TransitionNetwork::register);
        SnowtownLocateGameTests.register(modEventBus);
        modEventBus.addListener(ContentPackRepository::register);

        NarrativeAttachments.register(modEventBus);
        EbottMountainAttachments.register(modEventBus);
        CommonBlocksRegistry.register(modEventBus);
        TemplateMarkerRegistry.register(modEventBus);
        PlacementBlockerRegistry.register(modEventBus);
        MysteriousCampfireRegistry.register(modEventBus);
        CommonItemsRegistry.register(modEventBus);
        FloweyRegistry.register(modEventBus);
        SoulRegistry.register(modEventBus);
        MineTaleCreativeTab.register(modEventBus);
        ModBiomeSources.register(modEventBus);
        ModChunkGenerators.register(modEventBus);
        ModStructureProcessors.register(modEventBus);
        TransitionTicketTypes.register(modEventBus);


        NeoForge.EVENT_BUS.addListener(TemplateMarkerCommands::register);
        NeoForge.EVENT_BUS.addListener(EbottCommands::register);
        NeoForge.EVENT_BUS.addListener(SnowtownCommands::register);
        NeoForge.EVENT_BUS.addListener(MountainGenerator::onServerStarted);
        NeoForge.EVENT_BUS.addListener(MountainGenerator::onServerStopping);
        TransitionManager.register(NeoForge.EVENT_BUS);
        SnowtownCrowdCoordinator.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(StructureAssetReloadListener::register);
        NeoForge.EVENT_BUS.addListener(ContentPackActiveStackReloadListener::register);
        NeoForge.EVENT_BUS.addListener(ContentPackActiveProfile::verifyOnAboutToStart);
        NeoForge.EVENT_BUS.addListener(ContentPackActiveProfile::saveOnStarted);
        NeoForge.EVENT_BUS.addListener(ContentPackActiveProfile::saveAfterReload);
        NeoForge.EVENT_BUS.addListener(BattleScriptReloadListener::register);
        NeoForge.EVENT_BUS.addListener(NarrativeReloadListener::register);

        StructureAssetImportConfig.register(modContainer);
        modContainer.registerConfig(ModConfig.Type.CLIENT,
                cn.jehorstudio.minetale.voxel.scene.runtime.SceneConfig.SPEC, "minetale-voxel-client.toml");
        modContainer.registerConfig(ModConfig.Type.CLIENT, VisualConfig.SPEC, "minetale-battle-client.toml");
        SoulRecall.register(modEventBus, modContainer);
        modEventBus.addListener(this::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == VisualConfig.SPEC) VisualConfig.apply();
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == VisualConfig.SPEC) VisualConfig.apply();
    }

}
