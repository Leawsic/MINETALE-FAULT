package cn.jehorstudio.minetale.dimension.worldgen.registry;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.PlacementBlockerCleanupProcessor;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.TemplateMarkerCleanupProcessor;
import cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure.SnowtownStructure;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModStructureProcessors {
    public static final DeferredRegister<StructureProcessorType<?>> STRUCTURE_PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, MineTale.MODID);
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, MineTale.MODID);
    public static final DeferredRegister<StructurePieceType> STRUCTURE_PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, MineTale.MODID);

    public static final DeferredHolder<StructureProcessorType<?>, StructureProcessorType<TemplateMarkerCleanupProcessor>> TEMPLATE_MARKER_CLEANUP =
            STRUCTURE_PROCESSORS.register("template_marker_cleanup", () -> () -> TemplateMarkerCleanupProcessor.CODEC);

    public static final DeferredHolder<StructureProcessorType<?>, StructureProcessorType<PlacementBlockerCleanupProcessor>> PLACEMENT_BLOCKER_CLEANUP =
            STRUCTURE_PROCESSORS.register("placement_blocker_cleanup", () -> () -> PlacementBlockerCleanupProcessor.CODEC);

    public static final DeferredHolder<StructureType<?>, StructureType<SnowtownStructure>> SNOWTOWN =
            STRUCTURE_TYPES.register("snowtown", () -> codecType(SnowtownStructure.CODEC));

    public static final DeferredHolder<StructurePieceType, StructurePieceType> SNOWTOWN_MARKER_PIECE =
            STRUCTURE_PIECES.register("snowtown_marker", () -> SnowtownStructure.MarkerPiece::new);

    public static void register(IEventBus modEventBus) {
        STRUCTURE_PROCESSORS.register(modEventBus);
        STRUCTURE_TYPES.register(modEventBus);
        STRUCTURE_PIECES.register(modEventBus);
    }

    private static <S extends Structure> StructureType<S> codecType(MapCodec<S> codec) {
        return () -> codec;
    }

    private ModStructureProcessors() {
    }
}
