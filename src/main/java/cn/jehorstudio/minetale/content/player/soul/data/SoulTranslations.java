package cn.jehorstudio.minetale.content.player.soul.data;

import cn.jehorstudio.minetale.content.player.soul.SoulRegistry;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class SoulTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.addItem(SoulRegistry.SOUL_ITEM, "Soul");
        provider.addEntityType(SoulRegistry.SOUL, "Soul");
        provider.add("minetale.configuration.interaction", "Interaction");
        provider.add("minetale.configuration.soul_recall_hold_seconds", "Soul Recall Hold Duration");
        provider.add("minetale.configuration.soul_recall_hold_seconds.tooltip",
                "How long to hold Use Item/Place Block with both hands empty before recalling the Soul.");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.addItem(SoulRegistry.SOUL_ITEM, "灵魂");
        provider.addEntityType(SoulRegistry.SOUL, "灵魂");
        provider.add("minetale.configuration.interaction", "交互");
        provider.add("minetale.configuration.soul_recall_hold_seconds", "灵魂召回长按时长");
        provider.add("minetale.configuration.soul_recall_hold_seconds.tooltip",
                "主副手皆为空时，长按“使用物品/放置方块”键触发灵魂召回所需的时间。");
    }

    private SoulTranslations() {
    }
}
