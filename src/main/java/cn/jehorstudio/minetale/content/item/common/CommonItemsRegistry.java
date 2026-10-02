package cn.jehorstudio.minetale.content.item.common;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CommonItemsRegistry {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MineTale.MODID);

    public static final DeferredItem<Item> MONSTER_CANDY = ITEMS.registerSimpleItem(
            "monster_candy",
            () -> new Item.Properties()
                    .food(new FoodProperties.Builder()
                            .nutrition(4)
                            .saturationModifier(0.3F)
                            .alwaysEdible()
                            .build())
                    .stacksTo(16)
    );

    public static final DeferredItem<Item> BONE_FRAGMENT =
            ITEMS.registerSimpleItem("bone_fragment");

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private CommonItemsRegistry() {}
}
