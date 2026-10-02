package cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.data;

import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerRegistry;
import net.neoforged.neoforge.common.data.LanguageProvider;

// 生成 Placement Blocker 的本地化名称。
public final class PlacementBlockerTranslations {
    public static void addEnUs(LanguageProvider provider) {
        provider.addBlock(PlacementBlockerRegistry.PLACEMENT_BLOCKER, "Placement Blocker");
    }

    public static void addZhCn(LanguageProvider provider) {
        provider.addBlock(PlacementBlockerRegistry.PLACEMENT_BLOCKER, "阻隔方块");
    }

    private PlacementBlockerTranslations() {}
}
