package cn.jehorstudio.minetale.magic.spell.gasterblaster.client;

import cn.jehorstudio.minetale.magic.Magic;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicScreenDoor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.util.RenderUtil;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

// GB 共享静态几何 + 每实例骨骼姿态，GPU 物化 NEW_ENTITY 顶点后沿实体材质路径绘制。
// CPU 只处理骨骼，不重复遍历、旋转和上传每个立方体的顶点。
final class GasterGpuModel {
    private static final List<String> ENTITY_ATTRIBUTES = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");
    private static final GasterGpuModel INSTANCE = new GasterGpuModel();
    private static final int WIDTH = 1008; // 每行同时对齐 RGBA8 像素和 36 字节实体顶点。
    private static final RenderPipeline BAKE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Magic.id("pipeline/magic/model_bake"))
            .withVertexShader(Magic.id("core/battle/post/fullscreen"))
            .withFragmentShader(Magic.id("core/magic/model_bake"))
            .withSampler("Geometry").withSampler("Bones")
            .withUniform("ModelBake", UniformType.UNIFORM_BUFFER).withCull(false).build();
    private BakedGeoModel model;
    private List<GeoBone> bones = List.of();
    private int bodyVertices;
    private int eyeVertices;
    private DynamicTexture geometry;
    private DynamicTexture poses;
    private TextureTarget bytes;
    private GpuBuffer vertices;
    private MappableRingBuffer uniform;
    private final List<float[]> instances = new ArrayList<>();

    static boolean supports(RenderType type) {
        // Iris 扩展为 54 字节且拥有独立阴影阶段。这里在采样、分配之前回到原实体提交路径，
        return standard(type.pipeline().getVertexFormat())
                && standard(RenderPipelines.ENTITY_CUTOUT.getVertexFormat());
    }

    private static boolean standard(VertexFormat format) {
        return format.getVertexSize() == 36 && format.getElementAttributeNames().equals(ENTITY_ATTRIBUTES);
    }

    static void register(IEventBus bus) {
        bus.addListener((RegisterRenderPipelinesEvent event) -> event.registerPipeline(BAKE));
        bus.addListener((AddClientReloadListenersEvent event) -> event.addListener(Magic.id("gaster_gpu_model"),
                (shared, prepare, barrier, apply) -> CompletableFuture.completedFuture(true)
                        .thenCompose(barrier::wait).thenRunAsync(INSTANCE::close, apply)));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterEntities event) -> INSTANCE.render(event));
        // 每个世界帧独立收集；上一帧若因渲染取消未消费，不能在下一帧重放它的实例。
        NeoForge.EVENT_BUS.addListener((FrameGraphSetupEvent event) -> {
            INSTANCE.instances.clear();
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> INSTANCE.close());
    }

    static void submit(BakedGeoModel model, PoseStack pose, int color, int light, int overlay) {
        INSTANCE.capture(model, pose, color, light, overlay);
    }

    private void capture(BakedGeoModel source, PoseStack pose, int color, int light, int overlay) {
        if (model != source) {
            close();
            model = source;
            ArrayList<GeoBone> ordered = new ArrayList<>();
            source.topLevelBones().forEach(b -> collect(b, ordered));
            bones = List.copyOf(ordered);
            bakeGeometry();
        }
        // 每骨骼一行：16 个位置矩阵分量、9 个法线矩阵分量、颜色／光照／Overlay 三个原始位字。
        // 状态在提交时冻结
        float[] data = new float[bones.size() * 32];
        int[] cursor = {0};
        for (GeoBone bone : model.topLevelBones()) captureBone(bone, pose, data, cursor, color, light, overlay, false);
        instances.add(data);
    }

    private static void collect(GeoBone bone, List<GeoBone> ordered) {
        ordered.add(bone);
        bone.getChildBones().forEach(child -> collect(child, ordered));
    }

    private void captureBone(GeoBone bone, PoseStack pose, float[] data, int[] cursor,
            int color, int light, int overlay, boolean hidden) {
        int offset = cursor[0]++ * 32;
        pose.pushPose();
        RenderUtil.prepMatrixForBone(pose, bone);
        pose.last().pose().get(data, offset);
        pose.last().normal().get(data, offset + 16);
        int abgr = color & 0xFF00FF00 | (color >> 16 & 255) | (color & 255) << 16;
        data[offset + 25] = Float.intBitsToFloat(hidden || bone.isHidden() ? 0 : abgr);
        data[offset + 26] = Float.intBitsToFloat(isEye(bone) ? LightTexture.FULL_BRIGHT : light);
        data[offset + 27] = Float.intBitsToFloat(overlay);
        for (GeoBone child : bone.getChildBones())
            captureBone(child, pose, data, cursor, color, light, overlay, hidden || bone.isHidingChildren());
        pose.popPose();
    }

    private static boolean isEye(GeoBone bone) {
        return bone.getName().equals("left_eye") || bone.getName().equals("right_eye");
    }

    private void bakeGeometry() {
        ArrayList<float[]> points = new ArrayList<>();
        for (boolean eyes : new boolean[]{false, true}) {
            for (int b = 0; b < bones.size(); b++) {
                GeoBone bone = bones.get(b);
                if (isEye(bone) != eyes) continue;
                for (var cube : bone.getCubes()) {
                    PoseStack pose = new PoseStack();
                    RenderUtil.translateToPivotPoint(pose, cube);
                    RenderUtil.rotateMatrixAroundCube(pose, cube);
                    RenderUtil.translateAwayFromPivotPoint(pose, cube);
                    for (var quad : cube.quads()) {
                        if (quad == null) continue;
                        Vector3f normal = pose.last().normal().transform(new Vector3f(quad.normal()));
                        for (var vertex : quad.vertices()) {
                            Vector3f p = pose.last().pose().transformPosition(new Vector3f(vertex.position()));
                            points.add(new float[]{p.x, p.y, p.z, normal.x, normal.y, normal.z,
                                    vertex.texU(), vertex.texV(), b,
                                    cube.size().y() == 0 || cube.size().z() == 0 ? 1 : 0,
                                    cube.size().x() == 0 || cube.size().z() == 0 ? 1 : 0,
                                    cube.size().x() == 0 || cube.size().y() == 0 ? 1 : 0});
                        }
                    }
                }
            }
            if (!eyes) bodyVertices = points.size();
        }
        eyeVertices = points.size() - bodyVertices;
        NativeImage image = new NativeImage(WIDTH, Math.ceilDiv(points.size() * 12, WIDTH), false);
        for (int v = 0; v < points.size(); v++) for (int c = 0; c < 12; c++) {
            int i = v * 12 + c;
            image.setPixelABGR(i % WIDTH, i / WIDTH, Float.floatToRawIntBits(points.get(v)[c]));
        }
        geometry = new DynamicTexture(() -> "GB static geometry", image);
        geometry.setFilter(false, false);
        uniform = new MappableRingBuffer(() -> "GB vertex bake", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 16);
    }

    private void render(RenderLevelStageEvent.AfterEntities event) {
        if (instances.isEmpty()) return;
        int count = instances.size();
        int total = count * (bodyVertices + eyeVertices);
        int rows = Math.ceilDiv(total * 36, WIDTH * 4);
        try {
            if (poses == null || poses.getPixels().getHeight() < count * bones.size()) {
                if (poses != null) poses.close();
                poses = new DynamicTexture("GB bone palette", 32, capacity(count * bones.size()), false);
                poses.setFilter(false, false);
            }
            for (int i = 0; i < count; i++) {
                float[] data = instances.get(i);
                for (int j = 0; j < data.length; j++) poses.getPixels().setPixelABGR(j % 32,
                        i * bones.size() + j / 32, Float.floatToRawIntBits(data[j]));
            }
            poses.upload();
            if (bytes == null || bytes.height < rows) {
                if (bytes != null) { bytes.destroyBuffers(); vertices.close(); }
                bytes = new TextureTarget("GB vertex bytes", WIDTH, capacity(rows), false);
                vertices = RenderSystem.getDevice().createBuffer(() -> "GB entity vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, WIDTH * bytes.height * 4);
            }
            var buffer = uniform.currentBuffer();
            try (var mapped = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, false, true)) {
                mapped.data().order(ByteOrder.nativeOrder()).putInt(bodyVertices).putInt(eyeVertices).putInt(count).putInt(bones.size());
            }
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "GB materialize vertices", bytes.getColorTextureView(), OptionalInt.empty())) {
                pass.setPipeline(BAKE);
                pass.bindSampler("Geometry", geometry.getTextureView());
                pass.bindSampler("Bones", poses.getTextureView());
                pass.setUniform("ModelBake", buffer);
                pass.setViewport(0, 0, WIDTH, rows);
                pass.draw(0, 3);
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(bytes.getColorTexture(), vertices,
                    0, () -> {}, 0, 0, 0, WIDTH, rows);
            draw(event, false, 0, bodyVertices * count);
            draw(event, true, bodyVertices * count, eyeVertices * count);
            uniform.rotate();
        } finally {
            instances.clear();
        }
    }

    private void draw(RenderLevelStageEvent.AfterEntities event, boolean eyes, int start, int count) {
        var type = MagicScreenDoor.renderType(Magic.id("textures/magic/gaster_blaster.png"), eyes);
        if (type.pipeline().getVertexFormat().getVertexSize() != 36)
            throw new IllegalStateException("GB 实体顶点布局在同帧内发生变化");
        type.setupRenderState();
        try {
            var target = Minecraft.getInstance().getMainRenderTarget();
            var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
            var depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
            var transform = RenderSystem.getDynamicUniforms().writeTransform(event.getModelViewMatrix(),
                    new Vector4f(1), new Vector3f(), new Matrix4f(), 1);
            var sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            var index = sequential.getBuffer(VertexFormat.Mode.QUADS.indexCount(start + count));
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "GB entity batch", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                pass.setPipeline(type.pipeline());
                var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transform);
                pass.setVertexBuffer(0, vertices);
                for (int i = 0; i < 3; i++) {
                    var texture = RenderSystem.getShaderTexture(i);
                    if (texture != null) pass.bindSampler("Sampler" + i, texture);
                }
                pass.setIndexBuffer(index, sequential.type());
                pass.drawIndexed(0, VertexFormat.Mode.QUADS.indexCount(start), VertexFormat.Mode.QUADS.indexCount(count), 1);
            }
        } finally { type.clearRenderState(); }
    }

    private static int capacity(int required) {
        int maximum = RenderSystem.getDevice().getMaxTextureSize();
        if (required > maximum) throw new IllegalStateException("GB 顶点批次超过 GPU 纹理尺寸上限: " + required);
        return Math.min(maximum, Math.max(1, Integer.highestOneBit(required - 1) * 2));
    }

    private void close() {
        instances.clear();
        if (geometry != null) geometry.close();
        if (poses != null) poses.close();
        if (bytes != null) bytes.destroyBuffers();
        if (vertices != null) vertices.close();
        if (uniform != null) uniform.close();
        model = null; geometry = null; poses = null; bytes = null; vertices = null; uniform = null;
        bones = List.of();
    }
}
