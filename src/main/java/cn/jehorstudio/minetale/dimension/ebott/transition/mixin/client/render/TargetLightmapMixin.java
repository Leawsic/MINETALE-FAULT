package cn.jehorstudio.minetale.dimension.ebott.transition.mixin.client.render;

import cn.jehorstudio.minetale.dimension.ebott.transition.client.render.TargetDimensionRender;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// 仅在目标 lightmap 调用链中把 Minecraft.level 替换为 prepared level。
@Mixin(LightTexture.class)
abstract class TargetLightmapMixin {
    @ModifyExpressionValue(
            method = "updateLightTexture",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"
            )
    )
    private ClientLevel minetale$useDestinationLightLevel(ClientLevel original) {
        return TargetDimensionRender.lightLevel(original);
    }
}
