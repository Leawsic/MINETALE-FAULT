package cn.jehorstudio.minetale.content.entity.flowey.data;

import cn.jehorstudio.minetale.content.entity.flowey.FloweyRegistry;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class FloweyTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.addItem(FloweyRegistry.FLOWEY_SPAWN_EGG, "Flowey Spawn Egg");
        provider.addEntityType(FloweyRegistry.FLOWEY, "Flowey");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.addItem(FloweyRegistry.FLOWEY_SPAWN_EGG, "小花刷怪蛋");
        provider.addEntityType(FloweyRegistry.FLOWEY, "小花");
    }

    private FloweyTranslations() {
    }
}
