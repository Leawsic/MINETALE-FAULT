package cn.jehorstudio.minetale.content.sound;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.resources.ResourceLocation;

// 自定义 Sound Event 的稳定资源 ID；音频文件由资源包提供。
public final class MineTaleSoundEvents {
    public static final ResourceLocation UI_SELECT = id("ui.select");
    public static final ResourceLocation UI_CONFIRM = id("ui.confirm");
    public static final ResourceLocation SOUL_HURT = id("battle.soul_hurt");
    public static final ResourceLocation BATTLE_START = id("battle.start");
    public static final ResourceLocation FLOWEY_VOICE = id("dialogue.flowey_voice");
    public static final ResourceLocation FLOWEY_VOICE_ALT = id("dialogue.flowey_voice_alt");
    public static final ResourceLocation FLOWEY_PAGE_OPEN = id("dialogue.flowey_page_open");
    public static final ResourceLocation BULLET_WHOOSH = id("battle.bullet_whoosh");
    public static final ResourceLocation DEFAULT_BATTLE_MUSIC = id("music.battle_default");

    private MineTaleSoundEvents() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }
}
