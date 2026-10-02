package cn.jehorstudio.minetale.dimension.ebott.entrance.campfire;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MysteriousCampfireRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MineTale.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MineTale.MODID);
    private static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, MineTale.MODID);

    public static final DeferredBlock<MysteriousCampfireBlock> MYSTERIOUS_CAMPFIRE =
            BLOCKS.registerBlock(
                    "mysterious_campfire",
                    MysteriousCampfireBlock::new,
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.CAMPFIRE)
                            .overrideLootTable(Blocks.CAMPFIRE.getLootTable())
            );

    // 方块物品仅供命令获取
    public static final DeferredItem<BlockItem> MYSTERIOUS_CAMPFIRE_ITEM =
            ITEMS.registerSimpleBlockItem("mysterious_campfire", MYSTERIOUS_CAMPFIRE);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> COSY_SMOKE =
            PARTICLE_TYPES.register(
                    "mysterious_campfire_cosy_smoke",
                    () -> new SimpleParticleType(true)
            );

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SIGNAL_SMOKE =
            PARTICLE_TYPES.register(
                    "mysterious_campfire_signal_smoke",
                    () -> new SimpleParticleType(true)
            );

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        PARTICLE_TYPES.register(modEventBus);
        modEventBus.addListener(MysteriousCampfireRegistry::addCampfireBlockEntity);
    }

    private static void addCampfireBlockEntity(BlockEntityTypeAddBlocksEvent event) {
        event.modify(BlockEntityType.CAMPFIRE, MYSTERIOUS_CAMPFIRE.get());
    }

    private MysteriousCampfireRegistry() {}
}
