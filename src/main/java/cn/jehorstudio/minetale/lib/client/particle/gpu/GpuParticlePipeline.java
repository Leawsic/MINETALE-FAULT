package cn.jehorstudio.minetale.lib.client.particle.gpu;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Objects;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;

// 集中创建 GPU 粒子 mask 管线，生命周期由 GpuParticleRenderer 管理。
final class GpuParticlePipeline {
    private static final ResourceLocation MASK_SHADER = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID,
            "core/particle/mask"
    );

    private GpuParticlePipeline() {
    }

    static RenderPipeline create(ParticleDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return RenderPipeline
            .builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
            .withLocation(pipelineId(definition.id()))
            .withVertexShader(definition.vertexShader())
            .withFragmentShader(MASK_SHADER)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withShaderDefine(
                    "PARTICLE_VERTICES_PER_PARTICLE",
                    ParticleDefinition.VERTICES_PER_PARTICLE
            )
            .withSampler("Sampler0")
            .withSampler("SceneDepthSampler")
            .withUniform(GpuParticleUniform.NAME, UniformType.UNIFORM_BUFFER)
            .withCull(false)
            // SceneDepthSampler 提供只读世界深度。
            .withDepthWrite(true)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();
    }

    private static ResourceLocation pipelineId(ResourceLocation actionId) {
        return ResourceLocation.fromNamespaceAndPath(
                MineTale.MODID,
                "pipeline/gpu_particle/" + actionId.getNamespace() + "/" + actionId.getPath()
        );
    }
}
