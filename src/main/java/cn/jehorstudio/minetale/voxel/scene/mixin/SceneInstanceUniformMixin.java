package cn.jehorstudio.minetale.voxel.scene.mixin;

import cn.jehorstudio.minetale.voxel.scene.runtime.SceneInstanceShader;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(GlCommandEncoder.class)
abstract class SceneInstanceUniformMixin {
    @Shadow private GlProgram lastProgram;
    @Shadow private RenderPipeline lastPipeline;

    @Inject(method = "trySetup", at = @At("RETURN"))
    private void minetale$instances(
            GlRenderPass pass,
            Collection<String> uniforms,
            CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ() && lastProgram != null && lastPipeline != null)
            SceneInstanceShader.setup(lastProgram, lastPipeline);
    }
}
