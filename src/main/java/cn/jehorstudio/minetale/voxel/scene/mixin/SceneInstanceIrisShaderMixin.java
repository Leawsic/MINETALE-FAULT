package cn.jehorstudio.minetale.voxel.scene.mixin;

import cn.jehorstudio.minetale.voxel.scene.runtime.SceneInstanceShader;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
abstract class SceneInstanceIrisShaderMixin {
    @ModifyArgs(
            method = {"create", "createFallback"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator;link(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;Z)Lnet/irisshaders/iris/pipeline/programs/PartialShader;"))
    private static void minetale$instances(Args args) {
        args.set(1, SceneInstanceShader.patch(args.get(1)));
    }
}
