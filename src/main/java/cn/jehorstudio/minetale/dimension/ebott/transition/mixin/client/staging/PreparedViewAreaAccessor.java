package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// 让预编译器读取 prepared ViewArea 的既有 Section
@Mixin(ViewArea.class)
public interface PreparedViewAreaAccessor {
    @Invoker("getRenderSection")
    SectionRenderDispatcher.RenderSection minetale$getRenderSection(long sectionNode);
}
