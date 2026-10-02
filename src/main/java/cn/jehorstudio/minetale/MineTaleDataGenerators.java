package cn.jehorstudio.minetale;

import cn.jehorstudio.minetale.battle.presentation.data.BattlePresentationTranslations;
import cn.jehorstudio.minetale.content.block.common.data.CommonBlockModels;
import cn.jehorstudio.minetale.content.block.common.data.CommonBlockTranslations;
import cn.jehorstudio.minetale.content.entity.flowey.data.FloweyModels;
import cn.jehorstudio.minetale.content.entity.flowey.data.FloweyTranslations;
import cn.jehorstudio.minetale.content.entity.monster_npc.data.MonsterNpcTranslations;
import cn.jehorstudio.minetale.content.item.common.data.CommonItemModels;
import cn.jehorstudio.minetale.content.item.common.data.CommonItemTranslations;
import cn.jehorstudio.minetale.content.player.soul.data.SoulModels;
import cn.jehorstudio.minetale.content.player.soul.data.SoulTranslations;
import cn.jehorstudio.minetale.contentpack.data.ContentPackTranslations;
import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.data.MysteriousCampfireModels;
import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.data.MysteriousCampfireTranslations;
import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.data.PlacementBlockerModels;
import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.data.PlacementBlockerTranslations;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.data.TemplateMarkerModels;
import cn.jehorstudio.minetale.dimension.worldgen.asset.marker.data.TemplateMarkerTranslations;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.data.SnowdinWorldgenData;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

// 组合并注册项目全部 DataProvider，具体数据由所属模块生成。
public final class MineTaleDataGenerators {
    public static void gatherClientData(GatherDataEvent.Client event) {
        event.createDatapackRegistryObjects(SnowdinWorldgenData.BUILDER);
        event.createProvider(MineTaleZhCnLanguageProvider::new);
        event.createProvider(MineTaleEnUsLanguageProvider::new);
        event.createProvider(MineTaleModelProvider::new);
    }

    public static void gatherServerData(GatherDataEvent.Server event) {
        event.createDatapackRegistryObjects(SnowdinWorldgenData.BUILDER);
    }

    private static final class MineTaleModelProvider extends ModelProvider {
        private MineTaleModelProvider(PackOutput output) {
            super(output, MineTale.MODID);
        }

        @Override
        protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
            CommonItemModels.register(itemModels);
            SoulModels.register(itemModels);
            FloweyModels.register(itemModels);
            CommonBlockModels.register(blockModels);
            TemplateMarkerModels.register(blockModels);
            PlacementBlockerModels.register(blockModels);
            MysteriousCampfireModels.register(blockModels);
        }
    }

    private static final class MineTaleEnUsLanguageProvider extends LanguageProvider {
        private MineTaleEnUsLanguageProvider(PackOutput output) {
            super(output, MineTale.MODID, "en_us");
        }

        @Override
        protected void addTranslations() {
            add("itemGroup.minetale", "MineTale");
            add("key.category.minetale.debug", "MineTale Debug");
            CommonItemTranslations.addEnUs(this);
            BattlePresentationTranslations.addEnUs(this);
            ContentPackTranslations.addEnUs(this);
            SoulTranslations.addEnUs(this);
            cn.jehorstudio.minetale.magic.data.MagicTranslations.addEnUs(this);
            cn.jehorstudio.minetale.voxel.scene.runtime.SceneConfig.addEnUs(this::add);
            FloweyTranslations.addEnUs(this);
            MonsterNpcTranslations.addEnUs(this);
            CommonBlockTranslations.addEnUs(this);
            TemplateMarkerTranslations.addEnUs(this);
            PlacementBlockerTranslations.addEnUs(this);
            MysteriousCampfireTranslations.addEnUs(this);
        }
    }

    private static final class MineTaleZhCnLanguageProvider extends LanguageProvider {
        private MineTaleZhCnLanguageProvider(PackOutput output) {
            super(output, MineTale.MODID, "zh_cn");
        }

        @Override
        protected void addTranslations() {
            add("itemGroup.minetale", "MineTale");
            add("key.category.minetale.debug", "MINETALE 调试");
            CommonItemTranslations.addZhCn(this);
            BattlePresentationTranslations.addZhCn(this);
            ContentPackTranslations.addZhCn(this);
            SoulTranslations.addZhCn(this);
            cn.jehorstudio.minetale.magic.data.MagicTranslations.addZhCn(this);
            cn.jehorstudio.minetale.voxel.scene.runtime.SceneConfig.addZhCn(this::add);
            FloweyTranslations.addZhCn(this);
            MonsterNpcTranslations.addZhCn(this);
            CommonBlockTranslations.addZhCn(this);
            TemplateMarkerTranslations.addZhCn(this);
            PlacementBlockerTranslations.addZhCn(this);
            MysteriousCampfireTranslations.addZhCn(this);
        }
    }

    private MineTaleDataGenerators() {}
}
