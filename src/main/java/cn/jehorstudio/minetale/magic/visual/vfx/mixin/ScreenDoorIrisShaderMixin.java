package cn.jehorstudio.minetale.magic.visual.vfx.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

// Iris 1.9.7：保留光影包的实体着色，只在最终链接前加入按绘制管线开关的屏门裁切。
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
abstract class ScreenDoorIrisShaderMixin {
    @ModifyArgs(method = {"create", "createShadow", "createFallback", "createFallbackShadow"},
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator;link(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/mojang/blaze3d/vertex/VertexFormat;Z)Lnet/irisshaders/iris/pipeline/programs/PartialShader;"))
    private static void minetale$screenDoor(Args args) {
        String vertex = args.get(1), fragment = args.get(5);
        if (vertex == null || fragment == null || !vertex.contains("in vec4 iris_Color;")) return;
        if (args.get(2) != null || args.get(3) != null || args.get(4) != null) return;
        vertex = vertex.replaceAll("\\biris_Color\\b", "minetale_Color")
                .replace("in vec4 minetale_Color;", "in vec4 iris_Color; vec4 minetale_Color; uniform int minetale_ScreenDoor; out float minetale_Coverage;")
                .replaceFirst("void\\s+main\\s*\\(", "void minetale_originalMain(");
        vertex += "\nvoid main() { minetale_Coverage = iris_Color.a; minetale_Color = iris_Color; if (minetale_ScreenDoor != 0) minetale_Color.a = 1.0; minetale_originalMain(); }\n";
        fragment = fragment.replaceFirst("void\\s+main\\s*\\(",
                "uniform int minetale_ScreenDoor; in float minetale_Coverage; void minetale_originalMain(");
        try (var reader = net.minecraft.client.Minecraft.getInstance().getResourceManager()
                .openAsReader(cn.jehorstudio.minetale.magic.Magic.id("shaders/include/screen_door.glsl"))) {
            String rules = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            fragment += "\n" + rules + "\nvoid main() { if (minetale_ScreenDoor != 0) minetale_screenDoor(minetale_Coverage); minetale_originalMain(); }\n";
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("无法加载共用屏门 Shader", failure);
        }
        args.set(1, vertex);
        args.set(5, fragment);
    }
}
