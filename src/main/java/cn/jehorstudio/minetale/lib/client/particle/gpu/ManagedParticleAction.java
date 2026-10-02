package cn.jehorstudio.minetale.lib.client.particle.gpu;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.context.ContextKey;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

// 将单个 ParticleAction 的生命周期、pipeline 与冻结帧绑定为一个受管单元。
final class ManagedParticleAction {
    private final ParticleAction action;
    private final ParticleDefinition definition;
    private final ContextKey<ParticleFrame> frameKey;
    private final RenderPipeline pipeline;
    @Nullable
    private final ParticleAnchorRenderer anchorRenderer;

    ManagedParticleAction(ParticleAction action) {
        this.action = Objects.requireNonNull(action, "action");
        this.definition = Objects.requireNonNull(action.definition(), "action.definition()");
        this.frameKey = new ContextKey<>(ResourceLocation.fromNamespaceAndPath(
                this.definition.id().getNamespace(),
                "particle_action/" + this.definition.id().getPath()
        ));
        this.pipeline = GpuParticlePipeline.create(this.definition);
        this.anchorRenderer = this.definition.anchorModel() == null
                ? null
                : new ParticleAnchorRenderer(this.definition.anchorModel());
    }

    ResourceLocation id() {
        return this.definition.id();
    }

    void registerPipeline(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(this.pipeline);
    }

    boolean start(Minecraft minecraft) {
        return this.action.isRunning() || this.action.start(minecraft);
    }

    boolean toggle(Minecraft minecraft) {
        if (this.action.isRunning()) {
            this.action.stop();
            return false;
        }
        return this.action.start(minecraft);
    }

    void stop() {
        this.action.stop();
    }

    boolean isRunning() {
        return this.action.isRunning();
    }

    void tick(Minecraft minecraft) {
        this.action.tick(minecraft);
    }

    void extractFrame(ExtractLevelRenderStateEvent event) {
        if (!this.action.isRunning()) {
            return;
        }
        ParticleFrame frame = this.action.extractFrame(event.getLevel(), event.getCamera());
        if (frame != null) {
            event.getRenderState().setRenderData(this.frameKey, frame);
        }
    }

    void renderFrame(
            RenderLevelStageEvent.AfterEntities event,
            GpuParticleRenderer renderer,
            Matrix4f projectionMatrix,
            boolean hasProjection
    ) {
        ParticleFrame frame = event.getLevelRenderState().getRenderData(this.frameKey);
        if (frame == null) {
            return;
        }
        if (this.anchorRenderer != null) {
            this.anchorRenderer.render(event, frame);
        }
        if (hasProjection) {
            renderer.queue(event, this.definition, this.pipeline, frame, projectionMatrix);
        }
    }
}
