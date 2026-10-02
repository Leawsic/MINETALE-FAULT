package cn.jehorstudio.minetale.battle.presentation.mixin.client;

import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureService;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 环境捕获期间隔离瞬态世界效果，并在世界帧结束前保存最终颜色。
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void minetale$beginEnvironmentWorldFrame(DeltaTracker deltaTracker, CallbackInfo callback) {
        if (EnvironmentCaptureService.INSTANCE.beginWorldFrameAndShouldSuppress(Minecraft.getInstance())) {
            callback.cancel();
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void minetale$captureFinalWorldColor(
            DeltaTracker deltaTracker,
            boolean renderLevel,
            CallbackInfo callback
    ) {
        EnvironmentCaptureService.INSTANCE.captureFinalWorldColor(
                Minecraft.getInstance(),
                renderLevel
        );
    }

    @WrapWithCondition(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"
            )
    )
    private boolean minetale$allowHurtBob(GameRenderer renderer, PoseStack poseStack, float partialTick) {
        return !suppressTransientWorldEffects();
    }

    @WrapWithCondition(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"
            )
    )
    private boolean minetale$allowViewBob(GameRenderer renderer, PoseStack poseStack, float partialTick) {
        return !suppressTransientWorldEffects();
    }

    @WrapWithCondition(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(ZFLnet/minecraft/client/renderer/SubmitNodeCollector;)V"
            )
    )
    private boolean minetale$allowWorldScreenEffect(
            ScreenEffectRenderer renderer,
            boolean sleeping,
            float partialTick,
            SubmitNodeCollector submitNodes
    ) {
        return !suppressTransientWorldEffects();
    }

    @WrapWithCondition(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;render3dCrosshair(Lnet/minecraft/client/Camera;)V"
            )
    )
    private boolean minetale$allowDebugCrosshair(DebugScreenOverlay overlay, Camera camera) {
        return !suppressTransientWorldEffects();
    }

    @ModifyExpressionValue(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Math;max(FF)F"
            )
    )
    private float minetale$suppressPortalAndNauseaDistortion(float original) {
        return suppressTransientWorldEffects() ? 0.0F : original;
    }

    private static boolean suppressTransientWorldEffects() {
        return EnvironmentCaptureService.INSTANCE.suppressTransientWorldEffects();
    }
}
