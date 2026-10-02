package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging;

import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

@Mixin(SectionRenderDispatcher.class)
public interface PreparedSectionDispatcherAccessor {
    @Accessor("toUpload")
    Queue<Runnable> minetale$getPendingUploads();

    @Accessor("toClose")
    Queue<SectionMesh> minetale$getPendingCloses();

    @Mutable
    @Accessor("bufferPool")
    void minetale$setBufferPool(SectionBufferBuilderPool pool);
}
