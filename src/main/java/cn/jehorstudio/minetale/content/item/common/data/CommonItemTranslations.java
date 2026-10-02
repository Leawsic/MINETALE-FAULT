package cn.jehorstudio.minetale.content.item.common.data;

import cn.jehorstudio.minetale.content.item.common.CommonItemsRegistry;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class CommonItemTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.addItem(CommonItemsRegistry.MONSTER_CANDY, "Monster Candy");
        provider.addItem(CommonItemsRegistry.BONE_FRAGMENT, "Bone Fragment");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.addItem(CommonItemsRegistry.MONSTER_CANDY, "怪物糖果");
        provider.addItem(CommonItemsRegistry.BONE_FRAGMENT, "破碎骨片");
    }

    private CommonItemTranslations() {}
}
