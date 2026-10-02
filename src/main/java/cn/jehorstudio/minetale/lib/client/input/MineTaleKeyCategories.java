package cn.jehorstudio.minetale.lib.client.input;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;

// MineTale 客户端功能共用的 KeyMapping 分类注册。
public final class MineTaleKeyCategories {
    public static final KeyMapping.Category BATTLE = category("category");
    public static final KeyMapping.Category DEBUG = category("debug");

    private static KeyMapping.Category category(String path) {
        return new KeyMapping.Category(ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path));
    }

    private MineTaleKeyCategories() {}
}
