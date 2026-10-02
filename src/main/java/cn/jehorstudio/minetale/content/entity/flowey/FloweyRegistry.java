package cn.jehorstudio.minetale.content.entity.flowey;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class FloweyRegistry {
    private static final DeferredRegister.Entities ENTITY_TYPES =
            DeferredRegister.createEntities(MineTale.MODID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MineTale.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<Flowey>> FLOWEY =
            ENTITY_TYPES.registerEntityType(
                    "flowey",
                    Flowey::new,
                    MobCategory.CREATURE,
                    builder -> builder
                            .sized(1.3F, 1.25F)
                            .eyeHeight(1.08F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .noLootTable()
            );

    public static final DeferredItem<FloweySpawnEggItem> FLOWEY_SPAWN_EGG =
            ITEMS.registerItem(
                    "flowey_spawn_egg",
                    properties -> new FloweySpawnEggItem(properties.spawnEgg(FLOWEY.get()))
            );

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(FloweyRegistry::registerAttributes);
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(FLOWEY.get(), Flowey.createAttributes().build());
    }

    private FloweyRegistry() {
    }
}
