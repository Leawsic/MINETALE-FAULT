package cn.jehorstudio.minetale.content.entity.monster_npc.data;

import net.neoforged.neoforge.common.data.LanguageProvider;

public final class MonsterNpcTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.add("entity.minetale.monster_npc", "Monster Resident");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.add("entity.minetale.monster_npc", "怪物居民");
    }

    private MonsterNpcTranslations() {}
}
