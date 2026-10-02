package cn.jehorstudio.minetale.content.player.soul.mixin.common;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 暴露双箱半边，使 Soul 出口落在实际持有它的容器
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {
    @Accessor("container1")
    Container minetale$getFirstContainer();

    @Accessor("container2")
    Container minetale$getSecondContainer();
}
