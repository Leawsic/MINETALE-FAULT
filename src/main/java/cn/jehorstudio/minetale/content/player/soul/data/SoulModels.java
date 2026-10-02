package cn.jehorstudio.minetale.content.player.soul.data;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.content.player.soul.SoulRegistry;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;

public final class SoulModels {
    private static final ResourceLocation SOUL_ITEM = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID,
            "item/soul"
    );

    public static void register(ItemModelGenerators itemModels) {
        ModelTemplates.FLAT_ITEM.extend()
                .transform(ItemDisplayContext.GUI, transform -> transform.scale(0.75F))
                .build()
                .create(
                        SOUL_ITEM,
                        new TextureMapping().put(TextureSlot.LAYER0, SOUL_ITEM),
                        itemModels.modelOutput
                );
        itemModels.itemModelOutput.accept(
                SoulRegistry.SOUL_ITEM.get(),
                ItemModelUtils.plainModel(SOUL_ITEM)
        );
    }

    private SoulModels() {
    }
}
