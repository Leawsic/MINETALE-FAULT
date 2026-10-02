package cn.jehorstudio.minetale.dimension.worldgen.mixin;

import cn.jehorstudio.minetale.dimension.ebott.mountain.MountainGenerator;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @Inject(method = "generateCarvers", at = @At("RETURN"))
    private static void minetale$captureOriginalSurface(
            WorldGenContext context,
            ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback
    ) {
        MountainGenerator.captureOriginalSurface(context.level(), chunk);
    }

    @Redirect(
            method = "generateFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V"
            )
    )
    private static void minetale$wrapBiomeDecoration(
            ChunkGenerator generator,
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structureManager
    ) {
        MountainGenerator.applyBiomeDecoration(generator, level, chunk, structureManager);
    }
}
