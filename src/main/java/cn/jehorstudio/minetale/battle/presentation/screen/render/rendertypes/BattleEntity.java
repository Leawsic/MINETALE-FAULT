package cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public final class BattleEntity {
    public static final RenderPipeline UNMODIFIED_CUTOUT_PIPELINE = entityPipeline(
            "battle_entity_unmodified_cutout",
            "core/battle/entity/unmodified/battle_scene",
            "core/battle/primitive/texture",
            false,
            false,
            false
    );
    public static final RenderPipeline UNMODIFIED_TRANSLUCENT_PIPELINE = entityPipeline(
            "battle_entity_unmodified_translucent",
            "core/battle/entity/unmodified/battle_scene",
            "core/battle/primitive/texture",
            true,
            false,
            false
    );
    public static final RenderPipeline RENDERED_CUTOUT_PIPELINE = entityPipeline(
            "battle_entity_rendered_cutout",
            "core/battle/entity/rendered/battle_scene",
            "core/battle/entity/rendered/material",
            false,
            false,
            true,
            true
    );
    public static final RenderPipeline RENDERED_TRANSLUCENT_PIPELINE = entityPipeline(
            "battle_entity_rendered_translucent",
            "core/battle/entity/rendered/battle_scene",
            "core/battle/entity/rendered/material",
            true,
            false,
            true,
            true
    );
    public static final RenderPipeline RENDERED_SPECULAR_CUTOUT_PIPELINE = entityPipeline(
            "battle_entity_rendered_specular_cutout",
            "core/battle/entity/rendered/battle_scene",
            "core/battle/entity/rendered/material_specular",
            false,
            true,
            true,
            true
    );
    public static final RenderPipeline RENDERED_SPECULAR_TRANSLUCENT_PIPELINE = entityPipeline(
            "battle_entity_rendered_specular_translucent",
            "core/battle/entity/rendered/battle_scene",
            "core/battle/entity/rendered/material_specular",
            true,
            true,
            true,
            true
    );
    // 将模型 *_s.png 的 B 通道转换为发光颜色。
    public static final RenderPipeline EMISSIVE_BLOOM_SOURCE_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_entity_emissive_bloom_source"))
            .withVertexShader(id("core/battle/entity/rendered/battle_scene"))
            .withFragmentShader(id("core/battle/entity/rendered/emissive_bloom_source"))
            .withSampler("Sampler0")
            .withSampler("SpecularSampler")
             .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
             .withUniform("LightingUniform", UniformType.UNIFORM_BUFFER)
             .withUniform("PostProcessingUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
             .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .withCull(false)
            .build();
    // Sprite、粒子及纹理 quad 的 *_s.b Bloom 源。
    public static final RenderPipeline EMISSIVE_QUAD_BLOOM_SOURCE_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_quad_emissive_bloom_source"))
            .withVertexShader(id("core/battle/primitive/draw"))
            .withFragmentShader(id("core/battle/entity/rendered/emissive_bloom_source"))
            .withSampler("Sampler0")
            .withSampler("SpecularSampler")
             .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
             .withUniform("PostProcessingUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.TRIANGLE_STRIP)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
             .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .withCull(false)
            .build();
    // 挤出图片只接收全局/点阴影与 Soul 点光，并单独压暗轮廓侧面。
    public static final RenderPipeline RENDERED_EXTRUDED_PIPELINE = entityPipeline(
            "battle_entity_rendered_extruded",
            "core/battle/entity/rendered/battle_scene",
            "core/battle/entity/rendered/extruded",
            false,
            false,
            true
    );
    public static final RenderPipeline SHADOW_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/battle_entity_rendered_shadow"))
            .withVertexShader(id("core/battle/entity/rendered/shadow"))
            .withFragmentShader(id("core/battle/primitive/texture"))
            .withSampler("Sampler0")
            .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
            .withUniform("LightingUniform", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withColorWrite(false)
            .withCull(false)
            .withDepthBias(1.0F, 1.0F)
            .build();

    // MultiBufferSource adapter 固定使用 Unmodified 行为。
    public static final RenderPipeline UNMODIFIED_RENDER_TYPE_PIPELINE = RenderPipeline.builder(
                    RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(id("pipeline/battle_entity_unmodified_render_type"))
            .withVertexShader(id("core/battle/entity/unmodified/render_type"))
            .withFragmentShader(id("core/battle/primitive/texture"))
            .withSampler("Sampler0")
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withDepthWrite(true)
            .withCull(false)
            .build();

    /** @deprecated 使用带明确模式的 {@link #pipelines(VisualConfig.BattleRenderMode)}。 */
    @Deprecated public static final RenderPipeline CUTOUT_PIPELINE = UNMODIFIED_CUTOUT_PIPELINE;
    /** @deprecated 使用带明确模式的 {@link #pipelines(VisualConfig.BattleRenderMode)}。 */
    @Deprecated public static final RenderPipeline TRANSLUCENT_PIPELINE = UNMODIFIED_TRANSLUCENT_PIPELINE;
    /** @deprecated 旧 RenderType adapter 永远属于 Unmodified。 */
    @Deprecated public static final RenderPipeline RENDER_TYPE_PIPELINE = UNMODIFIED_RENDER_TYPE_PIPELINE;

    private static final Pipelines UNMODIFIED = new Pipelines(
            UNMODIFIED_CUTOUT_PIPELINE, UNMODIFIED_TRANSLUCENT_PIPELINE, null, null);
    private static final Pipelines RENDERED = new Pipelines(
            RENDERED_CUTOUT_PIPELINE, RENDERED_TRANSLUCENT_PIPELINE,
            RENDERED_SPECULAR_CUTOUT_PIPELINE, RENDERED_SPECULAR_TRANSLUCENT_PIPELINE);
    private static final Map<ResourceLocation, RenderType> TYPES = new HashMap<>();

    private BattleEntity() {
    }

    public static Pipelines pipelines(VisualConfig.BattleRenderMode mode) {
        return mode == VisualConfig.BattleRenderMode.RENDERED ? RENDERED : UNMODIFIED;
    }

    public static RenderType renderType(ResourceLocation texture) {
        return TYPES.computeIfAbsent(texture, BattleEntity::createUnmodifiedRenderType);
    }

    private static RenderPipeline entityPipeline(
            String location,
            String vertexShader,
            String fragmentShader,
            boolean translucent,
            boolean specularMap,
            boolean rendered
    ) {
        return entityPipeline(
                location,
                vertexShader,
                fragmentShader,
                translucent,
                specularMap,
                rendered,
                false
        );
    }

    private static RenderPipeline entityPipeline(
            String location,
            String vertexShader,
            String fragmentShader,
            boolean translucent,
            boolean specularMap,
            boolean rendered,
            boolean environmentLit
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(id("pipeline/" + location))
                .withVertexShader(id(vertexShader))
                .withFragmentShader(id(fragmentShader))
                .withSampler("Sampler0")
                .withUniform("DrawUniform", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, VertexFormat.Mode.QUADS)
                .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
                .withDepthWrite(!translucent)
                .withCull(false);
        if (rendered) {
            builder.withSampler("ShadowSampler")
                    .withSampler("PointShadowAtlas")
                    .withUniform("LightingUniform", UniformType.UNIFORM_BUFFER)
                    .withUniform("SoulPointLightUniform", UniformType.UNIFORM_BUFFER);
        }
        if (environmentLit) {
            builder.withSampler("EnvironmentFieldSampler")
                    .withUniform("EnvironmentFieldUniform", UniformType.UNIFORM_BUFFER)
                    .withUniform("EnvironmentLightUniform", UniformType.UNIFORM_BUFFER);
        }
        if (specularMap) {
            builder.withSampler("SpecularSampler");
        }
        if (translucent) {
            builder.withBlend(BlendFunction.TRANSLUCENT);
        }
        return builder.build();
    }

    private static RenderType createUnmodifiedRenderType(ResourceLocation texture) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false))
                .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                .setOverlayState(RenderStateShard.NO_OVERLAY)
                .createCompositeState(false);
        return RenderType.create("minetale_battle_entity_unmodified", RenderType.SMALL_BUFFER_SIZE,
                false, false, UNMODIFIED_RENDER_TYPE_PIPELINE, state);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }

    public record Pipelines(
            RenderPipeline cutout,
            RenderPipeline translucent,
            RenderPipeline specularCutout,
            RenderPipeline specularTranslucent
    ) {
        public RenderPipeline select(boolean translucent, boolean hasSpecularMap) {
            if (hasSpecularMap && this.specularCutout != null) {
                return translucent ? this.specularTranslucent : this.specularCutout;
            }
            return translucent ? this.translucent : this.cutout;
        }
    }
}
