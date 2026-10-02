package cn.jehorstudio.minetale.content.player.soul;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class SoulRegistry {
    private static final DeferredRegister.Entities ENTITY_TYPES =
            DeferredRegister.createEntities(MineTale.MODID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MineTale.MODID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MineTale.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<SoulEntity>> SOUL =
            ENTITY_TYPES.registerEntityType(
                    "soul",
                    SoulEntity::new,
                    MobCategory.MISC,
                    builder -> builder
                            .sized(0.6F, 0.6F)
                            .eyeHeight(0.3F)
                            .clientTrackingRange(16)
                            .updateInterval(SoulEntity.NETWORK_UPDATE_INTERVAL_TICKS)
                            .setShouldReceiveVelocityUpdates(false)
                            .fireImmune()
                            .noLootTable()
                            .noSummon()
            );

    public static final DeferredItem<Item> SOUL_ITEM =
            ITEMS.registerItem(
                    "soul",
                    properties -> new Item(properties.stacksTo(1).rarity(Rarity.EPIC))
            );

    public static final Supplier<AttachmentType<Soul>> SOUL_ATTACHMENT =
            ATTACHMENTS.register(
                    "soul",
                    () -> AttachmentType.builder(Soul::create)
                            // ITEM 是默认事实；省略它可避免全可选 codec 写出空 Attachment 对象。
                            .serialize(Soul.CODEC, soul -> soul.state() != Soul.State.ITEM)
                            .copyOnDeath()
                            .build()
            );

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        ITEMS.register(modEventBus);
        ATTACHMENTS.register(modEventBus);
    }

    private SoulRegistry() {
    }
}
