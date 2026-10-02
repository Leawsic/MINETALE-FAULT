package cn.jehorstudio.minetale.battle.presentation.screen.render.volume;

import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleScene;
import cn.jehorstudio.minetale.battle.presentation.screen.render.Lighting;
import cn.jehorstudio.minetale.battle.presentation.screen.render.PipelineRegister;
import cn.jehorstudio.minetale.battle.presentation.screen.render.RenderTargets;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.DrawUniform;
import cn.jehorstudio.minetale.battle.presentation.screen.render.uniforms.LightingUniform;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

// 方向光与 Soul 点光共享 prepare → refreshHierarchy → render 生命周期及唯一屏幕空间积分 Pass。
// ROI、atlas、Min-Max 缓存、UBO 与区间追踪均由该模块持有。
public final class VolumetricShadows implements AutoCloseable {
    public static final int POINT_FACE_COUNT = SoulPointShadowAtlas.FACE_COUNT;
    public static final int ALL_POINT_FACES = SoulPointShadowAtlas.ALL_FACES;
    private static final int POINT_MINMAX_BLOCK_SIZE = 4;

    private final LightingUniform lightingUniform;
    private final IntervalUniform intervalUniform = new IntervalUniform();
    private final MinMaxUniform minMaxUniform = new MinMaxUniform();
    private final SoulPointShadowAtlas pointShadowAtlas;
    private HierarchyCacheKey hierarchyCacheKey;

    public VolumetricShadows(LightingUniform lightingUniform, DrawUniform drawUniform) {
        this.lightingUniform = Objects.requireNonNull(lightingUniform, "lightingUniform");
        this.pointShadowAtlas = new SoulPointShadowAtlas(
                Objects.requireNonNull(drawUniform, "drawUniform"));
    }

    // 计算本帧唯一计划，并提前上传方向光与 Soul 点光共用的区间追踪 UBO。
    public Plan prepare(BattleScene.Frame frame, Lighting.LightSpace lightSpace, Lighting.Settings lighting) {
        boolean globalEnabled = VisualConfig.VOLUMETRIC_SHADOWS_ENABLED()
                && lighting.rendered()
                && frame.volumeRegion().isPresent();
        PointPlan point = this.pointShadowAtlas.prepare(frame, lighting);
        if (!globalEnabled && !point.enabled()) return Plan.DISABLED;

        BattleScene.VolumeRegion volume = frame.volumeRegion().orElseThrow();
        HierarchyLayout hierarchy = globalEnabled && lighting.shadowsEnabled()
                ? HierarchyLayout.project(volume, lightSpace.viewProjection(), lighting.shadowMapSize())
                : null;
        if (hierarchy != null && hierarchy.levelCount() == 0) hierarchy = null;
        if (globalEnabled && lighting.shadowsEnabled() && hierarchy == null) {
            globalEnabled = false;
        }
        if (!globalEnabled && !point.enabled()) return Plan.DISABLED;

        this.intervalUniform.upload(
                frame, volume, lightSpace, lighting, hierarchy, globalEnabled, point);
        return new Plan(true, globalEnabled, hierarchy, point);
    }

    // 深度图就绪后统一刷新两类层次；该阶段不启动屏幕空间积分 Pass。
    public void refreshHierarchy(
            RenderTargets.TargetSet targets,
            long shadowMapRevision,
            Plan plan,
            List<PreparedPointCaster> preparedPointCasters
    ) {
        refreshGlobalHierarchy(targets, shadowMapRevision, plan.globalEnabled());
        this.pointShadowAtlas.update(plan.point(), targets, preparedPointCasters);
    }

    private void refreshGlobalHierarchy(
            RenderTargets.TargetSet targets,
            long shadowMapRevision,
            boolean globalEnabled
    ) {
        HierarchyLayout hierarchy = targets.shadowHierarchyLayout();
        if (!globalEnabled || hierarchy == null || targets.shadowMinMaxLevels().isEmpty()) {
            this.hierarchyCacheKey = null;
            return;
        }
        HierarchyCacheKey currentKey = hierarchyCacheKey(
                shadowMapRevision, hierarchy, targets.shadowMinMaxLevels());
        if (VisualConfig.VOLUMETRIC_SHADOW_CACHE_ENABLED()
                && currentKey.equals(this.hierarchyCacheKey)) return;

        buildHierarchy(targets, hierarchy);
        this.hierarchyCacheKey = currentKey;
    }

    // 在不透明场景之后以单个 Pass 合成两类体积散射。
    public void render(
            RenderTargets.TargetSet targets,
            TextureTarget scene,
            BattleScene.Frame frame,
            Plan plan
    ) {
        Scissor scissor = plan.globalEnabled()
                ? projectedScissor(frame, scene.width, scene.height)
                : Scissor.EMPTY;
        if (plan.point().enabled()) {
            PointScissor point = plan.point().scissor();
            scissor = scissor.union(new Scissor(point.x(), point.y(), point.width(), point.height()));
        }
        try {
            if (scissor.empty()) return;
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Battle Renderer V2 volumetric interval trace",
                    scene.getColorTextureView(), OptionalInt.empty(),
                    null, OptionalDouble.empty()
            )) {
                pass.setViewport(0, 0, scene.width, scene.height);
                pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                pass.setPipeline(PipelineRegister.BATTLE_VOLUMETRIC_INTERVAL_TRACE);
                pass.bindSampler("SceneDepthSampler", scene.getDepthTextureView());
                pass.bindSampler("ShadowSampler", targets.shadow().getDepthTextureView());
                List<TextureTarget> levels = targets.shadowMinMaxLevels();
                GpuTextureView fallback = levels.isEmpty()
                        ? targets.shadow().getDepthTextureView()
                        : levels.getFirst().getColorTextureView();
                for (int level = 0; level < HierarchyLayout.MAX_LEVELS; level++) {
                    GpuTextureView view = level < levels.size()
                            ? levels.get(level).getColorTextureView()
                            : fallback;
                    pass.bindSampler("MinMaxLevel" + level, view);
                }
                TextureTarget pointAtlas = targets.soulPointShadowAtlas();
                GpuTextureView pointFallback = targets.shadow().getColorTextureView();
                pass.bindSampler("PointShadowAtlas", pointAtlas == null
                        ? pointFallback : pointAtlas.getColorTextureView());
                List<TextureTarget> pointLevels = targets.soulPointShadowMinMaxLevels();
                GpuTextureView pointLevelFallback = pointLevels.isEmpty()
                        ? pointFallback
                        : pointLevels.getFirst().getColorTextureView();
                for (int level = 0; level < PointTargetLayout.MAX_LEVELS; level++) {
                    pass.bindSampler("PointMinMaxLevel" + level,
                            level < pointLevels.size()
                                    ? pointLevels.get(level).getColorTextureView()
                                    : pointLevelFallback);
                }
                this.lightingUniform.bind(pass);
                this.intervalUniform.bind(pass);
                pass.draw(0, 3);
            }
        } finally {
            this.intervalUniform.rotate();
        }
    }

    @Override
    public void close() {
        this.intervalUniform.close();
        this.minMaxUniform.close();
        this.pointShadowAtlas.close();
    }

    private void buildHierarchy(RenderTargets.TargetSet targets, HierarchyLayout hierarchy) {
        List<TextureTarget> levels = targets.shadowMinMaxLevels();
        for (int level = 0; level < levels.size(); level++) {
            TextureTarget output = levels.get(level);
            if (level == 0) {
                this.minMaxUniform.uploadBase(targets.shadow().width, output, hierarchy);
            } else {
                this.minMaxUniform.uploadReduce(levels.get(level - 1), output);
            }
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Battle Renderer V2 shadow Min-Max level",
                    output.getColorTextureView(), OptionalInt.empty(),
                    null, OptionalDouble.empty()
            )) {
                pass.setViewport(0, 0, output.width, output.height);
                if (level == 0) {
                    pass.setPipeline(PipelineRegister.BATTLE_SHADOW_MINMAX_BASE);
                    pass.bindSampler("ShadowSampler", targets.shadow().getDepthTextureView());
                } else {
                    pass.setPipeline(PipelineRegister.BATTLE_SHADOW_MINMAX_REDUCE);
                    pass.bindSampler("PreviousMinMaxSampler", levels.get(level - 1).getColorTextureView());
                }
                this.minMaxUniform.bind(pass);
                pass.draw(0, 3);
            }
            this.minMaxUniform.rotate();
        }
    }

    private static HierarchyCacheKey hierarchyCacheKey(
            long shadowMapRevision,
            HierarchyLayout hierarchy,
            List<TextureTarget> levels
    ) {
        long targetHash = 0x13198A2E03707344L;
        for (TextureTarget level : levels) {
            targetHash = mixHash(targetHash,
                    System.identityHashCode(level.getColorTextureView().texture()));
        }
        return new HierarchyCacheKey(shadowMapRevision, hierarchy, targetHash);
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

    private static int ceilDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static Scissor projectedScissor(BattleScene.Frame frame, int width, int height) {
        BattleScene.VolumeRegion region = frame.volumeRegion().orElseThrow();
        Matrix4f worldToLocal = region.localToWorld().invert(new Matrix4f());
        Vector3f cameraLocal = worldToLocal.transformPosition(new Vector3f(frame.camera().position()));
        Vector3f half = region.halfSize();
        if (Math.abs(cameraLocal.x()) <= half.x() && Math.abs(cameraLocal.y()) <= half.y()
                && Math.abs(cameraLocal.z()) <= half.z()) {
            return new Scissor(0, 0, width, height);
        }

        Matrix4f clipFromLocal = new Matrix4f(frame.camera().projection())
                .mul(frame.camera().view())
                .mul(region.localToWorld());
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        Vector4f point = new Vector4f();
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    point.set(x * half.x(), y * half.y(), z * half.z(), 1.0F);
                    clipFromLocal.transform(point);
                    if (point.w() <= 0.0001F) return new Scissor(0, 0, width, height);
                    float inverseW = 1.0F / point.w();
                    minX = Math.min(minX, point.x() * inverseW);
                    minY = Math.min(minY, point.y() * inverseW);
                    maxX = Math.max(maxX, point.x() * inverseW);
                    maxY = Math.max(maxY, point.y() * inverseW);
                }
            }
        }
        int x0 = Math.clamp((int) Math.floor((minX * 0.5F + 0.5F) * width) - 1, 0, width);
        int y0 = Math.clamp((int) Math.floor((minY * 0.5F + 0.5F) * height) - 1, 0, height);
        int x1 = Math.clamp((int) Math.ceil((maxX * 0.5F + 0.5F) * width) + 1, 0, width);
        int y1 = Math.clamp((int) Math.ceil((maxY * 0.5F + 0.5F) * height) + 1, 0, height);
        return new Scissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    public record Plan(
            boolean enabled,
            boolean globalEnabled,
            HierarchyLayout hierarchyLayout,
            PointPlan point
    ) {
        private static final Plan DISABLED =
                new Plan(false, false, null, PointPlan.DISABLED);

        public Plan {
            Objects.requireNonNull(point, "point");
        }
    }

    public record PreparedPointCaster(
            int commandIndex,
            int faceMask,
            BattleScene.Geometry geometry,
            GpuTextureView texture,
            GpuBuffer vertexBuffer,
            GpuBuffer indexBuffer,
            VertexFormat.IndexType indexType,
            int indexCount
    ) {
        public PreparedPointCaster {
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(vertexBuffer, "vertexBuffer");
            Objects.requireNonNull(indexBuffer, "indexBuffer");
            Objects.requireNonNull(indexType, "indexType");
            if (commandIndex < 0 || faceMask == 0 || indexCount <= 0) {
                throw new IllegalArgumentException("Prepared Soul shadow caster must reference a draw.");
            }
        }
    }

    public record PointCasterPlan(int commandIndex, int faceMask) {
    }

    public record PointPlan(
            boolean enabled,
            Vector3f lightPosition,
            int lightColor,
            float captureRadius,
            float nearPlane,
            int requiredFaceMask,
            Matrix4f[] faceViewProjections,
            List<PointCasterPlan> casters,
            long shadowStateHash,
            PointTargetLayout layout,
            BattleScene.VolumeRegion volume,
            BattleScene.BattleCamera camera,
            PointScissor scissor
    ) {
        static final PointPlan DISABLED = new PointPlan(
                false, new Vector3f(), 0, 0.0F, 0.0F, 0,
                new Matrix4f[0], List.of(), 0L, null, null, null, PointScissor.EMPTY);

        public PointPlan {
            lightPosition = new Vector3f(lightPosition);
            Matrix4f[] copied = new Matrix4f[faceViewProjections.length];
            for (int index = 0; index < copied.length; index++) {
                copied[index] = new Matrix4f(faceViewProjections[index]);
            }
            faceViewProjections = copied;
            casters = List.copyOf(casters);
        }
    }

    // 持久资源布局：3×2 点阴影 atlas，每级 Min-Max 保持相同分面排列。
    public record PointTargetLayout(int faceSize, int border, int levelCount) {
        public static final int MAX_LEVELS = 4;

        public static PointTargetLayout of(int faceSize, int border) {
            int levelCount = 1;
            int size = ceilDivide(faceSize, POINT_MINMAX_BLOCK_SIZE);
            while (size > 1 && levelCount < MAX_LEVELS) {
                size = ceilDivide(size, POINT_MINMAX_BLOCK_SIZE);
                levelCount++;
            }
            return new PointTargetLayout(faceSize, border, levelCount);
        }

        public int tileSize() {
            return this.faceSize + this.border * 2;
        }

        public int atlasWidth() {
            return tileSize() * 3;
        }

        public int atlasHeight() {
            return tileSize() * 2;
        }

        public int faceOriginX(int face) {
            return face % 3 * tileSize();
        }

        public int faceOriginY(int face) {
            return face / 3 * tileSize();
        }

        public int levelFaceSize(int level) {
            int result = this.faceSize;
            for (int index = 0; index <= level; index++) {
                result = ceilDivide(result, POINT_MINMAX_BLOCK_SIZE);
            }
            return result;
        }

        public int levelWidth(int level) {
            return levelFaceSize(level) * 3;
        }

        public int levelHeight(int level) {
            return levelFaceSize(level) * 2;
        }
    }

    public record DebugInfo(
            boolean enabled,
            int requiredFaceMask,
            int[] submittedCastersPerFace,
            boolean atlasRefreshed,
            boolean hierarchyRebuilt,
            VisualConfig.SoulVolumetricDebugMode shaderDebugMode
    ) {
        static final DebugInfo DISABLED = new DebugInfo(
                false, 0, new int[POINT_FACE_COUNT], false, false,
                VisualConfig.SoulVolumetricDebugMode.NORMAL);

        public DebugInfo {
            submittedCastersPerFace = submittedCastersPerFace.clone();
        }

        @Override
        public int[] submittedCastersPerFace() {
            return this.submittedCastersPerFace.clone();
        }
    }

    public record PointScissor(int x, int y, int width, int height) {
        private static final PointScissor EMPTY = new PointScissor(0, 0, 0, 0);

        boolean empty() {
            return width <= 0 || height <= 0;
        }

        PointScissor intersect(PointScissor other) {
            int x0 = Math.max(this.x, other.x);
            int y0 = Math.max(this.y, other.y);
            int x1 = Math.min(this.x + this.width, other.x + other.width);
            int y1 = Math.min(this.y + this.height, other.y + other.height);
            return new PointScissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        }
    }

    // 布局只由 ROI 尺寸与层级数决定
    public record HierarchyLayout(int x, int y, int width, int height, int levelCount) {
        public static final int MAX_LEVELS = 5;
        private static final int BASE_BLOCK_SIZE = VisualConfig.VOLUMETRIC_MINMAX_BASE_BLOCK_SIZE;

        public HierarchyLayout {
            if (x < 0 || y < 0 || width < 0 || height < 0
                    || levelCount < 0 || levelCount > MAX_LEVELS) {
                throw new IllegalArgumentException("Invalid volumetric shadow hierarchy layout.");
            }
        }

        public int levelWidth(int level) {
            return ceilDivide(width, coverage(level));
        }

        public int levelHeight(int level) {
            return ceilDivide(height, coverage(level));
        }

        public boolean isCompatibleWith(HierarchyLayout other) {
            if (other == null || levelCount != other.levelCount) return false;
            for (int level = 0; level < levelCount; level++) {
                if (levelWidth(level) != other.levelWidth(level)
                        || levelHeight(level) != other.levelHeight(level)) return false;
            }
            return true;
        }

        public static HierarchyLayout project(
                BattleScene.VolumeRegion volume,
                Matrix4f lightViewProjection,
                int shadowMapSize
        ) {
            if (shadowMapSize <= 0) {
                throw new IllegalArgumentException("shadowMapSize must be positive.");
            }
            if (BASE_BLOCK_SIZE != 4) {
                throw new IllegalStateException("The interval-trace shader requires a 4x4 Min-Max base block.");
            }
            Matrix4f clipFromLocal = new Matrix4f(lightViewProjection).mul(volume.localToWorld());
            Vector3f half = volume.halfSize();
            float minU = Float.POSITIVE_INFINITY;
            float minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY;
            float maxV = Float.NEGATIVE_INFINITY;
            Vector4f point = new Vector4f();
            boolean invalid = false;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        point.set(sx * half.x(), sy * half.y(), sz * half.z(), 1.0F);
                        clipFromLocal.transform(point);
                        if (!Float.isFinite(point.x()) || !Float.isFinite(point.y())
                                || !Float.isFinite(point.w()) || Math.abs(point.w()) < 1.0E-7F) {
                            invalid = true;
                            continue;
                        }
                        float inverseW = 1.0F / point.w();
                        float u = point.x() * inverseW * 0.5F + 0.5F;
                        float v = point.y() * inverseW * 0.5F + 0.5F;
                        if (!Float.isFinite(u) || !Float.isFinite(v)) {
                            invalid = true;
                            continue;
                        }
                        minU = Math.min(minU, u);
                        minV = Math.min(minV, v);
                        maxU = Math.max(maxU, u);
                        maxV = Math.max(maxV, v);
                    }
                }
            }
            if (invalid || !Float.isFinite(minU)) {
                return aligned(0, 0, shadowMapSize, shadowMapSize, shadowMapSize);
            }

            int x0 = Math.clamp((int) Math.floor(minU * shadowMapSize) - 1, 0, shadowMapSize);
            int y0 = Math.clamp((int) Math.floor(minV * shadowMapSize) - 1, 0, shadowMapSize);
            int x1 = Math.clamp((int) Math.ceil(maxU * shadowMapSize) + 1, 0, shadowMapSize);
            int y1 = Math.clamp((int) Math.ceil(maxV * shadowMapSize) + 1, 0, shadowMapSize);
            if (x1 <= x0 || y1 <= y0) return new HierarchyLayout(0, 0, 0, 0, 0);
            return aligned(x0, y0, x1, y1, shadowMapSize);
        }

        private static int coverage(int level) {
            int result = BASE_BLOCK_SIZE;
            for (int index = 0; index < level; index++) result *= BASE_BLOCK_SIZE;
            return result;
        }

        private static HierarchyLayout aligned(int x0, int y0, int x1, int y1, int mapSize) {
            int alignedX0 = Math.max(0, x0 / BASE_BLOCK_SIZE * BASE_BLOCK_SIZE);
            int alignedY0 = Math.max(0, y0 / BASE_BLOCK_SIZE * BASE_BLOCK_SIZE);
            int alignedX1 = Math.min(mapSize, ceilDivide(x1, BASE_BLOCK_SIZE) * BASE_BLOCK_SIZE);
            int alignedY1 = Math.min(mapSize, ceilDivide(y1, BASE_BLOCK_SIZE) * BASE_BLOCK_SIZE);
            int width = Math.max(0, alignedX1 - alignedX0);
            int height = Math.max(0, alignedY1 - alignedY0);
            if (width == 0 || height == 0) return new HierarchyLayout(0, 0, 0, 0, 0);
            int levels = 1;
            int levelWidth = ceilDivide(width, BASE_BLOCK_SIZE);
            int levelHeight = ceilDivide(height, BASE_BLOCK_SIZE);
            while ((levelWidth > 1 || levelHeight > 1) && levels < MAX_LEVELS) {
                levelWidth = ceilDivide(levelWidth, BASE_BLOCK_SIZE);
                levelHeight = ceilDivide(levelHeight, BASE_BLOCK_SIZE);
                levels++;
            }
            return new HierarchyLayout(alignedX0, alignedY0, width, height, levels);
        }

        private static int ceilDivide(int value, int divisor) {
            return (value + divisor - 1) / divisor;
        }
    }

    private record HierarchyCacheKey(long shadowMapRevision, HierarchyLayout hierarchy, long targetHash) {
    }

    private record Scissor(int x, int y, int width, int height) {
        private static final Scissor EMPTY = new Scissor(0, 0, 0, 0);

        boolean empty() {
            return width <= 0 || height <= 0;
        }

        Scissor union(Scissor other) {
            if (this.empty()) return other;
            if (other.empty()) return this;
            int x0 = Math.min(this.x, other.x);
            int y0 = Math.min(this.y, other.y);
            int x1 = Math.max(this.x + this.width, other.x + other.width);
            int y1 = Math.max(this.y + this.height, other.y + other.height);
            return new Scissor(x0, y0, x1 - x0, y1 - y0);
        }
    }

    // 盒形介质区间追踪的相机、光空间、ROI 与表现参数 UBO。
    private static final class IntervalUniform implements AutoCloseable {
        private static final int BLOCK_SIZE = new Std140SizeCalculator()
                .putMat4f().putMat4f().putMat4f().putMat4f()
                .putMat4f().putMat4f().putMat4f().putMat4f().putMat4f().putMat4f()
                .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
                .putVec4().putVec4().putVec4().putVec4().putVec4()
                .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4()
                .putVec4().putVec4().putVec4()
                .get();

        private final MappableRingBuffer buffer = new MappableRingBuffer(
                () -> "Battle volumetric interval uniforms", 130, BLOCK_SIZE);

        void upload(
                BattleScene.Frame frame,
                BattleScene.VolumeRegion region,
                Lighting.LightSpace lightSpace,
                Lighting.Settings lighting,
                HierarchyLayout hierarchy,
                boolean globalEnabled,
                PointPlan point
        ) {
            Matrix4f inverseViewProjection = new Matrix4f(frame.camera().projection())
                    .mul(frame.camera().view())
                    .invert();
            Matrix4f worldToLocal = region.localToWorld().invert(new Matrix4f());
            Matrix4f gridModel = frame.commands().stream()
                    .filter(command -> command.material() == BattleScene.Material.AUXILIARY_GRID)
                    .map(BattleScene.RenderCommand::model)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            Matrix4f volumeToGrid = gridModel == null
                    ? new Matrix4f()
                    : gridModel.invert(new Matrix4f()).mul(region.localToWorld());
            boolean gridEnabled = gridModel != null;
            Vector3f halfSize = region.halfSize();
            float orthographicProjection = Math.abs(frame.camera().projection().m33()) > 0.5F ? 1.0F : 0.0F;
            HierarchyLayout safeHierarchy = hierarchy == null
                    ? new HierarchyLayout(0, 0, 0, 0, 0)
                    : hierarchy;
            try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.buffer.currentBuffer(), false, true)) {
                ByteBuffer data = mapped.data();
                inverseViewProjection.get(0, data);
                worldToLocal.get(64, data);
                lightSpace.viewProjection().get(128, data);
                volumeToGrid.get(192, data);
                for (int face = 0; face < POINT_FACE_COUNT; face++) {
                    Matrix4f matrix = point.enabled()
                            ? point.faceViewProjections()[face]
                            : new Matrix4f();
                    matrix.get(256 + face * 64, data);
                }
                putVec4(data, 640, halfSize.x(), halfSize.y(), halfSize.z(), orthographicProjection);
                putVec4(data, 656,
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_BASE_DENSITY()),
                        Math.max(0.001F, VisualConfig.VOLUMETRIC_EDGE_FALLOFF()),
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_SCATTERING_STRENGTH()),
                        Math.clamp(VisualConfig.VOLUMETRIC_SHADOW_STRENGTH(), 0.0F, 1.0F));
                putVec4(data, 672,
                        Math.max(0.0F, lighting.shadowBias()),
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_PARALLEL_UV_THRESHOLD()),
                        Math.max(1.0E-8F, VisualConfig.VOLUMETRIC_TRAVERSAL_EPSILON()),
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_MIN_OPTICAL_DEPTH()));
                putVec4(data, 688, lighting.shadowMapSize(), lighting.shadowMapSize(),
                        safeHierarchy.x(), safeHierarchy.y());
                putVec4(data, 704, safeHierarchy.width(), safeHierarchy.height(),
                        Math.max(0, safeHierarchy.levelCount() - 1),
                        VisualConfig.VOLUMETRIC_DEBUG_MODE().ordinal());
                putVec4(data, 720,
                        Math.clamp(VisualConfig.VOLUMETRIC_MAX_HIERARCHY_VISITS(), 1, 512),
                        Math.clamp(VisualConfig.VOLUMETRIC_MAX_LEAF_VISITS(), 1, 256),
                        Math.clamp(VisualConfig.VOLUMETRIC_MINIMUM_FOG_MULTIPLIER(), 0.0F, 1.0F),
                        Math.clamp(VisualConfig.VOLUMETRIC_SHADOW_SCATTERING_MULTIPLIER(), 0.0F, 1.0F));
                putColor(data, 736, VisualConfig.VOLUMETRIC_BASE_FOG_COLOR());
                putColor(data, 752, VisualConfig.VOLUMETRIC_SUN_SCATTER_COLOR());
                putVec4(data, 768,
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_TONE_MAPPING_EXPOSURE()),
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_TONE_MAPPING_CONTRAST()),
                        Math.clamp(VisualConfig.VOLUMETRIC_TONE_MAPPING_PIVOT(), 0.0F, 1.0F),
                        VisualConfig.VOLUMETRIC_TONE_MAPPING_ENABLED() ? 1.0F : 0.0F);
                putVec4(data, 784,
                        Math.max(1.0F, VisualConfig.VOLUMETRIC_SHADOW_OPTICAL_DEPTH_GAIN()),
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_SHADOW_EXTINCTION_GAIN()),
                        0.0F,
                        0.0F);
                putVec4(data, 800,
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_GRID_SCATTERING_BOUNDARY_OFFSET()),
                        gridEnabled ? 1.0F : 0.0F,
                        Math.clamp(VisualConfig.VOLUMETRIC_BELOW_GRID_SCATTERING_MULTIPLIER(), 0.0F, 1.0F),
                        globalEnabled ? 1.0F : 0.0F);
                Vector3f pointPosition = point.enabled() ? point.lightPosition() : new Vector3f();
                putVec4(data, 816, pointPosition.x(), pointPosition.y(), pointPosition.z(),
                        point.enabled() ? Math.max(0.0F, VisualConfig.SOUL_VOLUMETRIC_RADIUS()) : 0.0F);
                if (point.enabled()) {
                    int pointColor = point.lightColor();
                    putVec4(data, 832,
                            ((pointColor >>> 16) & 0xFF) / 255.0F,
                            ((pointColor >>> 8) & 0xFF) / 255.0F,
                            (pointColor & 0xFF) / 255.0F,
                            Math.clamp(VisualConfig.SOUL_VOLUMETRIC_GRID_TRANSMISSION(), 0.0F, 1.0F));
                } else {
                    putVec4(data, 832, 0.0F, 0.0F, 0.0F, 0.0F);
                }
                PointTargetLayout pointLayout = point.layout();
                putVec4(data, 848,
                        pointLayout == null ? 1 : pointLayout.faceSize(),
                        pointLayout == null ? 1 : pointLayout.border(),
                        pointLayout == null ? 3 : pointLayout.tileSize(),
                        pointLayout == null ? 0 : pointLayout.levelCount() - 1);
                putVec4(data, 864,
                        Math.max(0.0F, VisualConfig.SOUL_POINT_SHADOW_RADIAL_BIAS()),
                        Math.max(1.0E-8F, VisualConfig.SOUL_VOLUMETRIC_TRAVERSAL_EPSILON()),
                        Math.max(0.0F, VisualConfig.SOUL_VOLUMETRIC_INTENSITY()),
                        point.enabled() ? 1.0F : 0.0F);
                putVec4(data, 880,
                        point.enabled() ? point.requiredFaceMask() : 0,
                        Math.clamp(VisualConfig.SOUL_VOLUMETRIC_MAX_HIERARCHY_VISITS(), 1, 256),
                        Math.clamp(VisualConfig.SOUL_VOLUMETRIC_MAX_TEXEL_VISITS(), 1, 128),
                        VisualConfig.SOUL_VOLUMETRIC_DEBUG_MODE().ordinal());
                putVec4(data, 896,
                        Math.max(1.0E-4F, VisualConfig.SOUL_VOLUMETRIC_CORE_RADIUS()),
                        Math.clamp(VisualConfig.SOUL_VOLUMETRIC_CUTOFF_FEATHER(), 0.0F, 1.0F),
                        Math.clamp(VisualConfig.SOUL_VOLUMETRIC_PHASE_G(), -0.99F, 0.99F),
                        Math.max(1.0E-4F, VisualConfig.SOUL_VOLUMETRIC_RADIANCE_SOFT_CLIP()));
                putVec4(data, 912,
                        Math.clamp(VisualConfig.SOUL_VOLUMETRIC_SHADOW_STRENGTH(), 0.0F, 1.0F),
                        Math.clamp(VisualConfig.SOUL_VOLUMETRIC_MINIMUM_RADIANCE_MULTIPLIER(), 0.0F, 1.0F),
                        Math.max(1.0F, VisualConfig.SOUL_VOLUMETRIC_SHADOW_SOURCE_GAIN()),
                        Math.max(0.0F, VisualConfig.SOUL_VOLUMETRIC_SHADOW_EXTINCTION_GAIN()));
                putVec4(data, 928,
                        Math.max(0.0F, VisualConfig.SOUL_VOLUMETRIC_TONE_MAPPING_EXPOSURE()),
                        Math.max(0.0F, VisualConfig.SOUL_VOLUMETRIC_TONE_MAPPING_CONTRAST()),
                        Math.clamp(VisualConfig.SOUL_VOLUMETRIC_TONE_MAPPING_PIVOT(), 0.0F, 1.0F),
                        VisualConfig.SOUL_VOLUMETRIC_TONE_MAPPING_ENABLED() ? 1.0F : 0.0F);
                putVec4(data, 944,
                        Math.max(0.0F, VisualConfig.VOLUMETRIC_DITHER_STRENGTH_LSB()),
                        0.0F,
                        0.0F,
                        0.0F);
            }
        }

        void bind(RenderPass pass) {
            pass.setUniform("VolumetricUniform", this.buffer.currentBuffer());
        }

        void rotate() {
            this.buffer.rotate();
        }

        @Override
        public void close() {
            this.buffer.close();
        }
    }

    // Min-Max Base/Reduce Pass 的逐层尺寸 UBO。
    private static final class MinMaxUniform implements AutoCloseable {
        private static final int BLOCK_SIZE = new Std140SizeCalculator().putVec4().putVec4().get();
        private final MappableRingBuffer buffer = new MappableRingBuffer(
                () -> "Battle shadow Min-Max uniforms", 130, BLOCK_SIZE);

        void uploadBase(int shadowMapSize, TextureTarget output, HierarchyLayout hierarchy) {
            upload(shadowMapSize, shadowMapSize, output.width, output.height,
                    hierarchy.x(), hierarchy.y(), hierarchy.width(), hierarchy.height());
        }

        void uploadReduce(TextureTarget source, TextureTarget output) {
            upload(source.width, source.height, output.width, output.height, 0, 0, 0, 0);
        }

        private void upload(
                int sourceWidth,
                int sourceHeight,
                int targetWidth,
                int targetHeight,
                int regionX,
                int regionY,
                int regionWidth,
                int regionHeight
        ) {
            try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                    .mapBuffer(this.buffer.currentBuffer(), false, true)) {
                ByteBuffer data = mapped.data();
                putVec4(data, 0, sourceWidth, sourceHeight, targetWidth, targetHeight);
                putVec4(data, 16, regionX, regionY, regionWidth, regionHeight);
            }
        }

        void bind(RenderPass pass) {
            pass.setUniform("ShadowMinMaxUniform", this.buffer.currentBuffer());
        }

        void rotate() {
            this.buffer.rotate();
        }

        @Override
        public void close() {
            this.buffer.close();
        }
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
                0.0F);
    }
}
