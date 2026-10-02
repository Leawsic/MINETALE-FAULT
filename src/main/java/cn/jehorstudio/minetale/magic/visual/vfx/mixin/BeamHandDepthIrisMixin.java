package cn.jehorstudio.minetale.magic.visual.vfx.mixin;

import cn.jehorstudio.minetale.magic.visual.vfx.MagicBeamRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
abstract class BeamHandDepthIrisMixin {
    // 位于 canRender / isAnyHandTranslucent 之后，只有真实绘制透明手持物时才复制。
    @Inject(method = "renderTranslucent", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;backupProjectionMatrix()V"))
    private void minetale$beforeTranslucentHand(CallbackInfo callback) {
        MagicBeamRenderer.captureTranslucentHandDepth();
    }
}
