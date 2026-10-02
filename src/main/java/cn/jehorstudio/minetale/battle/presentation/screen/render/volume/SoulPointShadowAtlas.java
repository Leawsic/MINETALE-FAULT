package cn.jehorstudio.minetale.battle.presentation.screen.render.volume;

import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleScene;
import cn.jehorstudio.minetale.battle.presentation.screen.render.Lighting;
import cn.jehorstudio.minetale.battle.presentation.screen.render.PipelineRegister;
import cn.jehorstudio.minetale.battle.presentation.screen.render.RenderTargets;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.DrawUniform;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

// VolumetricShadows 内部的六面点阴影 atlas，持有捕获、CPU 剔除、缓存与分面 Min-Max，
final class SoulPointShadowAtlas implements AutoCloseable {
    static final int FACE_COUNT = 6;
    static final int ALL_FACES = (1 << FACE_COUNT) - 1;
    private static final int MINMAX_BLOCK_SIZE = 4;
    private static final float FRUSTUM_EPSILON = 1.0E-4F;

    private static final Vector3f[] FACE_DIRECTIONS = {
            new Vector3f(1.0F, 0.0F, 0.0F),
            new Vector3f(-1.0F, 0.0F, 0.0F),
            new Vector3f(0.0F, 1.0F, 0.0F),
            new Vector3f(0.0F, -1.0F, 0.0F),
            new Vector3f(0.0F, 0.0F, 1.0F),
            new Vector3f(0.0F, 0.0F, -1.0F)
    };
    private static final Vector3f[] FACE_UP = {
            new Vector3f(0.0F, -1.0F, 0.0F),
            new Vector3f(0.0F, -1.0F, 0.0F),
            new Vector3f(0.0F, 0.0F, 1.0F),
            new Vector3f(0.0F, 0.0F, -1.0F),
            new Vector3f(0.0F, -1.0F, 0.0F),
            new Vector3f(0.0F, -1.0F, 0.0F)
    };

    private final DrawUniform drawUniform;
    private final CaptureUniform captureUniform = new CaptureUniform();
    private final MinMaxUniform minMaxUniform = new MinMaxUniform();
    private CaptureCacheKey captureCacheKey;
    private int generatedFaceMask;
    private long atlasRevision;
    private long hierarchyRevision = -1L;
    private VolumetricShadows.DebugInfo debugInfo = VolumetricShadows.DebugInfo.DISABLED;

    SoulPointShadowAtlas(DrawUniform drawUniform) {
        this.drawUniform = Objects.requireNonNull(drawUniform, "drawUniform");
    }

    // 只从冻结 Frame 计算可见面、光源矩阵、caster mask 与屏幕 ROI。
    VolumetricShadows.PointPlan prepare(BattleScene.Frame frame, Lighting.Settings lighting) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(lighting, "lighting");
        if (!VisualConfig.SOUL_VOLUMETRIC_LIGHT_ENABLED()
                || !lighting.rendered()
                || frame.soulTintLight().isEmpty()
                || frame.volumeRegion().isEmpty()) {
            this.debugInfo = VolumetricShadows.DebugInfo.DISABLED;
            return VolumetricShadows.PointPlan.DISABLED;
        }

        int faceSize = VisualConfig.SOUL_POINT_SHADOW_FACE_SIZE();
        int border = VisualConfig.SOUL_POINT_SHADOW_BORDER();
        if (faceSize <= 0 || border < 1) {
            throw new IllegalStateException("Soul point-shadow face size must be positive and border must be >= 1.");
        }
        VolumetricShadows.PointTargetLayout layout =
                VolumetricShadows.PointTargetLayout.of(faceSize, border);
        BattleScene.SoulTintLight soul = frame.soulTintLight().orElseThrow();
        Vector3f lightPosition = new Vector3f(
                (float) soul.modelBoundsCenter().x(),
                (float) soul.modelBoundsCenter().y(),
                (float) soul.modelBoundsCenter().z()
        );
        float volumetricRadius = Math.max(0.0F, VisualConfig.SOUL_VOLUMETRIC_RADIUS());
        float captureRadius = Math.max(lighting.soulTintLightRadius(), volumetricRadius);
        float nearPlane = Math.min(
                Math.max(1.0E-4F, VisualConfig.SOUL_POINT_SHADOW_NEAR_PLANE()),
                captureRadius * 0.25F
        );
        Matrix4f[] faces = faceMatrices(lightPosition, nearPlane, captureRadius);
        BattleScene.VolumeRegion volume = frame.volumeRegion().orElseThrow();
        BattleScene.WorldBounds volumeBounds = volumeBounds(volume);
        if (!sphereIntersectsVolume(lightPosition, volumetricRadius, volume)
                || !intersectsFrustum(volumeBounds, cameraViewProjection(frame))) {
            this.debugInfo = VolumetricShadows.DebugInfo.DISABLED;
            return VolumetricShadows.PointPlan.DISABLED;
        }

        int requiredFaceMask = requiredFaceMask(
                frame, volumeBounds, lightPosition, captureRadius, nearPlane, faces);
        VolumetricShadows.PointScissor sphereScissor = projectedAabbScissor(
                frame, new BattleScene.WorldBounds(
                        new Vector3f(lightPosition).sub(
                                volumetricRadius, volumetricRadius, volumetricRadius),
                        new Vector3f(lightPosition).add(
                                volumetricRadius, volumetricRadius, volumetricRadius)));
        VolumetricShadows.PointScissor volumeScissor = projectedAabbScissor(frame, volumeBounds);
        VolumetricShadows.PointScissor scissor = sphereScissor.intersect(volumeScissor);
        if (requiredFaceMask == 0 || scissor.empty()) {
            this.debugInfo = VolumetricShadows.DebugInfo.DISABLED;
            return VolumetricShadows.PointPlan.DISABLED;
        }

        List<VolumetricShadows.PointCasterPlan> casters = new ArrayList<>();
        long stateXor = 0x243F6A8885A308D3L;
        long stateSum = 0x13198A2E03707344L;
        for (BattleScene.ShadowCaster caster : frame.shadowCasters()) {
            if (soul.sourceActor().equals(caster.sourceActor())) continue;
            BattleScene.WorldBounds bounds = caster.bounds();
            if (!sphereIntersectsAabb(lightPosition, captureRadius, bounds)) continue;
            int faceMask = bounds.contains(lightPosition)
                    ? ALL_FACES
                    : casterFaceMask(bounds, faces);
            if (faceMask == 0) continue;
            casters.add(new VolumetricShadows.PointCasterPlan(caster.commandIndex(), faceMask));
            long commandHash = hashCommand(
                    0x9E3779B97F4A7C15L,
                    frame.commands().get(caster.commandIndex()));
            stateXor ^= commandHash;
            stateSum += Long.rotateLeft(commandHash, 23);
        }
        long stateHash = mixHash(mixHash(stateXor, stateSum), casters.size());
        return new VolumetricShadows.PointPlan(
                true,
                lightPosition,
                soul.color(),
                captureRadius,
                nearPlane,
                requiredFaceMask,
                faces,
                List.copyOf(casters),
                stateHash,
                layout,
                volume,
                frame.camera(),
                scissor
        );
    }

    // 缓存失效时整体刷新 atlas 并重建 Min-Max；六面共用一个 RenderPass，以 viewport 切换。
    void update(
            VolumetricShadows.PointPlan plan,
            RenderTargets.TargetSet targets,
            List<VolumetricShadows.PreparedPointCaster> preparedCasters
    ) {
        if (!plan.enabled()) {
            this.debugInfo = VolumetricShadows.DebugInfo.DISABLED;
            return;
        }
        TextureTarget atlas = Objects.requireNonNull(targets.soulPointShadowAtlas(), "soulPointShadowAtlas");
        long resourceXor = 0x13198A2E03707344L;
        long resourceSum = 0x243F6A8885A308D3L;
        for (VolumetricShadows.PreparedPointCaster caster : preparedCasters) {
            long casterResource = mixHash(0x9E3779B97F4A7C15L, caster.faceMask());
            casterResource = mixHash(casterResource, System.identityHashCode(caster.vertexBuffer()));
            casterResource = mixHash(casterResource, caster.texture() == null
                    ? 0 : System.identityHashCode(caster.texture().texture()));
            resourceXor ^= casterResource;
            resourceSum += Long.rotateLeft(casterResource, 17);
        }
        long resourceHash = mixHash(mixHash(resourceXor, resourceSum), preparedCasters.size());
        CaptureCacheKey currentKey = new CaptureCacheKey(
                plan.shadowStateHash(),
                Float.floatToIntBits(plan.lightPosition().x()),
                Float.floatToIntBits(plan.lightPosition().y()),
                Float.floatToIntBits(plan.lightPosition().z()),
                Float.floatToIntBits(plan.captureRadius()),
                plan.layout(),
                System.identityHashCode(atlas.getColorTextureView().texture()),
                resourceHash
        );
        boolean requiredFaceAdded = (plan.requiredFaceMask() & ~this.generatedFaceMask) != 0;
        boolean atlasRefreshed = !VisualConfig.VOLUMETRIC_SHADOW_CACHE_ENABLED()
                || !currentKey.equals(this.captureCacheKey)
                || requiredFaceAdded;
        int[] submitted = new int[FACE_COUNT];
        if (atlasRefreshed) {
            captureAtlas(plan, atlas, preparedCasters, submitted);
            this.captureCacheKey = currentKey;
            this.generatedFaceMask = plan.requiredFaceMask();
            this.atlasRevision++;
        }

        boolean hierarchyRebuilt = false;
        if (atlasRefreshed || this.hierarchyRevision != this.atlasRevision) {
            buildHierarchy(plan.layout(), targets);
            this.hierarchyRevision = this.atlasRevision;
            hierarchyRebuilt = true;
        }
        this.debugInfo = new VolumetricShadows.DebugInfo(
                true,
                plan.requiredFaceMask(),
                submitted,
                atlasRefreshed,
                hierarchyRebuilt,
                VisualConfig.SOUL_VOLUMETRIC_DEBUG_MODE()
        );
    }

    VolumetricShadows.DebugInfo debugInfo() {
        return this.debugInfo;
    }

    private void captureAtlas(
            VolumetricShadows.PointPlan plan,
            TextureTarget atlas,
            List<VolumetricShadows.PreparedPointCaster> preparedCasters,
            int[] submitted
    ) {
        List<VolumetricShadows.PreparedPointCaster> sorted = new ArrayList<>(preparedCasters);
        sorted.sort(Comparator
                .comparingInt((VolumetricShadows.PreparedPointCaster caster) ->
                        caster.geometry() == BattleScene.Geometry.BATTLE_FRAME ? 1 : 0)
                .thenComparingInt(caster -> caster.texture() == null
                        ? 0 : System.identityHashCode(caster.texture().texture()))
                .thenComparingInt(caster -> System.identityHashCode(caster.vertexBuffer())));
        this.captureUniform.upload(
                plan.faceViewProjections(),
                plan.lightPosition(),
                plan.captureRadius());
        try {
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Battle Renderer V2 Soul point-shadow atlas",
                    atlas.getColorTextureView(), OptionalInt.of(0xFFFFFFFF),
                    atlas.getDepthTextureView(), OptionalDouble.of(1.0D)
            )) {
                for (int face = 0; face < FACE_COUNT; face++) {
                    if ((plan.requiredFaceMask() & (1 << face)) == 0) continue;
                    pass.setViewport(
                            plan.layout().faceOriginX(face) + plan.layout().border(),
                            plan.layout().faceOriginY(face) + plan.layout().border(),
                            plan.layout().faceSize(),
                            plan.layout().faceSize()
                    );
                    this.captureUniform.bind(pass, face);
                    for (VolumetricShadows.PreparedPointCaster caster : sorted) {
                        if ((caster.faceMask() & (1 << face)) == 0) continue;
                        this.drawUniform.bind(pass, caster.commandIndex());
                        if (caster.geometry() == BattleScene.Geometry.BATTLE_FRAME) {
                            pass.setPipeline(PipelineRegister.BATTLE_VOLUMETRIC_POINT_FRAME_SHADOW);
                        } else {
                            pass.setPipeline(PipelineRegister.BATTLE_VOLUMETRIC_POINT_ENTITY_SHADOW);
                            pass.bindSampler("Sampler0", caster.texture());
                        }
                        pass.setVertexBuffer(0, caster.vertexBuffer());
                        pass.setIndexBuffer(caster.indexBuffer(), caster.indexType());
                        pass.drawIndexed(0, 0, caster.indexCount(), 1);
                        submitted[face]++;
                    }
                }
            }
        } finally {
            this.captureUniform.rotate();
        }
    }

    private void buildHierarchy(
            VolumetricShadows.PointTargetLayout layout,
            RenderTargets.TargetSet targets
    ) {
        List<TextureTarget> levels = targets.soulPointShadowMinMaxLevels();
        TextureTarget atlas = Objects.requireNonNull(targets.soulPointShadowAtlas(), "soulPointShadowAtlas");
        for (int level = 0; level < levels.size(); level++) {
            int currentLevel = level;
            TextureTarget output = levels.get(level);
            if (level == 0) {
                this.minMaxUniform.uploadBase(layout, atlas, output);
            } else {
                this.minMaxUniform.uploadReduce(layout, level, levels.get(level - 1), output);
            }
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Battle Renderer V2 Soul point-shadow Min-Max level " + currentLevel,
                    output.getColorTextureView(), OptionalInt.empty(),
                    null, OptionalDouble.empty()
            )) {
                pass.setViewport(0, 0, output.width, output.height);
                if (level == 0) {
                    pass.setPipeline(PipelineRegister.BATTLE_VOLUMETRIC_POINT_MINMAX_BASE);
                    pass.bindSampler("PointShadowAtlas", atlas.getColorTextureView());
                } else {
                    pass.setPipeline(PipelineRegister.BATTLE_VOLUMETRIC_POINT_MINMAX_REDUCE);
                    pass.bindSampler("PreviousPointMinMax", levels.get(level - 1).getColorTextureView());
                }
                this.minMaxUniform.bind(pass);
                pass.draw(0, 3);
            }
            this.minMaxUniform.rotate();
        }
    }

    private static int requiredFaceMask(
            BattleScene.Frame frame,
            BattleScene.WorldBounds volumeBounds,
            Vector3f lightPosition,
            float radius,
            float nearPlane,
            Matrix4f[] faceMatrices
    ) {
        Matrix4f camera = cameraViewProjection(frame);
        FrustumIntersection cameraFrustum = new FrustumIntersection(camera);
        int result = 0;
        for (int face = 0; face < FACE_COUNT; face++) {
            if (!intersectsFrustum(volumeBounds, faceMatrices[face])) continue;
            BattleScene.WorldBounds faceBounds = faceFrustumBounds(
                    lightPosition, FACE_DIRECTIONS[face], FACE_UP[face], nearPlane, radius);
            if (cameraFrustum.testAab(
                    faceBounds.minimum().x(), faceBounds.minimum().y(), faceBounds.minimum().z(),
                    faceBounds.maximum().x(), faceBounds.maximum().y(), faceBounds.maximum().z())) {
                result |= 1 << face;
            }
        }
        return result;
    }

    static int casterFaceMask(BattleScene.WorldBounds bounds, Matrix4f[] faceMatrices) {
        int result = 0;
        for (int face = 0; face < FACE_COUNT; face++) {
            if (intersectsFrustum(bounds, faceMatrices[face])) result |= 1 << face;
        }
        return result;
    }

    // 无 GPU 依赖的生产剔除入口。
    public static int faceMaskForBounds(
            Vector3f lightPosition,
            float radius,
            float nearPlane,
            BattleScene.WorldBounds bounds
    ) {
        if (!sphereIntersectsAabb(lightPosition, radius, bounds)) return 0;
        if (bounds.contains(lightPosition)) return ALL_FACES;
        return casterFaceMask(bounds, faceMatrices(lightPosition, nearPlane, radius));
    }

    static boolean sphereIntersectsAabb(
            Vector3f center,
            float radius,
            BattleScene.WorldBounds bounds
    ) {
        float x = Math.clamp(center.x(), bounds.minimum().x(), bounds.maximum().x());
        float y = Math.clamp(center.y(), bounds.minimum().y(), bounds.maximum().y());
        float z = Math.clamp(center.z(), bounds.minimum().z(), bounds.maximum().z());
        return center.distanceSquared(x, y, z) <= radius * radius;
    }

    private static boolean sphereIntersectsVolume(
            Vector3f center,
            float radius,
            BattleScene.VolumeRegion volume
    ) {
        Vector3f local = volume.localToWorld().invert(new Matrix4f())
                .transformPosition(new Vector3f(center));
        Vector3f half = volume.halfSize();
        float x = Math.clamp(local.x(), -half.x(), half.x());
        float y = Math.clamp(local.y(), -half.y(), half.y());
        float z = Math.clamp(local.z(), -half.z(), half.z());
        return local.distanceSquared(x, y, z) <= radius * radius;
    }

    private static Matrix4f[] faceMatrices(Vector3f position, float nearPlane, float radius) {
        Matrix4f projection = new Matrix4f().perspective((float) (Math.PI * 0.5D), 1.0F, nearPlane, radius);
        Matrix4f[] result = new Matrix4f[FACE_COUNT];
        for (int face = 0; face < FACE_COUNT; face++) {
            Matrix4f view = new Matrix4f().lookAt(
                    position,
                    new Vector3f(position).add(FACE_DIRECTIONS[face]),
                    FACE_UP[face]
            );
            result[face] = new Matrix4f(projection).mul(view);
        }
        return result;
    }

    private static BattleScene.WorldBounds volumeBounds(BattleScene.VolumeRegion volume) {
        Vector3f half = volume.halfSize();
        return transformedBounds(volume.localToWorld(), half.x(), half.y(), half.z());
    }

    private static BattleScene.WorldBounds transformedBounds(
            Matrix4f transform,
            float halfX,
            float halfY,
            float halfZ
    ) {
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f point = new Vector3f();
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    transform.transformPosition(x * halfX, y * halfY, z * halfZ, point);
                    minimum.min(point);
                    maximum.max(point);
                }
            }
        }
        return new BattleScene.WorldBounds(minimum, maximum);
    }

    private static BattleScene.WorldBounds faceFrustumBounds(
            Vector3f position,
            Vector3f direction,
            Vector3f up,
            float nearPlane,
            float farPlane
    ) {
        Vector3f right = direction.cross(up, new Vector3f()).normalize();
        Vector3f trueUp = right.cross(direction, new Vector3f()).normalize();
        Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f maximum = new Vector3f(Float.NEGATIVE_INFINITY);
        for (float distance : new float[]{nearPlane, farPlane}) {
            for (int x = -1; x <= 1; x += 2) {
                for (int y = -1; y <= 1; y += 2) {
                    Vector3f point = new Vector3f(position)
                            .fma(distance, direction)
                            .fma(x * distance, right)
                            .fma(y * distance, trueUp);
                    minimum.min(point);
                    maximum.max(point);
                }
            }
        }
        minimum.min(position);
        maximum.max(position);
        return new BattleScene.WorldBounds(minimum, maximum);
    }

    private static boolean intersectsFrustum(BattleScene.WorldBounds bounds, Matrix4f viewProjection) {
        return new FrustumIntersection(viewProjection).testAab(
                bounds.minimum().x() - FRUSTUM_EPSILON,
                bounds.minimum().y() - FRUSTUM_EPSILON,
                bounds.minimum().z() - FRUSTUM_EPSILON,
                bounds.maximum().x() + FRUSTUM_EPSILON,
                bounds.maximum().y() + FRUSTUM_EPSILON,
                bounds.maximum().z() + FRUSTUM_EPSILON
        );
    }

    private static Matrix4f cameraViewProjection(BattleScene.Frame frame) {
        return new Matrix4f(frame.camera().projection()).mul(frame.camera().view());
    }

    private static VolumetricShadows.PointScissor projectedAabbScissor(
            BattleScene.Frame frame,
            BattleScene.WorldBounds bounds
    ) {
        if (bounds.contains(frame.camera().position())) {
            return new VolumetricShadows.PointScissor(
                    0, 0, frame.viewport().width(), frame.viewport().height());
        }
        Matrix4f clipFromWorld = cameraViewProjection(frame);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        Vector4f point = new Vector4f();
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    point.set(
                            x == 0 ? bounds.minimum().x() : bounds.maximum().x(),
                            y == 0 ? bounds.minimum().y() : bounds.maximum().y(),
                            z == 0 ? bounds.minimum().z() : bounds.maximum().z(),
                            1.0F
                    );
                    clipFromWorld.transform(point);
                    if (point.w() <= 1.0E-5F) {
                        return new VolumetricShadows.PointScissor(
                                0, 0, frame.viewport().width(), frame.viewport().height());
                    }
                    float inverseW = 1.0F / point.w();
                    minX = Math.min(minX, point.x() * inverseW);
                    minY = Math.min(minY, point.y() * inverseW);
                    maxX = Math.max(maxX, point.x() * inverseW);
                    maxY = Math.max(maxY, point.y() * inverseW);
                }
            }
        }
        int width = frame.viewport().width();
        int height = frame.viewport().height();
        int x0 = Math.clamp((int) Math.floor((minX * 0.5F + 0.5F) * width) - 1, 0, width);
        int y0 = Math.clamp((int) Math.floor((minY * 0.5F + 0.5F) * height) - 1, 0, height);
        int x1 = Math.clamp((int) Math.ceil((maxX * 0.5F + 0.5F) * width) + 1, 0, width);
        int y1 = Math.clamp((int) Math.ceil((maxY * 0.5F + 0.5F) * height) + 1, 0, height);
        return new VolumetricShadows.PointScissor(
                x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    private static long hashCommand(long hash, BattleScene.RenderCommand command) {
        hash = mixHash(hash, command.geometry().ordinal());
        hash = mixHash(hash, command.alphaMode().ordinal());
        hash = mixHash(hash, command.color() >>> 24);
        hash = mixHash(hash, command.texture() == null ? 0 : command.texture().hashCode());
        hash = mixHash(hash, command.objModel() == null ? -1 : command.objModel().ordinal());
        hash = mixHash(hash, command.extrudedImage() == null
                ? 0 : command.extrudedImage().source().hashCode());
        hash = mixHash(hash, command.extrudedImage() == null
                ? 0 : command.extrudedImage().textureSize().hashCode());
        hash = mixHash(hash, command.frameMesh() == null ? 0 : command.frameMesh().hashCode());
        hash = mixHash(hash, command.rendered().castsShadow() ? 1 : 0);
        float[] matrix = new float[16];
        command.model().get(matrix);
        for (float value : matrix) hash = mixHash(hash, Float.floatToIntBits(value));
        return hash;
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

    @Override
    public void close() {
        this.captureUniform.close();
        this.minMaxUniform.close();
    }

    private record CaptureCacheKey(
            long shadowStateHash,
            int lightX,
            int lightY,
            int lightZ,
            int captureRadius,
            VolumetricShadows.PointTargetLayout layout,
            int atlasIdentity,
            long resourceHash
    ) {
    }

    private static final class CaptureUniform implements AutoCloseable {
        private static final int BLOCK_SIZE =
                new Std140SizeCalculator().putMat4f().putVec4().get();
        private final int stride = align(BLOCK_SIZE, RenderSystem.getDevice().getUniformOffsetAlignment());
        private final MappableRingBuffer buffer = new MappableRingBuffer(
                () -> "Battle Soul point-shadow capture uniforms", 130, stride * FACE_COUNT);

        void upload(
                Matrix4f[] matrices,
                Vector3f position,
                float captureRadius
        ) {
            try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.buffer.currentBuffer(), false, true)) {
                ByteBuffer data = mapped.data();
                for (int face = 0; face < FACE_COUNT; face++) {
                    int offset = face * this.stride;
                    matrices[face].get(offset, data);
                    putVec4(data, offset + 64,
                            position.x(), position.y(), position.z(), captureRadius);
                }
            }
        }

        void bind(RenderPass pass, int face) {
            pass.setUniform("SoulPointShadowUniform",
                    this.buffer.currentBuffer().slice(face * this.stride, BLOCK_SIZE));
        }

        void rotate() {
            this.buffer.rotate();
        }

        @Override
        public void close() {
            this.buffer.close();
        }
    }

    private static final class MinMaxUniform implements AutoCloseable {
        private static final int BLOCK_SIZE = new Std140SizeCalculator().putVec4().putVec4().get();
        private final MappableRingBuffer buffer = new MappableRingBuffer(
                () -> "Battle Soul point-shadow Min-Max uniforms", 130, BLOCK_SIZE);

        void uploadBase(
                VolumetricShadows.PointTargetLayout layout,
                TextureTarget source,
                TextureTarget target
        ) {
            upload(source.width, source.height, target.width, target.height,
                    layout.faceSize(), layout.border(), layout.levelFaceSize(0), 0);
        }

        void uploadReduce(
                VolumetricShadows.PointTargetLayout layout,
                int level,
                TextureTarget source,
                TextureTarget target
        ) {
            upload(source.width, source.height, target.width, target.height,
                    layout.levelFaceSize(level - 1), 0, layout.levelFaceSize(level), level);
        }

        private void upload(
                int sourceWidth,
                int sourceHeight,
                int targetWidth,
                int targetHeight,
                int sourceFaceSize,
                int sourceBorder,
                int targetFaceSize,
                int level
        ) {
            try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.buffer.currentBuffer(), false, true)) {
                ByteBuffer data = mapped.data();
                putVec4(data, 0, sourceWidth, sourceHeight, targetWidth, targetHeight);
                putVec4(data, 16, sourceFaceSize, sourceBorder, targetFaceSize, level);
            }
        }

        void bind(RenderPass pass) {
            pass.setUniform("SoulPointMinMaxUniform", this.buffer.currentBuffer());
        }

        void rotate() {
            this.buffer.rotate();
        }

        @Override
        public void close() {
            this.buffer.close();
        }
    }

    private static int align(int value, int alignment) {
        int safeAlignment = Math.max(1, alignment);
        return Math.multiplyExact((value + safeAlignment - 1) / safeAlignment, safeAlignment);
    }

    private static void putVec4(ByteBuffer data, int offset, float x, float y, float z, float w) {
        data.putFloat(offset, x);
        data.putFloat(offset + 4, y);
        data.putFloat(offset + 8, z);
        data.putFloat(offset + 12, w);
    }

    private static void putColor(ByteBuffer data, int offset, int color) {
        putVec4(data, offset,
                ((color >>> 16) & 0xFF) / 255.0F,
                ((color >>> 8) & 0xFF) / 255.0F,
                (color & 0xFF) / 255.0F,
                ((color >>> 24) & 0xFF) / 255.0F);
    }
}
