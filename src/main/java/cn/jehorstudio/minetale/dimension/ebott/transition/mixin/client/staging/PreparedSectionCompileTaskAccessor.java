package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

// 暴露任务完成状态，使预编译器只在异步工作结束后回收槽位。
@Mixin(SectionRenderDispatcher.RenderSection.CompileTask.class)
public interface PreparedSectionCompileTaskAccessor {
    @Accessor("isCompleted")
    AtomicBoolean minetale$getCompleted();
}
