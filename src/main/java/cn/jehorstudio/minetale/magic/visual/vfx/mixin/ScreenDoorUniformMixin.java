package cn.jehorstudio.minetale.magic.visual.vfx.mixin;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(GlCommandEncoder.class)
abstract class ScreenDoorUniformMixin {
    @Shadow private GlProgram lastProgram;
    @Shadow private RenderPipeline lastPipeline;
    // 以程序对象而非可重用的 GL id 缓存，资源重载后旧程序可释放。
    @Unique private final Map<GlProgram, Integer> minetale$locations = new WeakHashMap<>();

    @Inject(method = "trySetup", at = @At("RETURN"))
    private void minetale$setScreenDoor(GlRenderPass pass, Collection<String> uniforms, CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValueZ() || lastProgram == null || lastPipeline == null) return;
        int location = minetale$locations.computeIfAbsent(lastProgram,
                program -> GL20.glGetUniformLocation(program.getProgramId(), "minetale_ScreenDoor"));
        if (location < 0) return;
        var id = lastPipeline.getLocation();
        boolean screenDoor = id.getNamespace().equals("minetale") && id.getPath().startsWith("pipeline/magic/screen_door");
        // Iris 的实体程序会被其他实体复用，每次绘制均必须恢复开关，不能污染后续实体。
        GL20.glUniform1i(location, screenDoor ? 1 : 0);
    }
}
