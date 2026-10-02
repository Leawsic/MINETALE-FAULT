package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.dimension.worldgen.registry.ModStructureProcessors;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.util.Optional;

// 用于反序列化旧世界的 vanilla marker start
// 新建筑与 locate 均以 Stage4 accepted lots 为准。
public class SnowtownStructure extends Structure {
    public static final MapCodec<SnowtownStructure> CODEC = simpleCodec(SnowtownStructure::new);

    public SnowtownStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        return Optional.empty();
    }

    @Override
    public StructureType<?> type() {
        return ModStructureProcessors.SNOWTOWN.get();
    }

    public static final class MarkerPiece extends StructurePiece {
        public MarkerPiece(StructurePieceSerializationContext context, CompoundTag tag) {
            super(ModStructureProcessors.SNOWTOWN_MARKER_PIECE.get(), tag);
        }

        @Override
        protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        }

        @Override
        public void postProcess(
                WorldGenLevel level,
                StructureManager structureManager,
                ChunkGenerator generator,
                RandomSource random,
                BoundingBox box,
                ChunkPos chunkPos,
                BlockPos pos
        ) {
        }
    }
}
