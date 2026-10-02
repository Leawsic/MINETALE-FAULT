package cn.jehorstudio.minetale.battle.presentation.screen.render;

import cn.jehorstudio.minetale.battle.logic.actor.ActorAppearance;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureSnapshot;
import cn.jehorstudio.minetale.battle.presentation.screen.render.volume.VolumetricShadows;
import cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes.BattleAuxiliaryGrid;
import cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes.BattleEntity;
import cn.jehorstudio.minetale.battle.presentation.screen.render.rendertypes.BattleFrame;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.DrawUniform;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.SoulPointLightUniform;
import cn.jehorstudio.minetale.battle.presentation.states.ScreenEffectSnapshot;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.LightingUniform;
import cn.jehorstudio.minetale.lib.ObjLoader;
import cn.jehorstudio.minetale.lib.ObjModels;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.io.IOException;

public final class Renderer extends PictureInPictureRenderer<Renderer.State> {
    private static final int MAX_CACHED_BATTLE_FRAMES = 16;

    public record State(
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds,
            UUID battleId,
            BattleScene.Snapshot scene,
            ScreenEffectSnapshot effects,
            EnvironmentCaptureSnapshot environment
    ) implements PictureInPictureRenderState {
        public State(int x, int y, int width, int height, float scale, ScreenRectangle scissorArea,
                     UUID battleId, BattleScene.Snapshot scene, ScreenEffectSnapshot effects,
                     EnvironmentCaptureSnapshot environment) {
            this(
                    x, y, x + width, y + height, scale, scissorArea,
                    PictureInPictureRenderState.getBounds(x, y, x + width, y + height, scissorArea),
                    battleId, scene, effects, environment
            );
        }

        public State {
            java.util.Objects.requireNonNull(battleId, "battleId");
            java.util.Objects.requireNonNull(scene, "scene");
            java.util.Objects.requireNonNull(effects, "effects");
            java.util.Objects.requireNonNull(environment, "environment");
            environment.battleId().ifPresent(environmentBattleId -> {
                if (!battleId.equals(environmentBattleId)) {
                    throw new IllegalArgumentException("环境捕获快照不属于当前 Battle");
                }
            });
        }
    }

    private final RenderTargets targets = new RenderTargets();
    private final DrawUniform uniform = new DrawUniform();
    private final LightingUniform lightingUniform = new LightingUniform();
    private final SoulPointLightUniform soulPointLightUniform = new SoulPointLightUniform();
    private final PostProcessing postProcessing = new PostProcessing();
    private final RenderModeCrossfade renderModeCrossfade = new RenderModeCrossfade();
    private final ScreenEffects screenEffects = new ScreenEffects();
    private final EnvironmentBackground environmentBackground = new EnvironmentBackground();
    private final EnvironmentLighting environmentLighting = new EnvironmentLighting();
    private final VolumetricShadows volumetricShadows =
            new VolumetricShadows(this.lightingUniform, this.uniform);
    private final GpuBuffer unitQuad = createUnitQuad();
    private final Map<ObjModels, ModelBuffer> models = new EnumMap<>(ObjModels.class);
    private final Map<ExtrudedImageKey, ModelBuffer> extrudedImages = new HashMap<>();
    private final Map<BattleScene.FrameMesh, ModelBuffer> battleFrames = new LinkedHashMap<>(16, 0.75F, true);
    private UUID preparedBattleId;
    private ShadowCacheKey shadowMapCacheKey;
    private long shadowMapRevision;
    private long objModelRevision = -1L;
    private int emissiveDrawSubmissions;
    private long emissiveCpuSubmissionNanos;

    public Renderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    public void prepare(State state, GuiRenderState guiState, int guiScale) {
        refreshObjModelBuffers();
        RenderPlan plan = createRenderPlan(state, guiScale);
        beginFrame(state.battleId(), plan);
        try {
            renderFrame(state, plan);
        } finally {
            endFrame(plan);
        }
        submitFrame(state, guiState, plan.targets());
    }

    private void refreshObjModelBuffers() {
        long currentRevision = ObjLoader.revision();
        if (this.objModelRevision == currentRevision) {
            return;
        }

        for (ModelBuffer model : this.models.values()) {
            model.close();
        }
        this.models.clear();
        this.objModelRevision = currentRevision;
    }

    private RenderPlan createRenderPlan(State state, int guiScale) {
        int width = Math.max(1, (state.x1() - state.x0()) * guiScale);
        int height = Math.max(1, (state.y1() - state.y0()) * guiScale);
        RenderModeCrossfade.FramePlan renderModePlan = RenderModeCrossfade.plan(
                state.scene().sceneMode(),
                state.scene().renderedBlend(),
                VisualConfig.BATTLE_RENDER_MODE()
        );
        Lighting.Settings unmodifiedLighting = Lighting.captureSettings(
                state.scene().sceneMode(),
                VisualConfig.BattleRenderMode.UNMODIFIED
        );
        Lighting.Settings renderedLighting = Lighting.captureSettings(
                state.scene().sceneMode(),
                VisualConfig.BattleRenderMode.RENDERED
        );
        BattleScene.Frame frame = BattleScene.compile(state.scene(), width, height);
        PostProcessing.FramePlan unmodifiedPostPlan = PostProcessing.plan(false, width, height);
        PostProcessing.FramePlan renderedPostPlan = PostProcessing.plan(true, width, height);
        PostProcessing.FramePlan allocatedPostPlan =
                renderModePlan.needsRendered() ? renderedPostPlan : unmodifiedPostPlan;
        EnvironmentBackground.FramePlan environmentBackgroundPlan =
                this.environmentBackground.plan(
                        state.environment(),
                        state.effects().environmentBackgroundOpacity()
                );
        EnvironmentLighting.FramePlan environmentLightingPlan = renderModePlan.needsRendered()
                ? this.environmentLighting.plan(
                        state.battleId(),
                        state.scene(),
                        frame,
                        renderedLighting,
                        state.environment()
                )
                : EnvironmentLighting.FramePlan.disabled();
        Lighting.LightSpace lightSpace = renderModePlan.needsRendered()
                ? Lighting.lightSpace(frame, renderedLighting)
                : null;
        Lighting.Settings volumetricLighting =
                renderModePlan.needsRendered() ? renderedLighting : unmodifiedLighting;
        VolumetricShadows.Plan volumetric =
                this.volumetricShadows.prepare(frame, lightSpace, volumetricLighting);
        RenderTargets.TargetSet targetSet = this.targets.ensure(
                width, height, renderedLighting.shadowMapSize(), volumetric.hierarchyLayout(),
                volumetric.point().enabled() ? volumetric.point().layout() : null,
                allocatedPostPlan.targets(),
                environmentBackgroundPlan.enabled(),
                renderModePlan.crossfade());

        return new RenderPlan(
                width,
                height,
                renderModePlan,
                unmodifiedLighting,
                renderedLighting,
                frame,
                unmodifiedPostPlan,
                renderedPostPlan,
                allocatedPostPlan,
                environmentBackgroundPlan,
                environmentLightingPlan,
                lightSpace,
                volumetric,
                targetSet
        );
    }

    private void beginFrame(UUID battleId, RenderPlan plan) {
        if (!battleId.equals(this.preparedBattleId)) {
            this.preparedBattleId = battleId;
            this.screenEffects.reset();
            this.shadowMapCacheKey = null;
        }
        this.uniform.upload(plan.frame(), plan.width(), plan.height());
        if (plan.renderMode().needsRendered()) {
            this.lightingUniform.upload(plan.frame(), plan.renderedLighting(), plan.lightSpace());
        }
        if (plan.environmentBackground().enabled()) {
            this.environmentBackground.upload(plan.frame(), plan.environmentBackground());
        }
        if (plan.environmentLighting().bindingsRequired()) {
            this.environmentLighting.upload(plan.environmentLighting());
        }
        this.postProcessing.beginFrame(plan.frame(), plan.allocatedPostProcessing());
    }

    private void renderFrame(State state, RenderPlan plan) {
        try (PreparedFrame prepared = prepareFrame(
                plan.frame(),
                plan.renderMode().needsRendered(),
                plan.renderMode().needsRendered() && plan.renderedPostProcessing().bloom(),
                plan.environmentLighting(),
                state.environment().debugTexture().orElse(null)
        )) {
            GpuTextureView soulPointAtlas = prepareLighting(plan, prepared);
            evaluateEnvironmentBackground(plan);
            renderUnmodifiedBranch(plan, prepared, soulPointAtlas);
            renderRenderedBranch(plan, prepared, soulPointAtlas);
        }
        if (!plan.renderMode().needsRendered()) {
            resetEmissiveSubmissionStats();
        }
        composeOutput(state, plan);
    }

    private GpuTextureView prepareLighting(RenderPlan plan, PreparedFrame prepared) {
        if (plan.renderMode().needsRendered() && plan.renderedLighting().shadowsEnabled()) {
            ShadowCacheKey currentShadowMapKey = shadowMapCacheKey(
                    plan.lightSpace(),
                    plan.renderedLighting().shadowMapSize(),
                    plan.targets().shadow(),
                    prepared
            );
            boolean shadowMapChanged = !VisualConfig.VOLUMETRIC_SHADOW_CACHE_ENABLED()
                    || !currentShadowMapKey.equals(this.shadowMapCacheKey);
            if (shadowMapChanged) {
                renderShadowMap(plan.targets().shadow(), prepared);
                this.shadowMapRevision++;
            }
            this.shadowMapCacheKey = currentShadowMapKey;
        }
        if (plan.renderMode().needsRendered()) {
            this.volumetricShadows.refreshHierarchy(
                    plan.targets(),
                    this.shadowMapRevision,
                    plan.volumetricShadows(),
                    prepareSoulShadowCasters(plan.volumetricShadows().point(), prepared)
            );
        }
        GpuTextureView soulPointAtlas = plan.targets().soulPointShadowAtlas() == null
                ? plan.targets().shadow().getDepthTextureView()
                : plan.targets().soulPointShadowAtlas().getColorTextureView();
        if (plan.renderMode().needsRendered()) {
            this.soulPointLightUniform.upload(
                    plan.volumetricShadows().point(),
                    plan.renderedLighting()
            );
        }
        return soulPointAtlas;
    }

    private void evaluateEnvironmentBackground(RenderPlan plan) {
        if (plan.environmentBackground().enabled()) {
            this.environmentBackground.evaluate(
                    plan.targets().environmentBackground(),
                    plan.environmentBackground()
            );
        }
    }

    private void renderUnmodifiedBranch(
            RenderPlan plan,
            PreparedFrame prepared,
            GpuTextureView soulPointAtlas
    ) {
        if (!plan.renderMode().needsUnmodified()) {
            return;
        }
        TextureTarget destination = plan.renderMode().crossfade()
                ? plan.targets().crossfadeUnmodified()
                : plan.targets().pong();
        renderModeBranch(
                plan.targets().raw(),
                plan.targets().environmentBackground(),
                plan.targets().shadow().getDepthTextureView(),
                soulPointAtlas,
                prepared,
                plan.unmodifiedLighting(),
                plan.environmentBackground(),
                plan.frame(),
                null,
                plan.targets()
        );
        this.postProcessing.process(
                plan.targets(),
                plan.unmodifiedPostProcessing(),
                plan.targets().raw(),
                destination,
                "Unmodified"
        );
    }

    private void renderRenderedBranch(
            RenderPlan plan,
            PreparedFrame prepared,
            GpuTextureView soulPointAtlas
    ) {
        if (!plan.renderMode().needsRendered()) {
            return;
        }
        TextureTarget rawTarget = plan.renderMode().crossfade()
                ? plan.targets().crossfadeRaw()
                : plan.targets().raw();
        TextureTarget destination = plan.renderMode().crossfade()
                ? plan.targets().crossfadeRendered()
                : plan.targets().pong();
        renderModeBranch(
                rawTarget,
                plan.targets().environmentBackground(),
                plan.targets().shadow().getDepthTextureView(),
                soulPointAtlas,
                prepared,
                plan.renderedLighting(),
                plan.environmentBackground(),
                plan.frame(),
                plan.volumetricShadows(),
                plan.targets()
        );
        if (plan.renderedPostProcessing().bloom()) {
            long emissiveStarted = System.nanoTime();
            this.emissiveDrawSubmissions = countEmissiveDraws(prepared);
            renderEmissiveBloomSource(plan.targets(), rawTarget, prepared);
            this.emissiveCpuSubmissionNanos = System.nanoTime() - emissiveStarted;
        } else {
            resetEmissiveSubmissionStats();
        }
        this.postProcessing.process(
                plan.targets(),
                plan.renderedPostProcessing(),
                rawTarget,
                destination,
                "Rendered"
        );
    }

    private void resetEmissiveSubmissionStats() {
        this.emissiveDrawSubmissions = 0;
        this.emissiveCpuSubmissionNanos = 0L;
    }

    private void composeOutput(State state, RenderPlan plan) {
        if (plan.renderMode().crossfade()) {
            this.renderModeCrossfade.process(
                    plan.targets().crossfadeUnmodified(),
                    plan.targets().crossfadeRendered(),
                    plan.targets().pong(),
                    plan.renderMode()
            );
        }
        this.screenEffects.process(
                plan.targets(),
                state.effects(),
                this.environmentBackground.foregroundTexture(
                        plan.targets().environmentBackground(),
                        plan.environmentBackground()
                ),
                plan.width(),
                plan.height()
        );
    }

    private void endFrame(RenderPlan plan) {
        if (!plan.frame().commands().isEmpty()) {
            this.uniform.rotate();
        }
        if (plan.renderMode().needsRendered()) {
            this.soulPointLightUniform.rotate();
            this.lightingUniform.rotate();
        }
        this.postProcessing.endFrame();
        this.environmentBackground.rotate(plan.environmentBackground());
        this.environmentLighting.rotate(plan.environmentLighting());
    }

    private static void submitFrame(
            State state,
            GuiRenderState guiState,
            RenderTargets.TargetSet targets
    ) {
        guiState.submitBlitToCurrentLayer(new BlitRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(targets.output().getColorTextureView()),
                state.pose(),
                state.x0(), state.y0(), state.x1(), state.y1(),
                0.0F, 1.0F, 1.0F, 0.0F,
                -1, state.scissorArea(), state.bounds()
        ));
    }

    // 所有可能触发上传的资源必须在 RenderPass 打开前解析；draw 阶段只绑定并提交。
    private PreparedFrame prepareFrame(
            BattleScene.Frame frame,
            boolean rendered,
            boolean bloom,
            EnvironmentLighting.FramePlan environmentLighting,
            EnvironmentCaptureSnapshot.DebugTexture environmentDebugTexture
    ) {
        List<GlyphBatch> glyphs = buildGlyphBatches(frame);
        try {
            Map<Integer, List<GlyphBatch>> glyphsByCommand = new HashMap<>();
            int maximumQuadIndexCount = 0;
            for (GlyphBatch glyph : glyphs) {
                glyphsByCommand.computeIfAbsent(glyph.commandIndex(), ignored -> new ArrayList<>()).add(glyph);
                maximumQuadIndexCount = Math.max(maximumQuadIndexCount, glyph.indexCount());
            }

            Map<ResourceLocation, GpuTextureView> textures = new HashMap<>();
            Map<ResourceLocation, Optional<GpuTextureView>> specularTextures = new HashMap<>();
            Map<ResourceLocation, Optional<GpuTextureView>> emissiveTextures = new HashMap<>();
            Map<ExtrudedImageKey, ModelBuffer> frameExtrudedImages = new HashMap<>();
            Set<BattleScene.FrameMesh> frameMeshesInUse = new HashSet<>();
            List<PreparedCommand> commands = new ArrayList<>(frame.commands().size());
            for (int index = 0; index < frame.commands().size(); index++) {
                BattleScene.RenderCommand command = frame.commands().get(index);
                GpuTextureView texture = command.texture() == null
                        ? null
                        : textures.computeIfAbsent(command.texture(), Renderer::texture);
                GpuTextureView specularTexture = rendered
                        && supportsMaterialMap(command)
                        && command.texture() != null
                        ? specularTextures.computeIfAbsent(command.texture(), Renderer::materialTexture).orElse(null)
                        : null;
                GpuTextureView emissiveTexture = bloom
                        && supportsMaterialMap(command)
                        && command.texture() != null
                        ? emissiveTextures.computeIfAbsent(command.texture(), Renderer::emissiveTexture).orElse(null)
                        : null;
                ModelBuffer model = command.objModel() == null
                        ? null
                        : this.models.computeIfAbsent(command.objModel(), Renderer::createModelBuffer);
                if (command.extrudedImage() != null) {
                    ExtrudedImageKey key = new ExtrudedImageKey(command.texture(), command.extrudedImage().source(),
                            command.extrudedImage().textureSize());
                    model = frameExtrudedImages.computeIfAbsent(key,
                            ignored -> this.extrudedImages.computeIfAbsent(key, Renderer::createExtrudedImageBuffer));
                }
                if (command.frameMesh() != null) {
                    frameMeshesInUse.add(command.frameMesh());
                    model = this.battleFrames.computeIfAbsent(command.frameMesh(), Renderer::createBattleFrameBuffer);
                }
                if (model != null) {
                    maximumQuadIndexCount = Math.max(maximumQuadIndexCount, model.indexCount());
                }
                commands.add(new PreparedCommand(
                         command,
                         texture,
                         specularTexture,
                         emissiveTexture,
                         model,
                        List.copyOf(glyphsByCommand.getOrDefault(index, List.of()))
                ));
            }
            trimBattleFrameCache(frameMeshesInUse);

            RenderSystem.AutoStorageIndexBuffer sequentialQuads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer quadIndices = maximumQuadIndexCount == 0
                    ? null
                    : sequentialQuads.getBuffer(maximumQuadIndexCount);
            return new PreparedFrame(
                    commands,
                    glyphs,
                    quadIndices,
                    sequentialQuads.type(),
                    environmentLighting,
                    environmentDebugTexture
            );
        } catch (RuntimeException exception) {
            closeGlyphs(glyphs);
            throw exception;
        }
    }

    private void renderShadowMap(TextureTarget target, PreparedFrame prepared) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle Renderer V2 global-light shadow map",
                target.getColorTextureView(), OptionalInt.of(0x00000000),
                target.getDepthTextureView(), OptionalDouble.of(1.0D)
        )) {
            pass.setViewport(0, 0, target.width, target.height);
            this.lightingUniform.bind(pass);
            for (int index = 0; index < prepared.commands().size(); index++) {
                PreparedCommand command = prepared.commands().get(index);
                if (!command.command().rendered().castsShadow()) {
                    continue;
                }
                ModelBuffer model = command.model();
                if (model == null || model.vertexCount() == 0) {
                    continue;
                }
                this.uniform.bind(pass, index);
                switch (command.command().geometry()) {
                    case OBJ_MODEL, EXTRUDED_IMAGE -> {
                        pass.setPipeline(BattleEntity.SHADOW_PIPELINE);
                        pass.bindSampler("Sampler0", command.texture());
                    }
                    case BATTLE_FRAME -> pass.setPipeline(BattleFrame.SHADOW_PIPELINE);
                    default -> {
                        continue;
                    }
                }
                pass.setVertexBuffer(0, model.buffer());
                pass.setIndexBuffer(prepared.quadIndices(), prepared.quadIndexType());
                pass.drawIndexed(0, 0, model.indexCount(), 1);
            }
        }
    }

    // 只转交已准备的模型、纹理与索引视图，不得在此解析或上传资源。
    private static List<VolumetricShadows.PreparedPointCaster> prepareSoulShadowCasters(
            VolumetricShadows.PointPlan plan,
            PreparedFrame prepared
    ) {
        if (!plan.enabled()) return List.of();
        List<VolumetricShadows.PreparedPointCaster> result = new ArrayList<>(plan.casters().size());
        for (VolumetricShadows.PointCasterPlan caster : plan.casters()) {
            PreparedCommand command = prepared.commands().get(caster.commandIndex());
            ModelBuffer model = command.model();
            if (model == null || model.buffer() == null || model.vertexCount() == 0
                    || prepared.quadIndices() == null) {
                continue;
            }
            result.add(new VolumetricShadows.PreparedPointCaster(
                    caster.commandIndex(),
                    caster.faceMask(),
                    command.command().geometry(),
                    command.texture(),
                    model.buffer(),
                    prepared.quadIndices(),
                    prepared.quadIndexType(),
                    model.indexCount()
            ));
        }
        return List.copyOf(result);
    }

    private void renderModeBranch(
            TextureTarget target,
            TextureTarget environmentBackgroundTarget,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            PreparedFrame prepared,
            Lighting.Settings lighting,
            EnvironmentBackground.FramePlan environmentBackgroundPlan,
            BattleScene.Frame frame,
            VolumetricShadows.Plan volumetric,
            RenderTargets.TargetSet targetSet
    ) {
        if (environmentBackgroundPlan.enabled()) {
            clearRawScene(target);
        }
        if (volumetric != null && volumetric.enabled()) {
            renderOpaqueScene(
                    target,
                    environmentBackgroundTarget,
                    shadowMap,
                    soulPointAtlas,
                    prepared,
                    lighting,
                    environmentBackgroundPlan
            );
            this.volumetricShadows.render(targetSet, target, frame, volumetric);
            renderDeferredScene(
                    target,
                    shadowMap,
                    soulPointAtlas,
                    prepared,
                    lighting
            );
            return;
        }
        renderScene(
                target,
                environmentBackgroundTarget,
                shadowMap,
                soulPointAtlas,
                prepared,
                lighting,
                environmentBackgroundPlan
        );
    }

    private void renderScene(
            TextureTarget target,
            TextureTarget environmentBackgroundTarget,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            PreparedFrame prepared,
            Lighting.Settings lighting,
            EnvironmentBackground.FramePlan environmentBackgroundPlan
    ) {
        OptionalInt clearColor = environmentBackgroundPlan.enabled()
                ? OptionalInt.empty()
                : OptionalInt.of(0x00000000);
        OptionalDouble clearDepth = environmentBackgroundPlan.enabled()
                ? OptionalDouble.empty()
                : OptionalDouble.of(1.0D);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle Renderer V2 raw scene",
                target.getColorTextureView(), clearColor,
                target.getDepthTextureView(), clearDepth
        )) {
            pass.setViewport(0, 0, target.width, target.height);
            if (lighting.rendered()) {
                this.lightingUniform.bind(pass);
            }
            if (environmentBackgroundPlan.enabled()) {
                this.environmentBackground.composite(
                        pass,
                        environmentBackgroundTarget,
                        environmentBackgroundPlan
                );
            }
            drawEnvironmentDebug(pass, prepared.environmentDebugTexture());
            for (int index = 0; index < prepared.commands().size(); index++) {
                PreparedCommand preparedCommand = prepared.commands().get(index);
                drawCommand(
                        pass, prepared, preparedCommand,
                        shadowMap, soulPointAtlas, lighting,
                        index, VisualPassMode.COLOR);
            }
        }
    }

    // 辅助网格先混入底色但不写深度，使后续体积积分仍可覆盖并混合。
    private void renderOpaqueScene(
            TextureTarget target,
            TextureTarget environmentBackgroundTarget,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            PreparedFrame prepared,
            Lighting.Settings lighting,
            EnvironmentBackground.FramePlan environmentBackgroundPlan
    ) {
        OptionalInt clearColor = environmentBackgroundPlan.enabled()
                ? OptionalInt.empty()
                : OptionalInt.of(0x00000000);
        OptionalDouble clearDepth = environmentBackgroundPlan.enabled()
                ? OptionalDouble.empty()
                : OptionalDouble.of(1.0D);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle Renderer V2 opaque scene",
                target.getColorTextureView(), clearColor,
                target.getDepthTextureView(), clearDepth
        )) {
            pass.setViewport(0, 0, target.width, target.height);
            this.lightingUniform.bind(pass);
            if (environmentBackgroundPlan.enabled()) {
                this.environmentBackground.composite(
                        pass,
                        environmentBackgroundTarget,
                        environmentBackgroundPlan
                );
            }
            drawEnvironmentDebug(pass, prepared.environmentDebugTexture());
            for (int index = 0; index < prepared.commands().size(); index++) {
                PreparedCommand command = prepared.commands().get(index);
                if (!isPreVolumeCommand(command.command())) continue;
                drawCommand(
                        pass, prepared, command,
                        shadowMap, soulPointAtlas, lighting,
                        index, VisualPassMode.COLOR);
            }
        }
    }

    private void renderDeferredScene(
            TextureTarget target,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            PreparedFrame prepared,
            Lighting.Settings lighting
    ) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle Renderer V2 translucent and screen scene",
                target.getColorTextureView(), OptionalInt.empty(),
                target.getDepthTextureView(), OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, target.width, target.height);
            this.lightingUniform.bind(pass);
            for (int index = 0; index < prepared.commands().size(); index++) {
                PreparedCommand command = prepared.commands().get(index);
                if (isPreVolumeCommand(command.command())) continue;
                drawCommand(
                        pass, prepared, command,
                        shadowMap, soulPointAtlas, lighting,
                        index, VisualPassMode.COLOR);
            }
        }
    }

    private static void clearRawScene(TextureTarget target) {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                target.getColorTexture(),
                0x00000000,
                target.getDepthTexture(),
                1.0D
        );
    }

    // 仅调试模式显示会话保留的 Atlas、Raw Lobe 或 Field，不进入最终背景路径。
    private static void drawEnvironmentDebug(
            RenderPass pass,
            EnvironmentCaptureSnapshot.DebugTexture debugTexture
    ) {
        if (debugTexture == null) {
            return;
        }
        pass.setPipeline(PipelineRegister.BATTLE_ENVIRONMENT_DEBUG);
        pass.bindSampler("EnvironmentDebugSampler", debugTexture.textureView());
        pass.draw(0, 3);
    }

    // 仅重绘已确认 *_s.b 非零的可见材质；只读复用场景深度，防止被遮挡的自发光穿透 Bloom。
    private void renderEmissiveBloomSource(
            RenderTargets.TargetSet targets,
            TextureTarget scene,
            PreparedFrame prepared
    ) {
        TextureTarget source = targets.bloomSource();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Battle emissive Bloom source",
                source.getColorTextureView(), OptionalInt.of(0x00000000),
                scene.getDepthTextureView(), OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, source.width, source.height);
            for (int index = 0; index < prepared.commands().size(); index++) {
                PreparedCommand command = prepared.commands().get(index);
                drawCommand(
                        pass, prepared, command,
                        null, null, null, index, VisualPassMode.EMISSIVE);
            }
        }
    }

    private static boolean supportsMaterialMap(BattleScene.RenderCommand command) {
        return command.geometry() == BattleScene.Geometry.OBJ_MODEL
                || command.geometry() == BattleScene.Geometry.EXTRUDED_IMAGE
                || (command.geometry() == BattleScene.Geometry.UNIT_QUAD
                && command.material() == BattleScene.Material.TEXTURE);
    }

    private static boolean isOpaqueWorld(BattleScene.RenderCommand command) {
        return command.binding() != BattleScene.ActorSpaceBinding.SCREEN_PLANE
                && command.alphaMode() != BattleScene.AlphaMode.TRANSLUCENT;
    }

    private static boolean isPreVolumeCommand(BattleScene.RenderCommand command) {
        return isOpaqueWorld(command) || command.material() == BattleScene.Material.AUXILIARY_GRID;
    }

    private void drawCommand(
            RenderPass pass,
            PreparedFrame frame,
            PreparedCommand prepared,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            Lighting.Settings lighting,
            int commandIndex,
            VisualPassMode mode
    ) {
        if (mode == VisualPassMode.EMISSIVE) {
            drawEmissiveCommand(pass, frame, prepared, commandIndex);
            return;
        }
        BattleScene.RenderCommand command = prepared.command();
        if (command.material() == BattleScene.Material.AUXILIARY_GRID
                && (!lighting.rendered() || !command.rendered().enabled())) {
            return;
        }
        this.uniform.bind(pass, commandIndex);
        switch (command.geometry()) {
            case UNIT_QUAD -> drawQuad(pass, prepared, shadowMap, lighting);
            case EXTRUDED_IMAGE -> drawExtrudedImage(
                    pass, frame, prepared, shadowMap, soulPointAtlas, lighting);
            case LINE_SEGMENT -> {
                pass.setPipeline(command.alphaMode() == BattleScene.AlphaMode.TRANSLUCENT
                        ? PipelineRegister.BATTLE_LINE_TRANSLUCENT
                        : PipelineRegister.BATTLE_LINE);
                pass.setVertexBuffer(0, this.unitQuad);
                pass.draw(0, 4);
            }
            case BATTLE_FRAME -> drawBattleFrame(
                    pass, frame, prepared, shadowMap, soulPointAtlas, lighting);
            case OBJ_MODEL -> drawModel(
                    pass, frame, prepared, shadowMap, soulPointAtlas, lighting);
            case GLYPH_RUN -> drawGlyphs(pass, frame, prepared);
        }
    }

    private void drawEmissiveCommand(
            RenderPass pass,
            PreparedFrame frame,
            PreparedCommand prepared,
            int commandIndex
    ) {
        ModelBuffer model = prepared.model();
        if (prepared.emissiveTexture() == null
                || !supportsMaterialMap(prepared.command())
                || (prepared.command().geometry() != BattleScene.Geometry.UNIT_QUAD
                && (model == null || model.vertexCount() == 0 || frame.quadIndices() == null))) {
            return;
        }
        this.uniform.bind(pass, commandIndex);
        this.postProcessing.bindUniform(pass);
        pass.bindSampler("Sampler0", prepared.texture());
        pass.bindSampler("SpecularSampler", prepared.emissiveTexture());
        if (prepared.command().geometry() == BattleScene.Geometry.UNIT_QUAD) {
            pass.setPipeline(BattleEntity.EMISSIVE_QUAD_BLOOM_SOURCE_PIPELINE);
            pass.setVertexBuffer(0, this.unitQuad);
            pass.draw(0, 4);
            return;
        }
        pass.setPipeline(BattleEntity.EMISSIVE_BLOOM_SOURCE_PIPELINE);
        this.lightingUniform.bind(pass);
        pass.setVertexBuffer(0, model.buffer());
        pass.setIndexBuffer(frame.quadIndices(), frame.quadIndexType());
        pass.drawIndexed(0, 0, model.indexCount(), 1);
    }

    private static ShadowCacheKey shadowMapCacheKey(
            Lighting.LightSpace lightSpace,
            int shadowMapSize,
            TextureTarget shadowTarget,
            PreparedFrame prepared
    ) {
        long first = 0x243F6A8885A308D3L;
        long second = 0x13198A2E03707344L;
        float[] matrix = new float[16];
        lightSpace.viewProjection().get(matrix);
        for (float value : matrix) {
            int bits = Float.floatToIntBits(value);
            first = mixHash(first, bits);
            second = mixHash(second, Integer.rotateLeft(bits, 13));
        }
        first = mixHash(first, shadowMapSize);
        first = mixHash(first, System.identityHashCode(shadowTarget.getDepthTextureView().texture()));

        int casterCount = 0;
        for (PreparedCommand preparedCommand : prepared.commands()) {
            BattleScene.RenderCommand command = preparedCommand.command();
            if (!command.rendered().castsShadow()
                    || (command.geometry() != BattleScene.Geometry.OBJ_MODEL
                    && command.geometry() != BattleScene.Geometry.EXTRUDED_IMAGE
                    && command.geometry() != BattleScene.Geometry.BATTLE_FRAME)) continue;
            casterCount++;
            first = mixHash(first, command.geometry().ordinal());
            first = mixHash(first, command.alphaMode().ordinal());
            first = mixHash(first, command.color());
            first = mixHash(first, command.texture() == null ? 0 : command.texture().hashCode());
            first = mixHash(first, command.objModel() == null ? -1 : command.objModel().ordinal());
            first = mixHash(first, command.extrudedImage() == null ? 0 : command.extrudedImage().hashCode());
            first = mixHash(first, command.frameMesh() == null ? 0 : command.frameMesh().hashCode());
            first = mixHash(first, command.sortKey());
            command.model().get(matrix);
            for (float value : matrix) first = mixHash(first, Float.floatToIntBits(value));

            GpuTextureView texture = preparedCommand.texture();
            ModelBuffer model = preparedCommand.model();
            second = mixHash(second, texture == null ? 0 : System.identityHashCode(texture.texture()));
            second = mixHash(second, model == null || model.buffer() == null
                    ? 0 : System.identityHashCode(model.buffer()));
        }
        first = mixHash(first, casterCount);
        second = mixHash(second, casterCount);
        return new ShadowCacheKey(first, second);
    }

    private static long mixHash(long hash, long value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return Long.rotateLeft(hash ^ mixed, 27) * 5L + 0x52DCE729L;
    }

    private void drawQuad(
            RenderPass pass,
            PreparedCommand prepared,
            GpuTextureView shadowMap,
            Lighting.Settings lighting
    ) {
        BattleScene.RenderCommand command = prepared.command();
        RenderPipeline pipeline = switch (command.material()) {
            case SOLID -> command.binding() == BattleScene.ActorSpaceBinding.SCREEN_PLANE
                    ? PipelineRegister.BATTLE_SOLID_SCREEN
                    : command.alphaMode() == BattleScene.AlphaMode.TRANSLUCENT
                            ? PipelineRegister.BATTLE_SOLID_WORLD_TRANSLUCENT
                            : PipelineRegister.BATTLE_SOLID_WORLD;
            case TEXTURE -> command.binding() == BattleScene.ActorSpaceBinding.SCREEN_PLANE
                    ? PipelineRegister.BATTLE_TEXTURE_SCREEN
                    : command.alphaMode() == BattleScene.AlphaMode.TRANSLUCENT
                            ? PipelineRegister.BATTLE_TEXTURE_WORLD_TRANSLUCENT
                            : PipelineRegister.BATTLE_TEXTURE_WORLD_CUTOUT;
            case AUXILIARY_GRID -> BattleAuxiliaryGrid.RENDERED_PIPELINE;
            default -> throw new IllegalStateException("Unsupported quad material: " + command.material());
        };
        pass.setPipeline(pipeline);
        if (prepared.texture() != null) {
            pass.bindSampler("Sampler0", prepared.texture());
        }
        if (command.material() == BattleScene.Material.AUXILIARY_GRID) {
            pass.bindSampler("ShadowSampler", shadowMap);
        }
        pass.setVertexBuffer(0, this.unitQuad);
        pass.draw(0, 4);
    }

    private void drawModel(
            RenderPass pass,
            PreparedFrame frame,
            PreparedCommand prepared,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            Lighting.Settings lighting
    ) {
        ModelBuffer model = prepared.model();
        if (model.vertexCount() == 0) {
            return;
        }
        boolean rendered = lighting.rendered() && prepared.command().rendered().enabled();
        BattleEntity.Pipelines pipelines = BattleEntity.pipelines(rendered
                ? VisualConfig.BattleRenderMode.RENDERED
                : VisualConfig.BattleRenderMode.UNMODIFIED);
        pass.setPipeline(pipelines.select(
                prepared.command().alphaMode() == BattleScene.AlphaMode.TRANSLUCENT,
                prepared.specularTexture() != null));
        pass.bindSampler("Sampler0", prepared.texture());
        if (rendered) {
            pass.bindSampler("ShadowSampler", shadowMap);
            this.soulPointLightUniform.bind(pass, soulPointAtlas);
            this.environmentLighting.bind(pass, frame.environmentLighting());
            if (prepared.specularTexture() != null) {
                pass.bindSampler("SpecularSampler", prepared.specularTexture());
            }
        }
        pass.setVertexBuffer(0, model.buffer());
        pass.setIndexBuffer(frame.quadIndices(), frame.quadIndexType());
        pass.drawIndexed(0, 0, model.indexCount(), 1);
    }

    // 平滑缩放会产生连续尺寸，只保留近期 Mesh，防止 GPU Buffer 无界增长。
    private void trimBattleFrameCache(Set<BattleScene.FrameMesh> frameMeshesInUse) {
        Iterator<Map.Entry<BattleScene.FrameMesh, ModelBuffer>> iterator = this.battleFrames.entrySet().iterator();
        while (this.battleFrames.size() > MAX_CACHED_BATTLE_FRAMES && iterator.hasNext()) {
            Map.Entry<BattleScene.FrameMesh, ModelBuffer> entry = iterator.next();
            if (frameMeshesInUse.contains(entry.getKey())) continue;
            iterator.remove();
            entry.getValue().close();
        }
    }

    private void drawBattleFrame(
            RenderPass pass,
            PreparedFrame frame,
            PreparedCommand prepared,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            Lighting.Settings lighting
    ) {
        ModelBuffer model = prepared.model();
        if (model == null || model.vertexCount() == 0) return;
        boolean rendered = lighting.rendered() && prepared.command().rendered().enabled();
        pass.setPipeline(rendered ? BattleFrame.RENDERED_PIPELINE : BattleFrame.UNMODIFIED_PIPELINE);
        if (rendered) {
            pass.bindSampler("ShadowSampler", shadowMap);
            this.soulPointLightUniform.bind(pass, soulPointAtlas);
            this.environmentLighting.bind(pass, frame.environmentLighting());
        }
        pass.setVertexBuffer(0, model.buffer());
        pass.setIndexBuffer(frame.quadIndices(), frame.quadIndexType());
        pass.drawIndexed(0, 0, model.indexCount(), 1);
    }

    private void drawExtrudedImage(
            RenderPass pass,
            PreparedFrame frame,
            PreparedCommand prepared,
            GpuTextureView shadowMap,
            GpuTextureView soulPointAtlas,
            Lighting.Settings lighting
    ) {
        ModelBuffer model = prepared.model();
        if (model == null || model.vertexCount() == 0) {
            return;
        }
        boolean rendered = lighting.rendered() && prepared.command().rendered().enabled();
        pass.setPipeline(rendered
                ? BattleEntity.RENDERED_EXTRUDED_PIPELINE
                : PipelineRegister.BATTLE_TEXTURE_WORLD_CUTOUT_QUADS);
        pass.bindSampler("Sampler0", prepared.texture());
        if (rendered) {
            pass.bindSampler("ShadowSampler", shadowMap);
            this.soulPointLightUniform.bind(pass, soulPointAtlas);
        }
        pass.setVertexBuffer(0, model.buffer());
        pass.setIndexBuffer(frame.quadIndices(), frame.quadIndexType());
        pass.drawIndexed(0, 0, model.indexCount(), 1);
    }

    private static void drawGlyphs(RenderPass pass, PreparedFrame frame, PreparedCommand prepared) {
        boolean world = prepared.command().binding() == BattleScene.ActorSpaceBinding.WORLD;
        for (GlyphBatch glyph : prepared.glyphs()) {
            pass.setPipeline(world
                    ? (glyph.intensity() ? PipelineRegister.BATTLE_GLYPH_WORLD_INTENSITY : PipelineRegister.BATTLE_GLYPH_WORLD_COLOR)
                    : (glyph.intensity() ? PipelineRegister.BATTLE_GLYPH_INTENSITY : PipelineRegister.BATTLE_GLYPH_COLOR));
            pass.bindSampler("Sampler0", glyph.texture());
            pass.setVertexBuffer(0, glyph.buffer());
            pass.setIndexBuffer(frame.quadIndices(), frame.quadIndexType());
            pass.drawIndexed(0, 0, glyph.indexCount(), 1);
        }
    }

    private List<GlyphBatch> buildGlyphBatches(BattleScene.Frame frame) {
        Font font = Minecraft.getInstance().font;
        List<GlyphBatch> result = new ArrayList<>();
        try {
            for (int commandIndex = 0; commandIndex < frame.commands().size(); commandIndex++) {
                BattleScene.RenderCommand command = frame.commands().get(commandIndex);
                if (command.geometry() != BattleScene.Geometry.GLYPH_RUN) {
                    continue;
                }
                Map<GlyphAtlasKey, List<TextRenderable>> byAtlas = layoutText(
                        font,
                        command,
                        command.binding() == BattleScene.ActorSpaceBinding.SCREEN_PLANE
                                ? frame.viewport().relativeUiScale()
                                : 1.0F
                );
                for (Map.Entry<GlyphAtlasKey, List<TextRenderable>> entry : byAtlas.entrySet()) {
                    GlyphBatch batch = createGlyphBatch(commandIndex, entry.getKey(), entry.getValue());
                    if (batch != null) {
                        result.add(batch);
                    }
                }
            }
            return result;
        } catch (RuntimeException exception) {
            closeGlyphs(result);
            throw exception;
        }
    }

    private static Map<GlyphAtlasKey, List<TextRenderable>> layoutText(
            Font font,
            BattleScene.RenderCommand command,
            float viewportScale
    ) {
        ActorAppearance.TextContent text = command.text();
        String textValue = text.text();
        BattleScene.ScreenRect bounds = command.textBounds();
        float scale = text.fontScale().mode() == ActorAppearance.FontScaleMode.AUTO
                ? 1.0F
                : (float) text.fontScale().value() * viewportScale;
        if (text.fit() == ActorAppearance.TextFit.SHRINK_TO_FIT || text.fontScale().mode() == ActorAppearance.FontScaleMode.AUTO) {
            int width = Math.max(1, font.width(textValue));
            scale = Math.min(bounds.width() / width, bounds.height() / font.lineHeight);
            scale = Math.max(0.05F, scale);
        }

        int wrapWidth = Math.max(1, (int) Math.floor(bounds.width() / scale));
        List<FormattedCharSequence> lines;
        if (text.fit() == ActorAppearance.TextFit.WRAP) {
            lines = font.split(Component.literal(textValue), wrapWidth);
            int maximumLines = Math.max(1, (int) Math.floor(bounds.height() / (font.lineHeight * scale)));
            if (lines.size() > maximumLines) {
                lines = List.copyOf(lines.subList(0, maximumLines));
            }
        } else {
            String value = text.fit() == ActorAppearance.TextFit.CLIP
                    ? font.plainSubstrByWidth(textValue, wrapWidth)
                    : textValue;
            lines = List.of(Component.literal(value).getVisualOrderText());
        }
        float contentHeight = lines.size() * font.lineHeight * scale;
        float top = switch (text.verticalAlign()) {
            case TOP -> 0.0F;
            case MIDDLE -> (bounds.height() - contentHeight) * 0.5F;
            case BOTTOM -> bounds.height() - contentHeight;
        };
        Matrix4f pose = new Matrix4f()
                .translate(bounds.x() + bounds.width() * 0.5F, bounds.y() + bounds.height() * 0.5F, 0.0F)
                .rotateZ((float) Math.toRadians(bounds.rotationDeg()))
                .translate(-bounds.width() * 0.5F, -bounds.height() * 0.5F, 0.0F)
                .scale(scale, scale, 1.0F);
        Map<GlyphAtlasKey, List<TextRenderable>> byAtlas = new java.util.HashMap<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            FormattedCharSequence line = lines.get(lineIndex);
            float lineWidth = font.width(line) * scale;
            float x = switch (text.align()) {
                case LEFT -> 0.0F;
                case CENTER -> (bounds.width() - lineWidth) * 0.5F;
                case RIGHT -> bounds.width() - lineWidth;
            } / scale;
            float y = (top + lineIndex * font.lineHeight * scale) / scale;
            font.prepareText(line, x, y, text.color(), text.shadow(), 0).visit(new Font.GlyphVisitor() {
                @Override
                public void acceptGlyph(TextRenderable glyph) {
                    GlyphAtlasKey key = new GlyphAtlasKey(glyph.textureView(), glyph.guiPipeline() == RenderPipelines.GUI_TEXT_INTENSITY);
                    byAtlas.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new TransformedText(glyph, pose));
                }

                @Override
                public void acceptEffect(TextRenderable effect) {
                    GlyphAtlasKey key = new GlyphAtlasKey(effect.textureView(), effect.guiPipeline() == RenderPipelines.GUI_TEXT_INTENSITY);
                    byAtlas.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new TransformedText(effect, pose));
                }
            });
        }
        return byAtlas;
    }

    private static GlyphBatch createGlyphBatch(int commandIndex, GlyphAtlasKey atlas, List<TextRenderable> glyphs) {
        int estimatedVertices = Math.max(4, glyphs.size() * 8);
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(estimatedVertices * DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP.getVertexSize())) {
            BufferBuilder vertices = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
            for (TextRenderable glyph : glyphs) {
                if (glyph instanceof TransformedText transformed) {
                    transformed.delegate().render(transformed.pose(), vertices, LightTexture.FULL_BRIGHT, true);
                }
            }
            MeshData mesh = vertices.build();
            if (mesh == null) {
                return null;
            }
            try (mesh) {
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(
                        () -> "Battle glyph batch", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                return new GlyphBatch(commandIndex, atlas.texture(), atlas.intensity(), buffer, mesh.drawState().indexCount());
            }
        }
    }

    private static ModelBuffer createModelBuffer(ObjModels model) {
        ObjLoader.ObjMesh source = ObjLoader.getOrLoad(model);
        int vertexCount = source.getVertexCount();
        if (vertexCount == 0) {
            return ModelBuffer.empty();
        }
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(vertexCount * DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.getVertexSize())) {
            BufferBuilder vertices = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
            float[] positions = source.getPositions();
            float[] uvs = source.getUvs();
            float[] normals = source.getNormals();
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                int p = vertex * 3;
                int uv = vertex * 2;
                vertices.addVertex(positions[p], positions[p + 1], positions[p + 2])
                        .setUv(uvs[uv], 1.0F - uvs[uv + 1])
                        .setColor(255, 255, 255, 255)
                        .setNormal(normals[p], normals[p + 1], normals[p + 2]);
            }
            try (MeshData mesh = vertices.buildOrThrow()) {
                return new ModelBuffer(RenderSystem.getDevice().createBuffer(
                        () -> "Battle entity " + model.name(), GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()), vertexCount);
            }
        }
    }

    // 角块只保留三个外向面，棱柱只保留四个侧面，接触端面不得写入最终 Mesh。
    private static ModelBuffer createBattleFrameBuffer(BattleScene.FrameMesh frame) {
        float thickness = frame.effectiveThickness();
        float halfThickness = thickness * 0.5F;
        float hx = frame.sizeX() * 0.5F;
        float hy = frame.sizeY() * 0.5F;
        float hz = frame.sizeZ() * 0.5F;
        int edgeCount = (frame.sizeX() > thickness ? 4 : 0)
                + (frame.sizeY() > thickness ? 4 : 0)
                + (frame.sizeZ() > thickness ? 4 : 0);
        int quadCount = 8 * 3 + edgeCount * 4;

        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(
                quadCount * 4 * DefaultVertexFormat.POSITION.getVertexSize())) {
            BufferBuilder vertices = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

            for (int sx : new int[]{-1, 1}) {
                for (int sy : new int[]{-1, 1}) {
                    for (int sz : new int[]{-1, 1}) {
                        float cx = sx * hx;
                        float cy = sy * hy;
                        float cz = sz * hz;
                        faceX(vertices, cx + sx * halfThickness,
                                cy - halfThickness, cy + halfThickness,
                                cz - halfThickness, cz + halfThickness, sx > 0);
                        faceY(vertices, cx - halfThickness, cx + halfThickness,
                                cy + sy * halfThickness,
                                cz - halfThickness, cz + halfThickness, sy > 0);
                        faceZ(vertices, cx - halfThickness, cx + halfThickness,
                                cy - halfThickness, cy + halfThickness,
                                cz + sz * halfThickness, sz > 0);
                    }
                }
            }

            if (frame.sizeX() > thickness) {
                float x0 = -hx + halfThickness;
                float x1 = hx - halfThickness;
                for (int sy : new int[]{-1, 1}) {
                    for (int sz : new int[]{-1, 1}) {
                        float cy = sy * hy;
                        float cz = sz * hz;
                        faceY(vertices, x0, x1, cy - halfThickness,
                                cz - halfThickness, cz + halfThickness, false);
                        faceY(vertices, x0, x1, cy + halfThickness,
                                cz - halfThickness, cz + halfThickness, true);
                        faceZ(vertices, x0, x1, cy - halfThickness, cy + halfThickness,
                                cz - halfThickness, false);
                        faceZ(vertices, x0, x1, cy - halfThickness, cy + halfThickness,
                                cz + halfThickness, true);
                    }
                }
            }
            if (frame.sizeY() > thickness) {
                float y0 = -hy + halfThickness;
                float y1 = hy - halfThickness;
                for (int sx : new int[]{-1, 1}) {
                    for (int sz : new int[]{-1, 1}) {
                        float cx = sx * hx;
                        float cz = sz * hz;
                        faceX(vertices, cx - halfThickness, y0, y1,
                                cz - halfThickness, cz + halfThickness, false);
                        faceX(vertices, cx + halfThickness, y0, y1,
                                cz - halfThickness, cz + halfThickness, true);
                        faceZ(vertices, cx - halfThickness, cx + halfThickness, y0, y1,
                                cz - halfThickness, false);
                        faceZ(vertices, cx - halfThickness, cx + halfThickness, y0, y1,
                                cz + halfThickness, true);
                    }
                }
            }
            if (frame.sizeZ() > thickness) {
                float z0 = -hz + halfThickness;
                float z1 = hz - halfThickness;
                for (int sx : new int[]{-1, 1}) {
                    for (int sy : new int[]{-1, 1}) {
                        float cx = sx * hx;
                        float cy = sy * hy;
                        faceX(vertices, cx - halfThickness, cy - halfThickness, cy + halfThickness,
                                z0, z1, false);
                        faceX(vertices, cx + halfThickness, cy - halfThickness, cy + halfThickness,
                                z0, z1, true);
                        faceY(vertices, cx - halfThickness, cx + halfThickness, cy - halfThickness,
                                z0, z1, false);
                        faceY(vertices, cx - halfThickness, cx + halfThickness, cy + halfThickness,
                                z0, z1, true);
                    }
                }
            }

            try (MeshData mesh = vertices.buildOrThrow()) {
                int vertexCount = mesh.drawState().vertexCount();
                return new ModelBuffer(RenderSystem.getDevice().createBuffer(
                        () -> "BattleFrame mesh", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()), vertexCount);
            }
        }
    }

    private static void faceX(BufferBuilder vertices, float x, float y0, float y1,
                              float z0, float z1, boolean positive) {
        if (positive) {
            quad(vertices, x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1);
        } else {
            quad(vertices, x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0);
        }
    }

    private static void faceY(BufferBuilder vertices, float x0, float x1, float y,
                              float z0, float z1, boolean positive) {
        if (positive) {
            quad(vertices, x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0);
        } else {
            quad(vertices, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1);
        }
    }

    private static void faceZ(BufferBuilder vertices, float x0, float x1,
                              float y0, float y1, float z, boolean positive) {
        if (positive) {
            quad(vertices, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z);
        } else {
            quad(vertices, x0, y0, z, x0, y1, z, x1, y1, z, x1, y0, z);
        }
    }

    private static void quad(BufferBuilder vertices,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3) {
        vertices.addVertex(x0, y0, z0);
        vertices.addVertex(x1, y1, z1);
        vertices.addVertex(x2, y2, z2);
        vertices.addVertex(x3, y3, z3);
    }

    // 正反面使用完整 alpha-cutout quad，仅沿透明边界按像素生成侧壁。
    private static ModelBuffer createExtrudedImageBuffer(ExtrudedImageKey key) {
        ActorAppearance.SourceRect source = key.source();
        long pixelCount = (long) source.width() * source.height();
        if (pixelCount > 262_144L) {
            throw new IllegalArgumentException("3d image source area is too large for extrusion: " + source.width() + "x" + source.height());
        }
        try (var input = Minecraft.getInstance().getResourceManager().open(key.texture());
             NativeImage image = NativeImage.read(input)) {
            if (source.x() + source.width() > image.getWidth() || source.y() + source.height() > image.getHeight()) {
                throw new IllegalArgumentException("3d image source area exceeds the loaded texture: " + key.texture());
            }
            int visiblePixels = 0;
            for (int y = 0; y < source.height(); y++) {
                for (int x = 0; x < source.width(); x++) {
                    if (opaque(image, source, x, y)) visiblePixels++;
                }
            }
            if (visiblePixels == 0) return ModelBuffer.empty();
            int maximumFaces = 2 + visiblePixels * 4;
            try (ByteBufferBuilder bytes = new ByteBufferBuilder(
                    maximumFaces * 4 * DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.getVertexSize())) {
                BufferBuilder vertices = new BufferBuilder(
                        bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
                float halfDepth = 0.5F;
                appendExtrudedFrontAndBack(vertices, key, halfDepth);
                for (int y = 0; y < source.height(); y++) {
                    for (int x = 0; x < source.width(); x++) {
                        if (!opaque(image, source, x, y)) continue;
                        appendExtrudedPixelSides(vertices, image, key, x, y, halfDepth);
                    }
                }
                try (MeshData mesh = vertices.buildOrThrow()) {
                    int vertexCount = mesh.drawState().vertexCount();
                    return new ModelBuffer(RenderSystem.getDevice().createBuffer(
                            () -> "Battle extruded image " + key.texture(), GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()), vertexCount);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to build extruded battle image " + key.texture(), exception);
        }
    }

    private static void appendExtrudedFrontAndBack(BufferBuilder vertices, ExtrudedImageKey key, float halfDepth) {
        ActorAppearance.SourceRect source = key.source();
        float pixel = 1.0F / 80.0F;
        float x0 = -source.width() * 0.5F * pixel;
        float x1 = source.width() * 0.5F * pixel;
        float z0 = -source.height() * 0.5F * pixel;
        float z1 = source.height() * 0.5F * pixel;
        float u0 = source.x() / (float) key.textureSize().width();
        float u1 = (source.x() + source.width()) / (float) key.textureSize().width();
        float v0 = source.y() / (float) key.textureSize().height();
        float v1 = (source.y() + source.height()) / (float) key.textureSize().height();
        face(vertices, 0.0F, -1.0F, 0.0F,
                x0, -halfDepth, z0, u0, v0, x1, -halfDepth, z0, u1, v0,
                x1, -halfDepth, z1, u1, v1, x0, -halfDepth, z1, u0, v1);
        face(vertices, 0.0F, 1.0F, 0.0F,
                x1, halfDepth, z0, u1, v0, x0, halfDepth, z0, u0, v0,
                x0, halfDepth, z1, u0, v1, x1, halfDepth, z1, u1, v1);
    }

    private static void appendExtrudedPixelSides(BufferBuilder vertices, NativeImage image, ExtrudedImageKey key,
                                                  int x, int y, float halfDepth) {
        ActorAppearance.SourceRect source = key.source();
        float pixel = 1.0F / 80.0F;
        float x0 = (x - source.width() * 0.5F) * pixel;
        float x1 = x0 + pixel;
        float z0 = (y - source.height() * 0.5F) * pixel;
        float z1 = z0 + pixel;
        float u0 = (source.x() + x) / (float) key.textureSize().width();
        float u1 = (source.x() + x + 1) / (float) key.textureSize().width();
        float v0 = (source.y() + y) / (float) key.textureSize().height();
        float v1 = (source.y() + y + 1) / (float) key.textureSize().height();
        float uc = (u0 + u1) * 0.5F;
        float vc = (v0 + v1) * 0.5F;
        if (!opaque(image, source, x - 1, y)) face(vertices, -1.0F, 0.0F, 0.0F, x0, halfDepth, z0, uc, vc, x0, -halfDepth, z0, uc, vc, x0, -halfDepth, z1, uc, vc, x0, halfDepth, z1, uc, vc);
        if (!opaque(image, source, x + 1, y)) face(vertices, 1.0F, 0.0F, 0.0F, x1, -halfDepth, z0, uc, vc, x1, halfDepth, z0, uc, vc, x1, halfDepth, z1, uc, vc, x1, -halfDepth, z1, uc, vc);
        if (!opaque(image, source, x, y - 1)) face(vertices, 0.0F, 0.0F, -1.0F, x0, halfDepth, z0, uc, vc, x1, halfDepth, z0, uc, vc, x1, -halfDepth, z0, uc, vc, x0, -halfDepth, z0, uc, vc);
        if (!opaque(image, source, x, y + 1)) face(vertices, 0.0F, 0.0F, 1.0F, x0, -halfDepth, z1, uc, vc, x1, -halfDepth, z1, uc, vc, x1, halfDepth, z1, uc, vc, x0, halfDepth, z1, uc, vc);
    }

    private static boolean opaque(NativeImage image, ActorAppearance.SourceRect source, int x, int y) {
        return x >= 0 && y >= 0 && x < source.width() && y < source.height()
                && Byte.toUnsignedInt(image.getLuminanceOrAlpha(source.x() + x, source.y() + y)) > 0;
    }

    private static void face(BufferBuilder vertices,
                             float nx, float ny, float nz,
                             float x0, float y0, float z0, float u0, float v0,
                             float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2,
                             float x3, float y3, float z3, float u3, float v3) {
        vertices.addVertex(x0, y0, z0).setUv(u0, v0).setColor(255, 255, 255, 255).setNormal(nx, ny, nz);
        vertices.addVertex(x1, y1, z1).setUv(u1, v1).setColor(255, 255, 255, 255).setNormal(nx, ny, nz);
        vertices.addVertex(x2, y2, z2).setUv(u2, v2).setColor(255, 255, 255, 255).setNormal(nx, ny, nz);
        vertices.addVertex(x3, y3, z3).setUv(u3, v3).setColor(255, 255, 255, 255).setNormal(nx, ny, nz);
    }

    private static GpuTextureView texture(ResourceLocation texture) {
        return Minecraft.getInstance().getTextureManager().getTexture(texture).getTextureView();
    }

    private static Optional<GpuTextureView> materialTexture(ResourceLocation baseTexture) {
        return MaterialTextures.INSTANCE.materialMap(baseTexture).map(Renderer::texture);
    }

    private static Optional<GpuTextureView> emissiveTexture(ResourceLocation baseTexture) {
        return MaterialTextures.INSTANCE.emissiveMap(baseTexture).map(Renderer::texture);
    }

    private static GpuBuffer createUnitQuad() {
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(4 * DefaultVertexFormat.POSITION_TEX.getVertexSize())) {
            BufferBuilder vertices = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_TEX);
            vertices.addVertex(-0.5F, -0.5F, 0.0F).setUv(0.0F, 0.0F);
            vertices.addVertex(-0.5F, 0.5F, 0.0F).setUv(0.0F, 1.0F);
            vertices.addVertex(0.5F, -0.5F, 0.0F).setUv(1.0F, 0.0F);
            vertices.addVertex(0.5F, 0.5F, 0.0F).setUv(1.0F, 1.0F);
            try (MeshData mesh = vertices.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "Battle unit quad", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            }
        }
    }

    private static void closeGlyphs(List<GlyphBatch> glyphs) {
        for (GlyphBatch glyph : glyphs) {
            glyph.buffer().close();
        }
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        return height;
    }

    @Override
    protected void renderToTexture(State state, PoseStack poseStack) {
    }

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected String getTextureLabel() {
        return "Battle Renderer V2";
    }

    @Override
    public void close() {
        this.targets.close();
        this.uniform.close();
        this.volumetricShadows.close();
        this.soulPointLightUniform.close();
        this.lightingUniform.close();
        this.postProcessing.close();
        this.renderModeCrossfade.close();
        this.screenEffects.close();
        this.environmentBackground.close();
        this.environmentLighting.close();
        this.unitQuad.close();
        for (ModelBuffer model : this.models.values()) {
            model.close();
        }
        for (ModelBuffer model : this.extrudedImages.values()) {
            model.close();
        }
        for (ModelBuffer model : this.battleFrames.values()) {
            model.close();
        }
        this.models.clear();
        this.extrudedImages.clear();
        this.battleFrames.clear();
        super.close();
    }

    private record ModelBuffer(GpuBuffer buffer, int vertexCount) implements AutoCloseable {
        static ModelBuffer empty() {
            return new ModelBuffer(null, 0);
        }

        int indexCount() {
            return this.vertexCount / 4 * 6;
        }

        @Override
        public void close() {
            if (this.buffer != null) {
                this.buffer.close();
            }
        }
    }

    private record ExtrudedImageKey(ResourceLocation texture,
                                    ActorAppearance.SourceRect source,
                                    ActorAppearance.TextureSize textureSize) {
    }

    private record ShadowCacheKey(long first, long second) {
    }

    private record GlyphAtlasKey(GpuTextureView texture, boolean intensity) {
    }

    private record GlyphBatch(int commandIndex, GpuTextureView texture, boolean intensity, GpuBuffer buffer, int indexCount) {
    }

    private record PreparedCommand(
            BattleScene.RenderCommand command,
            GpuTextureView texture,
            GpuTextureView specularTexture,
            GpuTextureView emissiveTexture,
            ModelBuffer model,
            List<GlyphBatch> glyphs
    ) {
    }

    private static int countEmissiveDraws(PreparedFrame prepared) {
        int result = 0;
        for (PreparedCommand command : prepared.commands()) {
            ModelBuffer model = command.model();
            if (command.emissiveTexture() != null
                    && supportsMaterialMap(command.command())
                    && (command.command().geometry() == BattleScene.Geometry.UNIT_QUAD
                    || (model != null && model.vertexCount() > 0 && prepared.quadIndices() != null))) {
                result++;
            }
        }
        return result;
    }

    private enum VisualPassMode {
        COLOR,
        EMISSIVE
    }

    private record RenderPlan(
            int width,
            int height,
            RenderModeCrossfade.FramePlan renderMode,
            Lighting.Settings unmodifiedLighting,
            Lighting.Settings renderedLighting,
            BattleScene.Frame frame,
            PostProcessing.FramePlan unmodifiedPostProcessing,
            PostProcessing.FramePlan renderedPostProcessing,
            PostProcessing.FramePlan allocatedPostProcessing,
            EnvironmentBackground.FramePlan environmentBackground,
            EnvironmentLighting.FramePlan environmentLighting,
            Lighting.LightSpace lightSpace,
            VolumetricShadows.Plan volumetricShadows,
            RenderTargets.TargetSet targets
    ) {
    }

    private record PreparedFrame(
            List<PreparedCommand> commands,
            List<GlyphBatch> glyphs,
            GpuBuffer quadIndices,
            VertexFormat.IndexType quadIndexType,
            EnvironmentLighting.FramePlan environmentLighting,
            EnvironmentCaptureSnapshot.DebugTexture environmentDebugTexture
    ) implements AutoCloseable {
        PreparedFrame {
            commands = List.copyOf(commands);
            glyphs = List.copyOf(glyphs);
            Objects.requireNonNull(environmentLighting, "environmentLighting");
        }

        @Override
        public void close() {
            closeGlyphs(this.glyphs);
        }
    }

    private record TransformedText(TextRenderable delegate, Matrix4f pose) implements TextRenderable {
        @Override public void render(Matrix4f ignored, com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, boolean noDepth) { this.delegate.render(this.pose, consumer, light, noDepth); }
        @Override public net.minecraft.client.renderer.RenderType renderType(Font.DisplayMode mode) { return this.delegate.renderType(mode); }
        @Override public GpuTextureView textureView() { return this.delegate.textureView(); }
        @Override public RenderPipeline guiPipeline() { return this.delegate.guiPipeline(); }
        @Override public float left() { return this.delegate.left(); }
        @Override public float top() { return this.delegate.top(); }
        @Override public float right() { return this.delegate.right(); }
        @Override public float bottom() { return this.delegate.bottom(); }
    }
}
