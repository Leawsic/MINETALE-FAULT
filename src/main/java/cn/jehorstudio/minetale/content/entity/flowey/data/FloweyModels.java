package cn.jehorstudio.minetale.content.entity.flowey.data;

import cn.jehorstudio.minetale.content.entity.flowey.FloweyRegistry;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.resources.ResourceLocation;

public final class FloweyModels {
    private static final ResourceLocation DANDELION_ITEM =
            ResourceLocation.withDefaultNamespace("item/dandelion");

    public static void register(ItemModelGenerators itemModels) {
        itemModels.itemModelOutput.accept(
                FloweyRegistry.FLOWEY_SPAWN_EGG.get(),
                ItemModelUtils.plainModel(DANDELION_ITEM)
        );
    }

    private FloweyModels() {
    }
}
