package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

@Mixin(SectionBufferBuilderPool.class)
public interface PreparedSectionBufferPoolAccessor {
    @Accessor("freeBuffers")
    Queue<SectionBufferBuilderPack> minetale$getFreeBuffers();
}
