package cn.jehorstudio.minetale.magic.visual.vfx;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.magic.Magic;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

// Iris 下的光束实体表面。传播图仍由 MagicBeamOcclusion 独占；这里只缓存由它裁出的外表面。
final class MagicBeamSurface implements AutoCloseable {
    private static final int SIDES = 32;
    private static final int CYLINDER_VERTICES = SIDES * 12;
    private static final int ATTRIBUTES = 12;
    private static final float[][] CYLINDER = cylinder();
    private static final float[][] SPHERE = sphere();
    private static final List<String> TERRAIN = List.of("Position", "Color", "UV0", "UV2", "Normal",
            "mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock");
    private final Map<Integer, CachedBeam> beams = new HashMap<>();
    private final IrisBridge iris = IrisBridge.load();
    private RenderPipeline pipeline;
    private final Map<Vec3, Mesh> orientedMeshes = new HashMap<>();
    private Mesher mesher;
    private int materialId;
    private boolean unsupported;

    boolean active() { return iris != null && !unsupported && iris.active(); }

    void retain(Set<Integer> active) {
        if (active.isEmpty()) { close(); return; }
        beams.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
    }

    boolean render(RenderLevelStageEvent.AfterEntities event, List<MagicBeamRenderer.BeamFrame> frames,
                Map<Integer, MagicBeamOcclusion> occlusions) {
        if (frames.isEmpty()) return true;
        if (!ensureResources()) return false;
        var axes = new java.util.HashSet<Vec3>();
        for (var frame : frames) axes.add(frame.axis());
        orientedMeshes.entrySet().removeIf(entry -> {
            if (axes.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
        var draws = new ArrayList<Draw>(frames.size() * 3);
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        for (var frame : frames) {
            var occlusion = occlusions.get(frame.id());
            Vec3 axis = frame.axis();
            Vec3 right = axis.cross(Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
            Vec3 up = axis.cross(right);
            Mesh shared = orientedMeshes.get(axis);
            if (shared == null) {
                shared = new Mesh();
                mesher.begin(null, 0, axis, right, up, 1);
                mesher.shape(CYLINDER, 1, 0, 1, false);
                mesher.shape(SPHERE, 1, 0, 1, false);
                shared.upload(mesher.bytes);
                orientedMeshes.put(axis, shared);
            }
            Vec3 relative = frame.occlusionOrigin().subtract(camera);
            Matrix4f basis = new Matrix4f().setColumn(0, new Vector4f(right.toVector3f(), 0))
                    .setColumn(1, new Vector4f(up.toVector3f(), 0)).setColumn(2, new Vector4f(axis.toVector3f(), 0));
            Matrix4f world = new Matrix4f(event.getModelViewMatrix())
                    .translate((float) relative.x, (float) relative.y, (float) relative.z).mul(basis);
            float start = (float) frame.origin().subtract(frame.occlusionOrigin()).dot(axis);
            float end = start + frame.length();
            float forwardStart = Math.max(0, start);
            // 后坐只延长传播基准后方的束身
            boolean split = start < 0 && end > 0;
            if (start < 0) {
                float rearLength = Math.min(0, end) - start;
                add(draws, shared, 0, split ? SIDES * 8 : CYLINDER_VERTICES,
                        new Matrix4f(world).translate(0, 0, start).scale(frame.radius(), frame.radius(), rearLength));
            }
            CachedBeam cached = beams.computeIfAbsent(frame.id(), id -> new CachedBeam());
            if (end > forwardStart) {
                if (end <= occlusion.minimumStop() * frame.retraction()) {
                    Matrix4f transform = new Matrix4f(world).translate(0, 0, forwardStart)
                            .scale(frame.radius(), frame.radius(), end - forwardStart);
                    add(draws, shared, 0, split ? SIDES * 4 : CYLINDER_VERTICES, transform);
                    if (split) add(draws, shared, SIDES * 8, SIDES * 4, transform);
                } else {
                    var key = new ShapeKey(occlusion.revision(), frame.radius(), forwardStart, end, frame.retraction(), split);
                    if (!key.matches(cached.bodyKey)) {
                        mesher.begin(occlusion, frame.extent(), frame.axis(), right, up, frame.retraction());
                        mesher.shape(CYLINDER, frame.radius(), forwardStart, end - forwardStart, split);
                        cached.body.upload(mesher.bytes);
                        cached.bodyKey = key;
                    }
                    add(draws, cached.body, 0, cached.body.count, world);
                }
            }
            if (frame.muzzleRadius() > 0) {
                float center = start + frame.muzzleOffset();
                if (center + frame.muzzleRadius() <= 0 || frame.muzzleRadius() <= frame.extent()
                        && center + frame.muzzleRadius() <= occlusion.minimumStop() * frame.retraction()) {
                    add(draws, shared, CYLINDER_VERTICES, SPHERE.length * 4,
                            new Matrix4f(world).translate(0, 0, center).scale(frame.muzzleRadius()));
                } else {
                    var key = new ShapeKey(occlusion.revision(), frame.muzzleRadius(), center, frame.muzzleRadius(), frame.retraction(), false);
                    if (!key.matches(cached.muzzleKey)) {
                        mesher.begin(occlusion, frame.extent(), frame.axis(), right, up, frame.retraction());
                        mesher.shape(SPHERE, frame.muzzleRadius(), center, frame.muzzleRadius(), false);
                        cached.muzzle.upload(mesher.bytes);
                        cached.muzzleKey = key;
                    }
                    add(draws, cached.muzzle, 0, cached.muzzle.count, world);
                }
            }
        }
        if (draws.isEmpty()) return true;
        var transforms = new DynamicUniforms.Transform[draws.size()];
        int maxIndices = 0;
        for (int i = 0; i < draws.size(); i++) {
            transforms[i] = new DynamicUniforms.Transform(draws.get(i).transform, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f(), 1);
            maxIndices = Math.max(maxIndices, draws.get(i).count / 4 * 6);
        }
        var slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
        var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        var indexBuffer = indices.getBuffer(maxIndices);
        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        // 首次取得材质会上传基础纹理和 PBR 附图，必须在开启 RenderPass 之前完成。
        var texture = mc.getTextureManager().getTexture(Magic.id("textures/magic/beam_surface.png")).getTextureView();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "MineTale beam terrain surface", target.getColorTextureView(), OptionalInt.empty(),
                target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindSampler("Sampler0", texture);
            pass.bindSampler("Sampler2", mc.gameRenderer.lightTexture().getTextureView());
            pass.setIndexBuffer(indexBuffer, indices.type());
            for (int i = 0; i < draws.size(); i++) {
                Draw draw = draws.get(i);
                pass.setVertexBuffer(0, draw.mesh.buffer);
                pass.setUniform("DynamicTransforms", slices[i]);
                pass.drawIndexed(draw.first, 0, draw.count / 4 * 6, 1);
            }
        }
        return true;
    }

    private static void add(List<Draw> draws, Mesh mesh, int first, int count, Matrix4f transform) {
        if (count > 0) draws.add(new Draw(mesh, first, count, transform));
    }

    private boolean ensureResources() {
        if (pipeline == null) {
            VertexFormat terrain = RenderPipelines.SOLID.getVertexFormat();
            if (terrain.getVertexSize() != 52 || !terrain.getElementAttributeNames().equals(TERRAIN))
                return unsupported("Unsupported Iris terrain layout: " + terrain, null);
            // GENERIC 整数属性会被 Blaze3D 绑定成整数输入，光影的 mc_Entity / at_midBlock 却接收浮点。
            // 只为本管线扩展到 64 字节
            var kind = VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
                    VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 2);
            var midpoint = VertexFormatElement.register(VertexFormatElement.findNextId(), 0,
                    VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.GENERIC, 3);
            VertexFormat format = VertexFormat.builder().add("Position", VertexFormatElement.POSITION)
                    .add("Color", VertexFormatElement.COLOR).add("UV0", VertexFormatElement.UV0)
                    .add("UV2", VertexFormatElement.UV2).add("Normal", VertexFormatElement.NORMAL).padding(1)
                    .add("mc_Entity", kind).add("mc_midTexCoord", terrain.getElements().get(6))
                    .add("at_tangent", terrain.getElements().get(7)).add("at_midBlock", midpoint).build();
            pipeline = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                    .withLocation(Magic.id("pipeline/magic/beam_surface"))
                    .withCull(false).withVertexFormat(format, VertexFormat.Mode.QUADS).build();
            try { iris.assign(pipeline); }
            catch (ReflectiveOperationException failure) { return unsupported("Iris terrain pipeline assignment failed", failure); }
        }
        int currentId;
        try { currentId = iris.materialId(); }
        catch (ReflectiveOperationException failure) { return unsupported("Iris block material mapping unavailable", failure); }
        if (mesher != null && currentId == materialId) return true;
        close();
        materialId = currentId;
        mesher = new Mesher(materialId);
        return true;
    }

    private boolean unsupported(String reason, Throwable failure) {
        unsupported = true;
        close();
        MineTale.LOGGER.warn("{}; using the standalone beam renderer, whose custom shader depth layouts are unsupported", reason, failure);
        return false;
    }

    @Override public void close() {
        beams.values().forEach(CachedBeam::close);
        beams.clear();
        orientedMeshes.values().forEach(Mesh::close);
        orientedMeshes.clear();
        if (mesher != null) { mesher.bytes.close(); mesher = null; }
        // 顶点元素是全局注册项，重载只释放网格
    }

    private record Draw(Mesh mesh, int first, int count, Matrix4f transform) {}
    private record ShapeKey(long revision, float radius, float start, float length, float retraction, boolean openStart) {
        boolean matches(ShapeKey previous) {
            // length 含后坐补偿，世界坐标相减再抵消会产生舍入差；小于距离图精度的差值不重建网格。
            return previous != null && revision == previous.revision && radius == previous.radius
                    && Math.abs(start - previous.start) <= 1.0F / 512 && Math.abs(length - previous.length) <= 1.0F / 512
                    && retraction == previous.retraction && openStart == previous.openStart;
        }
    }

    private static final class CachedBeam implements AutoCloseable {
        final Mesh body = new Mesh(), muzzle = new Mesh();
        ShapeKey bodyKey, muzzleKey;
        @Override public void close() { body.close(); muzzle.close(); }
    }

    private static final class Mesh implements AutoCloseable {
        GpuBuffer buffer;
        int count;
        void upload(ByteBufferBuilder builder) {
            try (var result = builder.build()) {
                if (result == null) { count = 0; return; }
                var data = result.byteBuffer();
                count = data.remaining() / 64;
                if (buffer == null || buffer.size() < data.remaining()) {
                    close();
                    buffer = RenderSystem.getDevice().createBuffer(() -> "Magic beam surface mesh",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data);
                } else RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, data.remaining()), data);
            }
        }
        @Override public void close() { if (buffer != null) { buffer.close(); buffer = null; } }
    }

    // Sutherland–Hodgman 只裁圆柱侧面、原始端盖和炮口球面
    private static final class Mesher {
        final ByteBufferBuilder bytes = new ByteBufferBuilder(128 * 1024);
        final int materialId;
        final float[] source = new float[4 * ATTRIBUTES];
        final float[] planeA = new float[3], planeB = new float[3];
        float[] polygon = new float[16 * ATTRIBUTES], scratch = new float[16 * ATTRIBUTES];
        int count;
        MagicBeamOcclusion occlusion;
        float extent, retraction;
        Vec3 axis, right, up;
        Mesher(int materialId) { this.materialId = materialId; }
        void begin(MagicBeamOcclusion occlusion, float extent, Vec3 axis, Vec3 right, Vec3 up, float retraction) {
            this.occlusion = occlusion;
            this.extent = extent;
            this.axis = axis;
            this.right = right;
            this.up = up;
            this.retraction = retraction;
            if (occlusion != null) for (Direction.Axis coordinate : Direction.Axis.values()) {
                double axial = axis.get(coordinate);
                planeA[coordinate.ordinal()] = Math.abs(axial) > 0.00001 ? (float) (right.get(coordinate) / axial) : 0;
                planeB[coordinate.ordinal()] = Math.abs(axial) > 0.00001 ? (float) (up.get(coordinate) / axial) : 0;
            }
        }
        void shape(float[][] quads, float radius, float start, float length, boolean openStart) {
            for (int q = 0; q < quads.length; q++) {
                if (openStart && q >= SIDES && q < SIDES * 2) continue;
                float[] quad = quads[q];
                for (int v = 0; v < 4; v++) {
                    int i = v * ATTRIBUTES;
                    System.arraycopy(quad, i, source, i, ATTRIBUTES);
                    source[i] *= radius; source[i + 1] *= radius; source[i + 2] = start + source[i + 2] * length;
                }
                if (occlusion == null) { System.arraycopy(source, 0, polygon, 0, source.length); count = 4; emit(); }
                else clippedQuad();
            }
        }
        void clippedQuad() {
            float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX, maxX = -minX, maxY = -minX, maxZ = -minX;
            for (int i = 0; i < source.length; i += ATTRIBUTES) {
                minX = Math.min(minX, source[i]); maxX = Math.max(maxX, source[i]);
                minY = Math.min(minY, source[i + 1]); maxY = Math.max(maxY, source[i + 1]);
                minZ = Math.min(minZ, source[i + 2]); maxZ = Math.max(maxZ, source[i + 2]);
            }
            if (maxZ <= 0) { System.arraycopy(source, 0, polygon, 0, source.length); count = 4; emit(); return; }
            int size = occlusion.size();
            float step = extent * 2 / size;
            int x0 = Math.clamp((int) Math.floor((minX + extent) / step), 0, size - 1);
            int x1 = Math.clamp((int) Math.floor((maxX + extent) / step), 0, size - 1);
            int y0 = Math.clamp((int) Math.floor((minY + extent) / step), 0, size - 1);
            int y1 = Math.clamp((int) Math.floor((maxY + extent) / step), 0, size - 1);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                int encoded = occlusion.cell(x, y);
                float distance = (encoded & 0xFFFFFF) / 512.0F;
                int normal = (encoded >>> 24) - 1;
                float a = normal >= 0 ? planeA[normal] : 0, b = normal >= 0 ? planeB[normal] : 0;
                float stop = distance + a * (-extent + (x + 0.5F) * step) + b * (-extent + (y + 0.5F) * step);
                // 负轴段在传播基准后方始终可见；max(0,stop) 的折点也必须保留。
                System.arraycopy(source, 0, polygon, 0, source.length); count = 4;
                if (minZ < 0) {
                    clip(0, 0, 1, 0); clipCell(x, y, size, step); emit();
                    System.arraycopy(source, 0, polygon, 0, source.length); count = 4;
                    clip(0, 0, -1, 0);
                }
                // 先裁传播平面，完整落在墙后的端盖不再切成大量随后丢弃的小片。
                clip(a * retraction, b * retraction, 1, stop * retraction);
                clipCell(x, y, size, step);
                emit();
            }
        }
        void clipCell(int x, int y, int size, float step) {
            if (x > 0) clip(-1, 0, 0, extent - x * step);
            if (x < size - 1) clip(1, 0, 0, -extent + (x + 1) * step);
            if (y > 0) clip(0, -1, 0, extent - y * step);
            if (y < size - 1) clip(0, 1, 0, -extent + (y + 1) * step);
        }
        void clip(float a, float b, float c, float d) {
            if (count == 0) return;
            int output = 0, last = (count - 1) * ATTRIBUTES;
            float before = a * polygon[last] + b * polygon[last + 1] + c * polygon[last + 2] - d;
            for (int v = 0; v < count; v++) {
                int current = v * ATTRIBUTES;
                float after = a * polygon[current] + b * polygon[current + 1] + c * polygon[current + 2] - d;
                if ((before <= 0) != (after <= 0)) {
                    float t = before / (before - after);
                    for (int k = 0; k < ATTRIBUTES; k++) scratch[output * ATTRIBUTES + k] = polygon[last + k] + t * (polygon[current + k] - polygon[last + k]);
                    output++;
                }
                if (after <= 0) { System.arraycopy(polygon, current, scratch, output * ATTRIBUTES, ATTRIBUTES); output++; }
                last = current; before = after;
            }
            float[] swap = polygon; polygon = scratch; scratch = swap; count = output;
        }
        void emit() {
            if (count == 4) { for (int i = 0; i < 4; i++) vertex(i); }
            else for (int i = 1; i + 1 < count; i++) { vertex(0); vertex(i); vertex(i + 1); vertex(i + 1); }
        }
        void vertex(int vertex) {
            int i = vertex * ATTRIBUTES;
            long p = bytes.reserve(64);
            MemoryUtil.memSet(p, 0, 64);
            for (int k = 0; k < 3; k++) MemoryUtil.memPutFloat(p + k * 4, polygon[i + k]);
            MemoryUtil.memPutInt(p + 12, -1);
            MemoryUtil.memPutFloat(p + 16, polygon[i + 6]); MemoryUtil.memPutFloat(p + 20, polygon[i + 7]);
            MemoryUtil.memPutShort(p + 24, (short) 240); MemoryUtil.memPutShort(p + 26, (short) 240);
            float nx = polygon[i + 3], ny = polygon[i + 4], nz = polygon[i + 5];
            float norm = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            // Iris 的 terrain normal matrix 只含全局视图
            // 因此 Normal 与 tangent 必须先写为世界方向
            MemoryUtil.memPutByte(p + 28, (byte) Math.round((right.x * nx + up.x * ny + axis.x * nz) / norm * 127));
            MemoryUtil.memPutByte(p + 29, (byte) Math.round((right.y * nx + up.y * ny + axis.y * nz) / norm * 127));
            MemoryUtil.memPutByte(p + 30, (byte) Math.round((right.z * nx + up.z * ny + axis.z * nz) / norm * 127));
            MemoryUtil.memPutFloat(p + 32, materialId); MemoryUtil.memPutFloat(p + 36, -1);
            MemoryUtil.memPutFloat(p + 40, 0.5F); MemoryUtil.memPutFloat(p + 44, 0.5F);
            float tx = polygon[i + 8], ty = polygon[i + 9], tz = polygon[i + 10];
            float alongNormal = (tx * nx + ty * ny + tz * nz) / (norm * norm);
            tx -= alongNormal * nx; ty -= alongNormal * ny; tz -= alongNormal * nz;
            float tangentLength = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            MemoryUtil.memPutByte(p + 48, (byte) Math.round((right.x * tx + up.x * ty + axis.x * tz) / tangentLength * 127));
            MemoryUtil.memPutByte(p + 49, (byte) Math.round((right.y * tx + up.y * ty + axis.y * tz) / tangentLength * 127));
            MemoryUtil.memPutByte(p + 50, (byte) Math.round((right.z * tx + up.z * ty + axis.z * tz) / tangentLength * 127));
            MemoryUtil.memPutByte(p + 51, (byte) Math.round(polygon[i + 11] * 127));
        }
    }

    private static float[][] cylinder() {
        float[][] quads = new float[SIDES * 3][4 * ATTRIBUTES];
        for (int side = 0; side < SIDES; side++) {
            float x0 = (float) Math.cos(side * Math.PI * 2 / SIDES), y0 = (float) Math.sin(side * Math.PI * 2 / SIDES);
            float x1 = (float) Math.cos((side + 1) * Math.PI * 2 / SIDES), y1 = (float) Math.sin((side + 1) * Math.PI * 2 / SIDES);
            point(quads[side], 0, x0, y0, 0, x0, y0, 0); point(quads[side], 1, x1, y1, 0, x1, y1, 0);
            point(quads[side], 2, x1, y1, 1, x1, y1, 0); point(quads[side], 3, x0, y0, 1, x0, y0, 0);
            point(quads[SIDES + side], 0, 0, 0, 0, 0, 0, -1); point(quads[SIDES + side], 1, 0, 0, 0, 0, 0, -1);
            point(quads[SIDES + side], 2, x1, y1, 0, 0, 0, -1); point(quads[SIDES + side], 3, x0, y0, 0, 0, 0, -1);
            point(quads[SIDES * 2 + side], 0, 0, 0, 1, 0, 0, 1); point(quads[SIDES * 2 + side], 1, x0, y0, 1, 0, 0, 1);
            point(quads[SIDES * 2 + side], 2, x1, y1, 1, 0, 0, 1); point(quads[SIDES * 2 + side], 3, 0, 0, 1, 0, 0, 1);
            for (int cap = 1; cap <= 2; cap++) for (int v = 0; v < 4; v++) {
                float[] quad = quads[SIDES * cap + side];
                int i = v * ATTRIBUTES;
                quad[i + 6] = 0.5F + quad[i] * 0.45F;
                quad[i + 7] = 0.5F + quad[i + 1] * quad[i + 5] * 0.45F;
            }
        }
        return quads;
    }

    private static float[][] sphere() {
        float[][] quads = new float[16 * 8][4 * ATTRIBUTES];
        for (int latitude = 0; latitude < 8; latitude++) for (int longitude = 0; longitude < 16; longitude++)
            for (int corner = 0; corner < 4; corner++) {
                double a = (latitude + (corner == 1 || corner == 2 ? 1 : 0)) * Math.PI / 8;
                double b = (longitude + (corner >= 2 ? 1 : 0)) * Math.PI * 2 / 16;
                float x = (float) (Math.sin(a) * Math.cos(b)), y = (float) (Math.sin(a) * Math.sin(b)), z = (float) Math.cos(a);
                point(quads[latitude * 16 + longitude], corner, x, y, z, x, y, z);
                float[] quad = quads[latitude * 16 + longitude];
                int i = corner * ATTRIBUTES;
                quad[i + 6] = corner >= 2 ? 0.95F : 0.05F;
                quad[i + 7] = corner == 1 || corner == 2 ? 0.95F : 0.05F;
                quad[i + 8] = (float) -Math.sin(b); quad[i + 9] = (float) Math.cos(b);
                // v 随纬度增加，与 cross(normal,tangent) 反向。
                quad[i + 11] = -1;
            }
        return quads;
    }

    private static void point(float[] quad, int corner, float x, float y, float z, float nx, float ny, float nz) {
        int i = corner * ATTRIBUTES;
        quad[i] = x; quad[i + 1] = y; quad[i + 2] = z;
        quad[i + 3] = nx; quad[i + 4] = ny; quad[i + 5] = nz;
        quad[i + 6] = corner == 1 || corner == 2 ? 0.95F : 0.05F;
        quad[i + 7] = corner >= 2 ? 0.95F : 0.05F;
        float radial = (float) Math.sqrt(nx * nx + ny * ny);
        quad[i + 8] = radial > 0.00001F ? -ny / radial : 1;
        quad[i + 9] = radial > 0.00001F ? nx / radial : 0;
        quad[i + 11] = 1;
    }

    private static final class IrisBridge {
        final Object api, settings, terrain;
        final Method active, assign, blockIds;
        static IrisBridge load() {
            Class<?> type;
            try {
                type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            } catch (ClassNotFoundException absent) { return null; }
            try { return new IrisBridge(type); }
            catch (ReflectiveOperationException failure) {
                MineTale.LOGGER.warn("Iris beam integration unavailable; using standalone beam rendering", failure);
                return null;
            }
        }
        IrisBridge(Class<?> type) throws ReflectiveOperationException {
            var program = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
            var rendering = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
            api = type.getMethod("getInstance").invoke(null);
            active = type.getMethod("isShaderPackInUse");
            assign = type.getMethod("assignPipeline", RenderPipeline.class, program);
            terrain = program.getField("TERRAIN_SOLID").get(null);
            settings = rendering.getField("INSTANCE").get(null);
            blockIds = rendering.getMethod("getBlockStateIds");
        }
        boolean active() {
            try { return (boolean) active.invoke(api); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        void assign(RenderPipeline pipeline) throws ReflectiveOperationException {
            assign.invoke(api, pipeline, terrain);
        }
        int materialId() throws ReflectiveOperationException {
            var ids = (Object2IntMap<?>) blockIds.invoke(settings);
            return ids == null ? -1 : ids.getOrDefault(Blocks.SEA_LANTERN.defaultBlockState(), -1);
        }
    }

}
