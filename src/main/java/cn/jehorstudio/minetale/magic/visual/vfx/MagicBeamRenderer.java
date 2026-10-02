package cn.jehorstudio.minetale.magic.visual.vfx;

import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.MagicConfig;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

// 冻结光束帧，统一管理材质表面、独立核心与热扭曲的生命周期。
public final class MagicBeamRenderer {
    private static final ContextKey<List<BeamFrame>> FRAMES = new ContextKey<>(Magic.id("magic_beams"));
    private static final int BEAM_UNIFORM_BYTES = 224;
    // 与 beam.vsh 的 32 边圆柱（含端盖）、16×8 炮口球一致
    private static final int BEAM_VERTICES = 32 * 12 + 16 * 8 * 6;
    private static final RenderPipeline CORE = corePipeline("beam_core").build();
    private static final RenderPipeline CORE_MASK = corePipeline("beam_core_mask").withShaderDefine("MASK_ONLY").build();
    private static RenderPipeline.Builder corePipeline(String name) {
        return RenderPipeline.builder().withLocation(Magic.id("pipeline/magic/" + name))
            .withVertexShader(Magic.id("core/magic/beam")).withFragmentShader(Magic.id("core/magic/beam"))
            .withSampler("SceneDepthSampler").withSampler("BeamDepthSampler")
            .withSampler("BeforeHandDepthSampler").withSampler("AfterHandDepthSampler")
            .withUniform("MagicBeam", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withCull(false).withDepthWrite(true).withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
    }
    private static final RenderPipeline DOWNSAMPLE = bloomPipeline("beam_downsample").withShaderDefine("DOWNSAMPLE").build();
    private static final RenderPipeline BLUR_HORIZONTAL = bloomPipeline("beam_blur_horizontal").withShaderDefine("HORIZONTAL").build();
    private static final RenderPipeline BLUR_VERTICAL = bloomPipeline("beam_blur_vertical").build();
    private static final RenderPipeline LENS_HORIZONTAL = bloomPipeline("beam_lens_horizontal")
            .withShaderDefine("LENS_FILTER").withShaderDefine("HORIZONTAL").build();
    private static final RenderPipeline LENS_VERTICAL = bloomPipeline("beam_lens_vertical").withShaderDefine("LENS_FILTER").build();
    private static final RenderPipeline LENS = RenderPipeline.builder().withLocation(Magic.id("pipeline/magic/beam_lens"))
            .withVertexShader(Magic.id("core/battle/post/fullscreen")).withFragmentShader(Magic.id("core/magic/beam"))
            .withShaderDefine("DISTORTION")
            .withSampler("SceneDepthSampler").withSampler("BeamDepthSampler")
            .withSampler("BeforeHandDepthSampler").withSampler("AfterHandDepthSampler")
            .withUniform("MagicBeam", UniformType.UNIFORM_BUFFER).withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE, SourceFactor.ONE, DestFactor.ONE))
            .withCull(false).withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
    private static final RenderPipeline COMPOSITE = compositePipeline("beam_composite").build();
    private static final RenderPipeline HEAT_COMPOSITE = compositePipeline("beam_heat_composite").withShaderDefine("SCENE_SURFACE").build();
    private static RenderPipeline.Builder compositePipeline(String name) {
        return RenderPipeline.builder()
            .withLocation(Magic.id("pipeline/magic/" + name))
            .withVertexShader(Magic.id("core/battle/post/fullscreen"))
            .withFragmentShader(Magic.id("core/magic/beam_composite"))
            .withSampler("CoreSampler").withSampler("CoreDepthSampler").withSampler("GlowSampler")
            .withSampler("SceneSampler").withSampler("DistortionSampler")
            .withUniform("MagicBeam", UniformType.UNIFORM_BUFFER)
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ZERO, DestFactor.ONE))
            .withCull(false).withDepthWrite(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
    }

    private static RenderPipeline.Builder bloomPipeline(String name) {
        return RenderPipeline.builder().withLocation(Magic.id("pipeline/magic/" + name))
                .withVertexShader(Magic.id("core/battle/post/fullscreen"))
                .withFragmentShader(Magic.id("core/magic/beam_bloom"))
                .withSampler("SourceSampler").withCull(false).withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES);
    }

    private static final MagicBeamRenderer INSTANCE = new MagicBeamRenderer();
    private final Matrix4f projection = new Matrix4f();
    private final java.util.Map<Integer, MagicBeamOcclusion> occlusions = new java.util.HashMap<>();
    private final MagicBeamSurface surface = new MagicBeamSurface();
    private boolean surfaceRendered;
    private boolean hasProjection;
    private RenderLevelStageEvent.AfterEntities pending;
    private boolean ready;
    private RenderLevelStageEvent.AfterEntities captured;
    private final TextureSnapshot sceneDepth = new TextureSnapshot("Magic opaque scene depth");
    private final TextureSnapshot beforeHandDepth = new TextureSnapshot("Magic before translucent hand depth");
    private boolean handCaptured;
    private final TextureSnapshot sceneColor = new TextureSnapshot("Magic final scene color");
    private GpuBufferSlice compositeParameters;
    private GpuBufferSlice fog;
    private TextureTarget core;
    private TextureTarget glow;
    private TextureTarget blur;
    private TextureTarget distortion;
    private boolean distortionEnabled;
    private double distortionClock = Double.NaN;
    private double distortionPhase;
    private MappableRingBuffer uniform;

    public static void register(IEventBus bus) {
        bus.addListener((RegisterRenderPipelinesEvent event) -> {
            event.registerPipeline(CORE);
            event.registerPipeline(CORE_MASK);
            event.registerPipeline(DOWNSAMPLE);
            event.registerPipeline(BLUR_HORIZONTAL);
            event.registerPipeline(BLUR_VERTICAL);
            event.registerPipeline(COMPOSITE);
            event.registerPipeline(HEAT_COMPOSITE);
            event.registerPipeline(LENS);
            event.registerPipeline(LENS_HORIZONTAL);
            event.registerPipeline(LENS_VERTICAL);
        });
        bus.addListener((AddClientReloadListenersEvent event) -> event.addListener(Magic.id("magic_beams"),
                (shared, prepare, barrier, apply) -> CompletableFuture.completedFuture(true)
                        .thenCompose(barrier::wait).thenRunAsync(INSTANCE::close, apply)));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ExtractLevelRenderStateEvent event) ->
                event.getRenderState().setRenderData(FRAMES, List.of()));
        NeoForge.EVENT_BUS.addListener((FrameGraphSetupEvent event) -> {
            INSTANCE.pending = null;
            INSTANCE.captured = null;
            INSTANCE.ready = false;
            INSTANCE.handCaptured = false;
            INSTANCE.surfaceRendered = false;
            INSTANCE.projection.set(event.getProjectionMatrix());
            INSTANCE.hasProjection = true;
        });
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterEntities event) -> {
            INSTANCE.renderSurface(event);
            INSTANCE.pending = event;
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (RenderLevelStageEvent.AfterLevel event) -> INSTANCE.present());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> INSTANCE.close());
    }

    // 在实际透明地形提交前消费一次。此时包括实体和 Iris 提前绘制的实心手部，但不含水、云。
    public static void beforeTranslucentTerrain() {
        var event = INSTANCE.pending;
        INSTANCE.pending = null;
        if (event != null) INSTANCE.captureDepth(event);
    }

    private void captureDepth(RenderLevelStageEvent.AfterEntities event) {
        captured = event;
        var frames = event.getLevelRenderState().getRenderData(FRAMES);
        if (frames == null || frames.isEmpty()) return;
        var source = Minecraft.getInstance().getMainRenderTarget().getDepthTexture();
        if (source == null) { captured = null; return; }
        // 此处只复制，不开启 RenderPass；切换管线会破坏 Iris 已为透明地形准备的绘制状态。
        sceneDepth.copy(source);
        fog = RenderSystem.getShaderFog();
    }

    // Iris 可选桥接只记录实际透明手部 pass 的起点，最终深度由 AfterLevel 读取。
    public static void captureTranslucentHandDepth() {
        if (INSTANCE.captured == null) return;
        var frames = INSTANCE.captured.getLevelRenderState().getRenderData(FRAMES);
        if (frames == null || frames.isEmpty()) return;
        INSTANCE.beforeHandDepth.copy(Minecraft.getInstance().getMainRenderTarget().getDepthTexture());
        INSTANCE.handCaptured = true;
    }

    /**
     * 在 ExtractLevelRenderStateEvent 中提交本帧光束；可由多个法术追加。
     * 提供方使用 NORMAL 或更低优先级；HIGHEST 为本模块清空上一帧的阶段。
     * 本模块冻结帧列表，负责遮挡纹理、离屏目标和退出／重载清理；调用方无需维护 GPU 资源。
     * @param event 当前 Level 的渲染提取事件
     * @param frames 当前可见光束；id 必须为本 Level 唯一且在一束光的生命周期内稳定的实体 ID
     */
    public static void submit(ExtractLevelRenderStateEvent event, List<BeamFrame> frames) {
        List<BeamFrame> combined = new ArrayList<>();
        List<BeamFrame> previous = event.getRenderState().getRenderData(FRAMES);
        if (previous != null) combined.addAll(previous);
        combined.addAll(frames);
        event.getRenderState().setRenderData(FRAMES, List.copyOf(combined));
    }

    private void renderSurface(RenderLevelStageEvent.AfterEntities event) {
        if (!hasProjection || event.getLevelRenderer() != Minecraft.getInstance().levelRenderer) return;
        var frames = event.getLevelRenderState().getRenderData(FRAMES);
        var active = new java.util.HashSet<Integer>();
        if (frames != null) for (var frame : frames) active.add(frame.id);
        surface.retain(active);
        occlusions.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
        if (active.isEmpty()) return;
        if (!surface.active()) { surface.close(); return; }
        var mc = Minecraft.getInstance();
        for (var frame : frames) occlusions.computeIfAbsent(frame.id, id -> new MagicBeamOcclusion(frame.extent))
                .update(mc.level, frame.occlusionOrigin, frame.axis, frame.extent, frame.range);
        surfaceRendered = surface.render(event, frames, occlusions);
    }

    private void render(RenderLevelStageEvent.AfterEntities event) {
        Minecraft mc = Minecraft.getInstance();
        if (!hasProjection || event.getLevelRenderer() != mc.levelRenderer) return;
        if (surfaceRendered && MagicConfig.BEAM_DISTORTION_STRENGTH <= 0) return;
        List<BeamFrame> frames = event.getLevelRenderState().getRenderData(FRAMES);
        if (frames == null || frames.isEmpty()) return;
        GpuTextureView depth = sceneDepth.view;
        if (depth == null) return;
        ensureResources(depth.getWidth(0), depth.getHeight(0));
        Matrix4f viewProjection = new Matrix4f(projection).mul(event.getModelViewMatrix());
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        List<PreparedBeam> prepared = new ArrayList<>();
        for (BeamFrame frame : frames) {
            ScreenRect rect = projectBounds(frame.bounds.inflate(MagicConfig.BEAM_DISTORTION_RADIUS * frame.radius), camera, viewProjection);
            if (rect == null) continue;
            prepared.add(new PreparedBeam(frame, occlusions.computeIfAbsent(frame.id, id -> new MagicBeamOcclusion(frame.extent)), rect));
        }
        if (prepared.isEmpty()) return;
        // 只用于核心深度完全相同的确定性选择
        prepared.sort(java.util.Comparator.comparingInt(beam -> beam.frame.id));
        if (!surfaceRendered) for (PreparedBeam beam : prepared) {
            BeamFrame frame = beam.frame;
            beam.occlusion.update(mc.level, frame.occlusionOrigin, frame.axis, frame.extent, frame.range);
        }
        int alignment = RenderSystem.getDevice().getUniformOffsetAlignment();
        int stride = (BEAM_UNIFORM_BYTES + alignment - 1) / alignment * alignment;
        GpuBuffer parameters = uploadBeamParameters(prepared, stride, viewProjection, camera);
        compositeParameters = parameters.slice(0, BEAM_UNIFORM_BYTES);
        renderBeams(prepared, parameters, stride, depth, false);
        if (!surfaceRendered) {
            filter(DOWNSAMPLE, core, glow);
            filter(BLUR_HORIZONTAL, glow, blur);
            filter(BLUR_VERTICAL, blur, glow);
        }
        if (distortionEnabled) {
            renderBeams(prepared, parameters, stride, depth, true);
            filter(LENS_HORIZONTAL, distortion, blur);
            filter(LENS_VERTICAL, blur, distortion);
        }
        ready = true;
        uniform.rotate();
    }

    private GpuBuffer uploadBeamParameters(List<PreparedBeam> prepared, int stride, Matrix4f viewProjection, Vec3 camera) {
        Matrix4f inverseViewProjection = new Matrix4f(viewProjection).invert();
        int bytes = stride * prepared.size();
        if (uniform == null || uniform.size() < bytes) {
            if (uniform != null) uniform.close();
            uniform = new MappableRingBuffer(() -> "Magic beam uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, bytes);
        }
        GpuBuffer parameters = uniform.currentBuffer();
        var mc = Minecraft.getInstance();
        float distortionRadius = MagicConfig.BEAM_DISTORTION_RADIUS;
        float distortionStrength = MagicConfig.BEAM_DISTORTION_STRENGTH;
        float distortionFalloff = MagicConfig.BEAM_DISTORTION_FALLOFF;
        float distortionWave = MagicConfig.BEAM_DISTORTION_WAVE;
        distortionEnabled = distortionStrength > 0;
        // 折射共享世界时钟
        double now = mc.level.getGameTime() + (double) mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (!Double.isNaN(distortionClock)) {
            // 速度只控制相位增量
            distortionPhase = (distortionPhase + Math.clamp(now - distortionClock, 0, 1)
                    * MagicConfig.BEAM_DISTORTION_SPEED / 20.0) % (Math.PI * 2);
        }
        distortionClock = now;
        try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder().mapBuffer(parameters, false, true)) {
            for (int index = 0; index < prepared.size(); index++) {
                BeamFrame frame = prepared.get(index).frame;
                Vec3 relative = frame.origin.subtract(camera);
                // std140: 2 mat4 + 6 vec4；每束独占对齐 slice，帧末轮换一次。
                mapped.data().position(index * stride);
                Std140Builder.intoBuffer(mapped.data()).putMat4f(viewProjection).putMat4f(inverseViewProjection)
                        .putVec4((float) relative.x, (float) relative.y, (float) relative.z, frame.radius)
                        .putVec4((float) frame.axis.x, (float) frame.axis.y, (float) frame.axis.z, frame.length)
                        .putVec4(core.width, core.height, frame.time, 0)
                        .putVec4(frame.muzzleRadius, frame.muzzleOffset, frame.extent, frame.retraction)
                        .putVec4((float) frame.occlusionOrigin.subtract(frame.origin).dot(frame.axis), handCaptured ? 1 : 0,
                                (float) distortionPhase, 0)
                        // 基准半径为 1 格；使用当前可见半径，使蓄力尺寸与收束动画同步缩放折射。
                        .putVec4(distortionRadius * frame.radius, distortionStrength * frame.radius, distortionFalloff, distortionWave);
            }
        }
        return parameters;
    }

    private void renderBeams(List<PreparedBeam> prepared, GpuBuffer parameters, int stride,
                             GpuTextureView depth, boolean lens) {
        TextureTarget destination = lens ? distortion : core;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var pass = lens
                ? encoder.createRenderPass(() -> "MineTale continuous beam distortion", destination.getColorTextureView(), OptionalInt.of(0))
                : encoder.createRenderPass(() -> "MineTale opaque beam geometry", destination.getColorTextureView(), OptionalInt.of(0),
                        destination.getDepthTextureView(), OptionalDouble.of(1));
        try (pass) {
            pass.setViewport(0, 0, destination.width, destination.height);
            pass.setPipeline(lens ? LENS : surfaceRendered ? CORE_MASK : CORE);
            pass.setUniform("Fog", fog);
            pass.bindSampler("SceneDepthSampler", depth);
            pass.bindSampler("BeforeHandDepthSampler", handCaptured ? beforeHandDepth.view : depth);
            pass.bindSampler("AfterHandDepthSampler", handCaptured ? Minecraft.getInstance().getMainRenderTarget().getDepthTextureView() : depth);
            for (int index = 0; index < prepared.size(); index++) {
                PreparedBeam beam = prepared.get(index);
                ScreenRect rect = beam.rect;
                pass.enableScissor(rect.pixelX(destination.width), rect.pixelY(destination.height),
                        Math.max(1, rect.right(destination.width) - rect.pixelX(destination.width)),
                        Math.max(1, rect.top(destination.height) - rect.pixelY(destination.height)));
                pass.setUniform("MagicBeam", parameters.slice(index * stride, BEAM_UNIFORM_BYTES));
                pass.bindSampler("BeamDepthSampler", beam.occlusion.texture().getTextureView());
                pass.draw(0, lens ? 3 : BEAM_VERTICES);
            }
        }
    }

    private void filter(RenderPipeline pipeline, TextureTarget source, TextureTarget destination) {
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "MineTale beam bloom", destination.getColorTextureView(), OptionalInt.empty())) {
            pass.setViewport(0, 0, destination.width, destination.height);
            pass.setPipeline(pipeline);
            pass.bindSampler("SourceSampler", source.getColorTextureView());
            pass.draw(0, 3);
        }
    }

    private void present() {
        pending = null;
        var event = captured;
        captured = null;
        if (event != null) render(event);
        if (!ready) return;
        ready = false;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width != core.width || target.height != core.height) return;
        // 复制光影最终颜色，采样源与写入目标分离；所有光束共享一次背景复制。
        if (distortionEnabled) {
            sceneColor.copy(target.getColorTexture());
            sceneColor.texture.setTextureFilter(FilterMode.LINEAR, false);
        }
        // 核心覆盖、外晕加法在同一个 pass 完成
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "MineTale final beam composite", target.getColorTextureView(), OptionalInt.empty())) {
            pass.setViewport(0, 0, target.width, target.height);
            pass.setPipeline(surfaceRendered ? HEAT_COMPOSITE : COMPOSITE);
            pass.setUniform("Fog", fog);
            pass.bindSampler("CoreSampler", core.getColorTextureView());
            pass.setUniform("MagicBeam", compositeParameters);
            pass.bindSampler("SceneSampler", distortionEnabled ? sceneColor.view : core.getColorTextureView());
            pass.bindSampler("DistortionSampler", distortion.getColorTextureView());
            pass.bindSampler("CoreDepthSampler", core.getDepthTextureView());
            pass.bindSampler("GlowSampler", glow.getColorTextureView());
            pass.draw(0, 3);
        }
    }

    private void ensureResources(int width, int height) {
        if (core != null && core.width == width && core.height == height) return;
        if (core == null) {
            core = new TextureTarget("MineTale beam cores", width, height, true);
            glow = new TextureTarget("MineTale beam glow", Math.max(1, (width + 3) / 4), Math.max(1, (height + 3) / 4), false);
            blur = new TextureTarget("MineTale beam blur", glow.width, glow.height, false);
            distortion = new TextureTarget("MineTale beam distortion", glow.width, glow.height, false);
        } else {
            core.resize(width, height);
            glow.resize(Math.max(1, (width + 3) / 4), Math.max(1, (height + 3) / 4));
            blur.resize(glow.width, glow.height);
            distortion.resize(glow.width, glow.height);
        }
        glow.getColorTexture().setTextureFilter(FilterMode.LINEAR, false);
        blur.getColorTexture().setTextureFilter(FilterMode.LINEAR, false);
        distortion.getColorTexture().setTextureFilter(FilterMode.LINEAR, false);
    }

    private void close() {
        distortionClock = Double.NaN;
        distortionPhase = 0;
        surface.close();
        surfaceRendered = false;
        occlusions.values().forEach(MagicBeamOcclusion::close);
        occlusions.clear();
        sceneDepth.close();
        sceneColor.close();
        compositeParameters = null;
        beforeHandDepth.close();
        handCaptured = false;
        captured = null;
        fog = null;
        pending = null;
        ready = false;
        if (core != null) { core.destroyBuffers(); core = null; }
        if (glow != null) { glow.destroyBuffers(); glow = null; }
        if (blur != null) { blur.destroyBuffers(); blur = null; }
        if (distortion != null) { distortion.destroyBuffers(); distortion = null; }
        if (uniform != null) { uniform.close(); uniform = null; }
        hasProjection = false;
    }

    private static ScreenRect projectBounds(AABB box, Vec3 camera, Matrix4f matrix) {
        float minX = 1, minY = 1, maxX = -1, maxY = -1;
        int front = 0, behind = 0;
        for (int i = 0; i < 8; i++) {
            Vector4f p = new Vector4f((float) (((i & 1) == 0 ? box.minX : box.maxX) - camera.x),
                    (float) (((i & 2) == 0 ? box.minY : box.maxY) - camera.y),
                    (float) (((i & 4) == 0 ? box.minZ : box.maxZ) - camera.z), 1);
            matrix.transform(p);
            if (p.w <= 1.0E-4F) { behind++; continue; }
            front++;
            minX = Math.min(minX, p.x / p.w); maxX = Math.max(maxX, p.x / p.w);
            minY = Math.min(minY, p.y / p.w); maxY = Math.max(maxY, p.y / p.w);
        }
        if (front == 0) return null;
        if (behind > 0) return new ScreenRect(-1, -1, 1, 1);
        if (maxX < -1 || minX > 1 || maxY < -1 || minY > 1) return null;
        return new ScreenRect(Math.clamp(minX - 0.005F, -1, 1), Math.clamp(minY - 0.005F, -1, 1),
                Math.clamp(maxX + 0.005F, -1, 1), Math.clamp(maxY + 0.005F, -1, 1));
    }

    /**
     * 不可变光束采样。origin 为表现炮口，occlusionOrigin 为传播遮挡的世界基准，axis 为单位方向。
     * 两个原点只能沿 axis 偏移；以不同基准表达动画时，调用方负责补偿 length。
     * radius/length 是当前可见尺寸；length 可包含炮口相对固定发射基准的动画位移补偿。
     * extent/range 是遮挡图横截面半宽和从 occlusionOrigin 计算的最大射程，收口时保持不变。
     * time 单位为秒，retraction 为 0..1；muzzleRadius/muzzleOffset 描述球形首端。
     * 不透明核心没有独立透明度或亮度渐隐；退场通过当前尺寸表达。
     * bounds 必须覆盖束身与球形首端；最终材质颜色和 bloom 由当前渲染环境决定。
     * 所有尺寸有限非负，正尺寸光束才应提交。
     * 同一 id 的 extent 在法术存续期间保持不变，决定遮挡图的固定分辨率。
     */
    public record BeamFrame(int id, float extent, float range, Vec3 origin, Vec3 occlusionOrigin, Vec3 axis, float radius, float length, float time, float retraction,
                             float muzzleRadius, float muzzleOffset, AABB bounds) {
        public BeamFrame {
            if (!finite(origin) || !finite(occlusionOrigin) || !finite(axis)
                    || Math.abs(axis.lengthSqr() - 1) > 1.0E-4
                    || !Float.isFinite(extent) || extent <= 0
                    || !Float.isFinite(range) || range <= 0 || range > 32767
                    || !Float.isFinite(radius) || radius <= 0 || radius > extent
                    || !Float.isFinite(length) || length <= 0
                    || !Float.isFinite(time) || !unit(retraction)
                    || !Float.isFinite(muzzleRadius) || muzzleRadius < 0
                    || !Float.isFinite(muzzleOffset) || muzzleOffset < 0
                    || bounds == null
                    || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.minY) || !Double.isFinite(bounds.minZ)
                    || !Double.isFinite(bounds.maxX) || !Double.isFinite(bounds.maxY) || !Double.isFinite(bounds.maxZ)) {
                throw new IllegalArgumentException("光束帧必须具有有限的坐标、单位方向和有效尺寸");
            }
        }

        private static boolean finite(Vec3 value) {
            return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
        }

        private static boolean unit(float value) {
            return Float.isFinite(value) && value >= 0 && value <= 1;
        }
    }
    private record ScreenRect(float minX, float minY, float maxX, float maxY) {
        int pixelX(int width) { return Math.clamp((int) Math.floor((minX * 0.5 + 0.5) * width), 0, width - 1); }
        int pixelY(int height) { return Math.clamp((int) Math.floor((minY * 0.5 + 0.5) * height), 0, height - 1); }
        int right(int width) { return Math.clamp((int) Math.ceil((maxX * 0.5 + 0.5) * width), 1, width); }
        int top(int height) { return Math.clamp((int) Math.ceil((maxY * 0.5 + 0.5) * height), 1, height); }
    }

    private static final class TextureSnapshot implements AutoCloseable {
        private final String label;
        private GpuTexture texture;
        private GpuTextureView view;

        private TextureSnapshot(String label) { this.label = label; }

        private void copy(GpuTexture source) {
            int width = source.getWidth(0), height = source.getHeight(0);
            if (texture == null || texture.getWidth(0) != width || texture.getHeight(0) != height
                    || texture.getFormat() != source.getFormat()) {
                close();
                texture = RenderSystem.getDevice().createTexture(() -> label,
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        source.getFormat(), width, height, 1, 1);
                texture.setTextureFilter(FilterMode.NEAREST, false);
                view = RenderSystem.getDevice().createTextureView(texture);
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, texture, 0, 0, 0, 0, 0, width, height);
        }

        @Override public void close() {
            if (view != null) { view.close(); view = null; }
            if (texture != null) { texture.close(); texture = null; }
        }
    }

    private record PreparedBeam(BeamFrame frame, MagicBeamOcclusion occlusion, ScreenRect rect) {}

}
