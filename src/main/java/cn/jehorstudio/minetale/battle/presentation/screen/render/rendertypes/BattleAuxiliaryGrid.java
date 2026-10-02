package cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;

// Rendered 模式在单次 draw 中生成网格线与填充面，并以标准 alpha blend 合入场景。
public final class BattleAuxiliaryGrid {
    public static final RenderPipeline RENDERED_PIPELINE = pipeline(
            "battle_auxiliary_grid_rendered", "battle/primitive/rendered/auxiliary_grid");

    private BattleAuxiliaryGrid() {
    }

    private static RenderPipeline pipeline(String location, String shader) {
        return RenderPipeline.builder()
                .withLocation(id("pipeline/" + location))
                .withVertexShader(id("core/" + shader))
                .withFragmentShader(id("core/" + shader))
                .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
                .withSampler("ShadowSampler")
                .withUniform("LightingUniform", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP)
                .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
                .withDepthWrite(false)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .build();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }
}
