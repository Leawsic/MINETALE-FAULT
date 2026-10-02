package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.staging;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftRendererAccessor {
    @Mutable
    @Final
    @Accessor("levelRenderer")
    void minetale$setLevelRenderer(LevelRenderer renderer);
}
