package cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.data;

import cn.jehorstudio.minetale.dimension.ebott.entrance.campfire.MysteriousCampfireRegistry;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class MysteriousCampfireTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.addBlock(MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE, "Mysterious Campfire");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.addBlock(MysteriousCampfireRegistry.MYSTERIOUS_CAMPFIRE, "谜之营火");
    }

    private MysteriousCampfireTranslations() {}
}
