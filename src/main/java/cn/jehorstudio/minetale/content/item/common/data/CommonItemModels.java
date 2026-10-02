package cn.jehorstudio.minetale.content.item.common.data;

import cn.jehorstudio.minetale.content.item.common.CommonItemsRegistry;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.ModelTemplates;

public final class CommonItemModels {
    public static void register(ItemModelGenerators itemModels) {
        itemModels.generateFlatItem(CommonItemsRegistry.MONSTER_CANDY.get(), ModelTemplates.FLAT_ITEM);
        itemModels.generateFlatItem(CommonItemsRegistry.BONE_FRAGMENT.get(), ModelTemplates.FLAT_ITEM);
    }

    private CommonItemModels() {}
}
