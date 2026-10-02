package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import cn.jehorstudio.minetale.MineTale;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

// 独占一个可走面扇区的 GPU 状态、模拟 pass 与拾取资源。
final class SnowtownGpuCrowdSector implements AutoCloseable {

    private static final Vector4f WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final float SIMULATION_STEP_SECONDS = 0.1F;
    private static final int OCCUPANCY_BUCKET_COLUMNS = 4;
    private static final int OCCUPANCY_BUCKET_ROWS = 4;
    private static final int MIN_STATE_TEXTURE_SIZE = 8;

    private TextureTarget densityField;
    private TextureTarget occupancyAtlas;

    private final int stateTextureSize;
    private final float simulationPhaseSeconds;
    private SnowtownGpuCrowdStateAtlas stateAtlas;
    private int statePage = -1;
    private SnowtownGpuCrowdUniforms uniforms;
    private DynamicTexture staticFieldTexture;
    private DynamicTexture lightFieldTexture;
    private DynamicTexture flowFieldTexture;
    private DynamicTexture destinationDataTexture;
    private boolean currentIsA = true;
    private boolean previousAvailable;
    private float simulationAccumulator;
    private int initializedGeneration = Integer.MIN_VALUE;
    private int uploadedGeometryGeneration = Integer.MIN_VALUE;
    private int uploadedLightGeneration = Integer.MIN_VALUE;
    private int uploadedNavigationGeneration = Integer.MIN_VALUE;
    private int uploadedSpawnGeneration = Integer.MIN_VALUE;
    private int appliedRebaseGeneration = Integer.MIN_VALUE;
    private int initializedAgentCount;
    private int lastAgentCount;
    private int lastVisibleAgentCount;
    private int lastWalkableCount;
    private boolean failureLogged;
    private int lastSimulationPasses;

    SnowtownGpuCrowdSector(
            int stateTextureSize,
            SnowtownGpuCrowdStateAtlas stateAtlas
    ) {
        this.stateTextureSize = stateTextureSize;
        this.stateAtlas = Objects.requireNonNull(stateAtlas);
        if (stateTextureSize > SnowtownGpuCrowdStateAtlas.STATE_PAGE_SIZE) {
            throw new IllegalArgumentException("Snowtown GPU状态纹理超过宏扇区页尺寸");
        }
        this.statePage = stateAtlas.acquirePage();
        this.simulationPhaseSeconds = simulationPhaseSeconds(this.statePage);
    }

    private float interactionInterpolation() {
        return this.previousAvailable
                ? Math.clamp(
                        this.simulationAccumulator / SIMULATION_STEP_SECONDS,
                        0.0F,
                        1.0F
                )
                : 1.0F;
    }

    int statePage() {
        return this.statePage;
    }

    int stateTextureSize() {
        return this.stateTextureSize;
    }

    boolean currentStateIsA() {
        return this.currentIsA;
    }

    boolean previousStateAvailable() {
        return this.previousAvailable;
    }

    float renderInterpolation() {
        return interactionInterpolation();
    }

    int initializedAgentCount() {
        return this.initializedAgentCount;
    }

    boolean ready() {
        return !this.failureLogged && this.uniforms != null;
    }

    void drawInteractionPick(
            TextureTarget target,
            RenderLevelStageEvent.AfterEntities event,
            SnowtownCrowdClient.FrameState frame,
            int agentCount,
            int candidateToken,
            float interactionReach
    ) {
        this.uniforms.uploadPick(
                frame,
                interactionInterpolation(),
                candidateToken,
                interactionReach
        );
        try {
            drawPickAgents(target, event, agentCount);
        } finally {
            this.uniforms.rotateInteraction();
        }
    }

    int lastAgentCount() {
        return this.lastAgentCount;
    }

    int lastVisibleAgentCount() {
        return this.lastVisibleAgentCount;
    }

    int lastWalkableCount() {
        return this.lastWalkableCount;
    }

    int lastSimulationPasses() {
        return this.lastSimulationPasses;
    }

    static int stateTextureSize(int targetAgentCount) {
        int required = Math.max(1, targetAgentCount);
        int size = MIN_STATE_TEXTURE_SIZE;
        while (size * size < required) {
            size *= 2;
        }
        return size;
    }

    private static float simulationPhaseSeconds(int statePage) {
        int phaseBucket = Math.floorMod(
                statePage * 37,
                SnowtownGpuCrowdStateAtlas.MAX_PAGES);
        return (phaseBucket + 0.5F)
                * SIMULATION_STEP_SECONDS
                / SnowtownGpuCrowdStateAtlas.MAX_PAGES;
    }

    void simulate(SnowtownCrowdClient.FrameState frame) {
        this.lastSimulationPasses = 0;
        // 初始化失败在本次运行内锁存
        if (this.failureLogged) {
            return;
        }

        try {
            if (frame.agentCount() > this.stateTextureSize * this.stateTextureSize) {
                throw new IllegalStateException("Snowtown GPU 可走面人口超过状态纹理容量");
            }
            ensureResources();
            ensureStaticField(
                    frame.staticField(),
                    frame.geometryGeneration(),
                    frame.lightGeneration(),
                    frame.navigationGeneration(),
                    frame.spawnGeneration(),
                    frame.spawnDestinationPixels());
            try {
                boolean reset = this.initializedGeneration != frame.generation();
                if (reset) {
                    runSimulationPass(
                            SIMULATION_STEP_SECONDS,
                            true,
                            frame.agentCount(),
                            frame.elapsedSeconds());
                    this.lastSimulationPasses++;
                    this.simulationAccumulator = this.simulationPhaseSeconds;
                    this.previousAvailable = false;
                    this.initializedGeneration = frame.generation();
                    this.initializedAgentCount = frame.agentCount();
                    this.appliedRebaseGeneration = frame.rebaseGeneration();
                } else {
                    if (this.appliedRebaseGeneration != frame.rebaseGeneration()) {
                        runMigrationPass(frame);
                        this.lastSimulationPasses++;
                        this.initializedAgentCount = frame.agentCount();
                        this.appliedRebaseGeneration = frame.rebaseGeneration();
                        this.simulationAccumulator = this.simulationPhaseSeconds;
                        this.previousAvailable = false;
                    }
                    this.simulationAccumulator = Math.min(
                            this.simulationAccumulator + frame.deltaSeconds(),
                            SIMULATION_STEP_SECONDS * 2.0F
                    );
                    if (this.simulationAccumulator >= SIMULATION_STEP_SECONDS) {
                        runSimulationPass(
                                SIMULATION_STEP_SECONDS,
                                false,
                                frame.agentCount(),
                                frame.elapsedSeconds()
                        );
                        this.lastSimulationPasses++;
                        this.initializedAgentCount = frame.agentCount();
                        this.simulationAccumulator -= SIMULATION_STEP_SECONDS;
                        this.previousAvailable = true;
                    }
                }

                this.lastAgentCount = this.initializedAgentCount;
                this.lastVisibleAgentCount = frame.draw()
                        ? Math.min(frame.visibleAgentCount(), this.initializedAgentCount)
                        : 0;
                this.failureLogged = false;
            } finally {
                this.uniforms.rotate();
            }
        } catch (RuntimeException | LinkageError failure) {
            if (!this.failureLogged) {
                MineTale.LOGGER.error("Snowtown GPU 人群模拟失败", failure);
                this.failureLogged = true;
            }
            releaseSectorResources();
        }
    }

    @Override
    public void close() {
        releaseSectorResources();
        this.failureLogged = false;
    }

    private void ensureResources() {
        if (this.uniforms != null) {
            return;
        }
        SnowtownGpuCrowdRenderer.ensureSimulationSharedResources();
        this.densityField = new TextureTarget(
                "Snowtown GPU crowd density field",
                SnowtownGpuCrowdStaticField.SIZE,
                SnowtownGpuCrowdStaticField.SIZE,
                false);
        this.occupancyAtlas = new TextureTarget(
                "Snowtown GPU crowd occupancy buckets",
                SnowtownGpuCrowdStaticField.SIZE * OCCUPANCY_BUCKET_COLUMNS,
                SnowtownGpuCrowdStaticField.SIZE * OCCUPANCY_BUCKET_ROWS,
                true
        );
        this.uniforms = new SnowtownGpuCrowdUniforms(
                this.stateTextureSize,
                stateOriginX(),
                stateOriginY());
        this.currentIsA = true;
        this.previousAvailable = false;
        this.simulationAccumulator = this.simulationPhaseSeconds;
        this.initializedGeneration = Integer.MIN_VALUE;
        this.initializedAgentCount = 0;
        this.appliedRebaseGeneration = Integer.MIN_VALUE;
    }

    private int stateOriginX() {
        return this.stateAtlas.stateOriginX(this.statePage);
    }

    private int stateOriginY() {
        return this.stateAtlas.stateOriginY(this.statePage);
    }

    private void runSimulationPass(
            float deltaSeconds,
            boolean reset,
            int agentCount,
            float elapsedSeconds
    ) {
        this.uniforms.uploadSimulation(
                deltaSeconds,
                reset,
                agentCount,
                elapsedSeconds,
                this.lastWalkableCount
        );
        updateBehavior();
        if (!reset) {
            updateOccupancy(agentCount);
            updateDensity();
        }
        updateVelocity();
        updatePosition();
        this.currentIsA = !this.currentIsA;
    }

    private void runMigrationPass(SnowtownCrowdClient.FrameState frame) {
        this.uniforms.uploadMigration(
                frame.agentCount(),
                frame.elapsedSeconds(),
                this.lastWalkableCount,
                frame.rebaseX(),
                frame.rebaseZ()
        );
        updateBehavior();
        updateVelocity();
        updatePosition();
        this.currentIsA = !this.currentIsA;
    }

    private void updateBehavior() {
        TextureTarget position = this.currentIsA ? this.stateAtlas.positionA() : this.stateAtlas.positionB();
        TextureTarget velocity = this.currentIsA ? this.stateAtlas.velocityA() : this.stateAtlas.velocityB();
        TextureTarget inputBehavior = this.currentIsA ? this.stateAtlas.behaviorA() : this.stateAtlas.behaviorB();
        TextureTarget outputBehavior = this.currentIsA ? this.stateAtlas.behaviorB() : this.stateAtlas.behaviorA();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Snowtown GPU crowd behavior update",
                Objects.requireNonNull(outputBehavior.getColorTextureView()),
                OptionalInt.empty(),
                null,
                OptionalDouble.empty()
        )) {
            pass.setViewport(stateOriginX(), stateOriginY(), this.stateTextureSize, this.stateTextureSize);
            pass.setPipeline(SnowtownGpuCrowdPipelines.UPDATE_BEHAVIOR);
            pass.bindSampler("PositionState", Objects.requireNonNull(position.getColorTextureView()));
            pass.bindSampler("VelocityState", Objects.requireNonNull(velocity.getColorTextureView()));
            pass.bindSampler("BehaviorState", Objects.requireNonNull(inputBehavior.getColorTextureView()));
            pass.bindSampler("StaticField", this.staticFieldTexture.getTextureView());
            pass.bindSampler(
                    "AppearanceData",
                    SnowtownGpuCrowdRenderer.appearanceDataTexture().getTextureView()
            );
            this.uniforms.bindSimulation(pass);
            pass.draw(0, 3);
        }
    }

    private void updateDensity() {
        TextureTarget position = this.currentIsA ? this.stateAtlas.positionA() : this.stateAtlas.positionB();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Snowtown GPU crowd density update",
                Objects.requireNonNull(this.densityField.getColorTextureView()),
                OptionalInt.empty(),
                null,
                OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, this.densityField.width, this.densityField.height);
            pass.setPipeline(SnowtownGpuCrowdPipelines.UPDATE_DENSITY);
            pass.bindSampler("PositionState", Objects.requireNonNull(position.getColorTextureView()));
            pass.bindSampler("StaticField", this.staticFieldTexture.getTextureView());
            bindOccupancy(pass);
            this.uniforms.bindSimulation(pass);
            pass.draw(0, 3);
        }
    }

    private void updateOccupancy(int agentCount) {
        TextureTarget position = this.currentIsA ? this.stateAtlas.positionA() : this.stateAtlas.positionB();
        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(
                VertexFormat.Mode.QUADS
        );
        SnowtownGpuCrowdRenderer.OccupancyMesh occupancyMesh =
                SnowtownGpuCrowdRenderer.occupancyMesh();
        GpuBuffer indices = sequential.getBuffer(occupancyMesh.indexCount());
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Snowtown GPU crowd occupancy buckets",
                Objects.requireNonNull(this.occupancyAtlas.getColorTextureView()),
                OptionalInt.of(0),
                Objects.requireNonNull(this.occupancyAtlas.getDepthTextureView()),
                OptionalDouble.of(1.0D)
        )) {
            pass.setViewport(0, 0, this.occupancyAtlas.width, this.occupancyAtlas.height);
            pass.setPipeline(SnowtownGpuCrowdPipelines.BUILD_OCCUPANCY);
            pass.bindSampler(
                    "PositionState",
                    Objects.requireNonNull(position.getColorTextureView())
            );
            this.uniforms.bindSimulation(pass);
            pass.setVertexBuffer(0, occupancyMesh.vertices());
            pass.setIndexBuffer(indices, sequential.type());
            pass.drawIndexed(0, 0, occupancyMesh.indexCount(), agentCount);
        }
    }

    private void updateVelocity() {
        TextureTarget position = this.currentIsA ? this.stateAtlas.positionA() : this.stateAtlas.positionB();
        TextureTarget inputVelocity = this.currentIsA ? this.stateAtlas.velocityA() : this.stateAtlas.velocityB();
        TextureTarget outputVelocity = this.currentIsA ? this.stateAtlas.velocityB() : this.stateAtlas.velocityA();
        TextureTarget nextBehavior = this.currentIsA ? this.stateAtlas.behaviorB() : this.stateAtlas.behaviorA();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Snowtown GPU crowd reference velocity update",
                Objects.requireNonNull(outputVelocity.getColorTextureView()),
                OptionalInt.empty(),
                null,
                OptionalDouble.empty()
        )) {
            pass.setViewport(stateOriginX(), stateOriginY(), this.stateTextureSize, this.stateTextureSize);
            pass.setPipeline(SnowtownGpuCrowdPipelines.UPDATE_VELOCITY_REFERENCE);
            pass.bindSampler("PositionState", Objects.requireNonNull(position.getColorTextureView()));
            pass.bindSampler("VelocityState", Objects.requireNonNull(inputVelocity.getColorTextureView()));
            pass.bindSampler("StaticField", this.staticFieldTexture.getTextureView());
            pass.bindSampler("FlowField", this.flowFieldTexture.getTextureView());
            pass.bindSampler("DensityField", Objects.requireNonNull(this.densityField.getColorTextureView()));
            pass.bindSampler("BehaviorState", Objects.requireNonNull(nextBehavior.getColorTextureView()));
            pass.bindSampler(
                    "AppearanceData",
                    SnowtownGpuCrowdRenderer.appearanceDataTexture().getTextureView()
            );
            bindOccupancy(pass);
            this.uniforms.bindSimulation(pass);
            pass.draw(0, 3);
        }
    }

    private void updatePosition() {
        TextureTarget inputPosition = this.currentIsA ? this.stateAtlas.positionA() : this.stateAtlas.positionB();
        TextureTarget outputPosition = this.currentIsA ? this.stateAtlas.positionB() : this.stateAtlas.positionA();
        TextureTarget nextVelocity = this.currentIsA ? this.stateAtlas.velocityB() : this.stateAtlas.velocityA();
        TextureTarget nextBehavior = this.currentIsA ? this.stateAtlas.behaviorB() : this.stateAtlas.behaviorA();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Snowtown GPU crowd position update",
                Objects.requireNonNull(outputPosition.getColorTextureView()),
                OptionalInt.empty(),
                null,
                OptionalDouble.empty()
        )) {
            pass.setViewport(stateOriginX(), stateOriginY(), this.stateTextureSize, this.stateTextureSize);
            pass.setPipeline(SnowtownGpuCrowdPipelines.UPDATE_POSITION);
            pass.bindSampler("PositionState", Objects.requireNonNull(inputPosition.getColorTextureView()));
            pass.bindSampler("VelocityState", Objects.requireNonNull(nextVelocity.getColorTextureView()));
            pass.bindSampler("BehaviorState", Objects.requireNonNull(nextBehavior.getColorTextureView()));
            pass.bindSampler("StaticField", this.staticFieldTexture.getTextureView());
            pass.bindSampler("DestinationData", this.destinationDataTexture.getTextureView());
            pass.bindSampler(
                    "AppearanceData",
                    SnowtownGpuCrowdRenderer.appearanceDataTexture().getTextureView()
            );
            bindOccupancy(pass);
            this.uniforms.bindSimulation(pass);
            pass.draw(0, 3);
        }
    }

    private void drawPickAgents(
            RenderTarget target,
            RenderLevelStageEvent.AfterEntities event,
            int agentCount
    ) {
        TextureTarget current = this.currentIsA ? this.stateAtlas.positionA() : this.stateAtlas.positionB();
        TextureTarget previous = this.previousAvailable
                ? (this.currentIsA ? this.stateAtlas.positionB() : this.stateAtlas.positionA())
                : current;
        TextureTarget currentVelocity = this.currentIsA ? this.stateAtlas.velocityA() : this.stateAtlas.velocityB();
        TextureTarget previousVelocity = this.previousAvailable
                ? (this.currentIsA ? this.stateAtlas.velocityB() : this.stateAtlas.velocityA())
                : currentVelocity;
        TextureTarget currentBehavior = this.currentIsA ? this.stateAtlas.behaviorA() : this.stateAtlas.behaviorB();
        TextureTarget previousBehavior = this.previousAvailable
                ? (this.currentIsA ? this.stateAtlas.behaviorB() : this.stateAtlas.behaviorA())
                : currentBehavior;
        var transform = RenderSystem.getDynamicUniforms().writeTransform(
                event.getModelViewMatrix(), WHITE, ZERO, IDENTITY, 1.0F);
        RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        List<SnowtownGpuCrowdAppearanceMesh> appearanceMeshes =
                SnowtownGpuCrowdRenderer.entityAppearanceMeshes();
        int maximumIndexCount = appearanceMeshes.stream()
                .mapToInt(SnowtownGpuCrowdAppearanceMesh::indexCount)
                .max()
                .orElseThrow();
        GpuBuffer indices = sequential.getBuffer(maximumIndexCount);
        // getTexture 可能执行首次上传，必须在 RenderPass 开始前完成。
        List<GpuTextureView> diffuseTextures = appearanceMeshes.stream()
                .map(mesh -> Minecraft.getInstance().getTextureManager()
                        .getTexture(mesh.appearance().textureResource())
                        .getTextureView())
                .toList();
        GpuTextureView lightMapTexture = Minecraft.getInstance()
                .gameRenderer.lightTexture().getTextureView();

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Snowtown GPU crowd instanced draw",
                Objects.requireNonNull(target.getColorTextureView()),
                OptionalInt.empty(),
                target.getDepthTextureView(),
                OptionalDouble.empty()
        )) {
            pass.setViewport(0, 0, target.width, target.height);
            pass.setPipeline(SnowtownGpuCrowdPipelines.PICK_AGENTS);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            this.uniforms.bindInteraction(pass);
            pass.bindSampler("PreviousPositionState", Objects.requireNonNull(previous.getColorTextureView()));
            pass.bindSampler("CurrentPositionState", Objects.requireNonNull(current.getColorTextureView()));
            pass.bindSampler("PreviousVelocityState", Objects.requireNonNull(previousVelocity.getColorTextureView()));
            pass.bindSampler("CurrentVelocityState", Objects.requireNonNull(currentVelocity.getColorTextureView()));
            pass.bindSampler("PreviousBehaviorState", Objects.requireNonNull(previousBehavior.getColorTextureView()));
            pass.bindSampler("CurrentBehaviorState", Objects.requireNonNull(currentBehavior.getColorTextureView()));
            pass.bindSampler("StaticField", this.staticFieldTexture.getTextureView());
            pass.bindSampler("LightField", this.lightFieldTexture.getTextureView());
            pass.bindSampler("Sampler2", lightMapTexture);
            pass.setIndexBuffer(indices, sequential.type());
            for (int appearanceIndex = 0;
                    appearanceIndex < appearanceMeshes.size();
                    appearanceIndex++) {
                SnowtownGpuCrowdAppearanceMesh mesh = appearanceMeshes.get(appearanceIndex);
                int instanceCount = mesh.instanceCount(agentCount);
                if (instanceCount == 0) {
                    continue;
                }
                pass.setUniform("CrowdAppearance", mesh.appearanceUniform());
                pass.bindSampler("AnimationFrames", mesh.animationTexture().getTextureView());
                pass.bindSampler("Sampler0", diffuseTextures.get(appearanceIndex));
                pass.setVertexBuffer(0, mesh.vertices());
                pass.drawIndexed(0, 0, mesh.indexCount(), instanceCount);
            }
        }
    }

    private void bindOccupancy(RenderPass pass) {
        pass.bindSampler(
                "OccupancyAtlas",
                Objects.requireNonNull(this.occupancyAtlas.getColorTextureView())
        );
    }

    private void releaseSectorResources() {
        releaseStaticFieldTextures();
        if (this.densityField != null) {
            this.densityField.destroyBuffers();
            this.densityField = null;
        }
        if (this.occupancyAtlas != null) {
            this.occupancyAtlas.destroyBuffers();
            this.occupancyAtlas = null;
        }
        if (this.uniforms != null) {
            this.uniforms.close();
            this.uniforms = null;
        }
        if (this.statePage >= 0) {
            this.stateAtlas.releasePage(this.statePage);
            this.statePage = -1;
        }
        this.currentIsA = true;
        this.previousAvailable = false;
        this.simulationAccumulator = 0.0F;
        this.initializedGeneration = Integer.MIN_VALUE;
        this.initializedAgentCount = 0;
        this.appliedRebaseGeneration = Integer.MIN_VALUE;
        this.lastAgentCount = 0;
        this.lastWalkableCount = 0;
        this.lastSimulationPasses = 0;
    }

    private void ensureStaticField(
            SnowtownGpuCrowdStaticField field,
            int geometryGeneration,
            int lightGeneration,
            int navigationGeneration,
            int spawnGeneration,
            int[] spawnDestinationPixels
    ) {
        if (this.uploadedGeometryGeneration != geometryGeneration
                || this.staticFieldTexture == null) {
            if (this.staticFieldTexture != null) {
                this.staticFieldTexture.close();
            }
            this.staticFieldTexture = createStaticTexture(
                    "Snowtown GPU crowd static field",
                    field.fieldPixels(),
                    SnowtownGpuCrowdStaticField.SIZE,
                    SnowtownGpuCrowdStaticField.SIZE);
            this.stateAtlas.uploadStaticField(this.statePage, field.fieldPixels());
            this.uploadedGeometryGeneration = geometryGeneration;
            this.lastWalkableCount = field.walkableCount();
        }
        if (this.lightFieldTexture == null) {
            this.lightFieldTexture = createStaticTexture(
                    "Snowtown GPU crowd light field",
                    field.lightPixels(),
                    SnowtownGpuCrowdStaticField.SIZE,
                    SnowtownGpuCrowdStaticField.SIZE);
            this.stateAtlas.uploadLightField(this.statePage, field.lightPixels());
            this.uploadedLightGeneration = lightGeneration;
        } else if (this.uploadedLightGeneration != lightGeneration) {
            updateStaticTexture(this.lightFieldTexture, field.lightPixels());
            this.stateAtlas.uploadLightField(this.statePage, field.lightPixels());
            this.uploadedLightGeneration = lightGeneration;
        }
        boolean navigationChanged =
                this.uploadedNavigationGeneration != navigationGeneration;
        if (navigationChanged || this.flowFieldTexture == null) {
            if (this.flowFieldTexture != null) {
                this.flowFieldTexture.close();
            }
            this.flowFieldTexture = createStaticTexture(
                    "Snowtown GPU crowd flow field",
                    field.flowPixels(),
                    SnowtownGpuCrowdStaticField.FLOW_WIDTH,
                    SnowtownGpuCrowdStaticField.FLOW_HEIGHT);
            this.uploadedNavigationGeneration = navigationGeneration;
        }
        if (navigationChanged
                || this.uploadedSpawnGeneration != spawnGeneration
                || this.destinationDataTexture == null) {
            if (this.destinationDataTexture != null) {
                this.destinationDataTexture.close();
            }
            this.destinationDataTexture = createStaticTexture(
                    "Snowtown GPU crowd destination data",
                    destinationPixelsWithSpawnLocations(
                            field.destinationPixels(),
                            spawnDestinationPixels),
                    SnowtownGpuCrowdStaticField.SPAWN_POINT_COUNT,
                    1);
            this.uploadedSpawnGeneration = spawnGeneration;
        }
    }

    private static int[] destinationPixelsWithSpawnLocations(
            int[] destinations,
            int[] spawnDestinations
    ) {
        if (spawnDestinations.length != destinations.length) {
            throw new IllegalArgumentException("Snowtown GPU 出生点映射长度不匹配");
        }
        int[] combined = new int[destinations.length];
        for (int slot = 0; slot < combined.length; slot++) {
            combined[slot] = destinations[slot] & 0xFFFF
                    | (spawnDestinations[slot] & 0xFFFF) << 16;
        }
        return combined;
    }

    private static DynamicTexture createStaticTexture(String label, int[] pixels, int width, int height) {
        if (pixels.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(label + " 的像素数量与纹理尺寸不一致");
        }
        NativeImage image = new NativeImage(width, height, false);
        DynamicTexture texture = null;
        try {
            // 静态纹理整批写入
            MemoryUtil.memIntBuffer(image.getPointer(), pixels.length).put(pixels);
            texture = new DynamicTexture(() -> label, image);
            texture.setClamp(true);
            texture.setFilter(false, false);
            return texture;
        } catch (RuntimeException | LinkageError failure) {
            if (texture != null) {
                texture.close();
            } else {
                image.close();
            }
            throw failure;
        }
    }

    private static void updateStaticTexture(DynamicTexture texture, int[] pixels) {
        NativeImage image = Objects.requireNonNull(
                texture.getPixels(),
                "Snowtown GPU 动态纹理已释放");
        if (pixels.length != Math.multiplyExact(image.getWidth(), image.getHeight())) {
            throw new IllegalArgumentException("Snowtown GPU 动态纹理更新尺寸不匹配");
        }
        MemoryUtil.memIntBuffer(image.getPointer(), pixels.length).put(pixels);
        texture.upload();
    }

    private void releaseStaticFieldTextures() {
        if (this.staticFieldTexture != null) {
            this.staticFieldTexture.close();
            this.staticFieldTexture = null;
        }
        if (this.lightFieldTexture != null) {
            this.lightFieldTexture.close();
            this.lightFieldTexture = null;
        }
        if (this.flowFieldTexture != null) {
            this.flowFieldTexture.close();
            this.flowFieldTexture = null;
        }
        if (this.destinationDataTexture != null) {
            this.destinationDataTexture.close();
            this.destinationDataTexture = null;
        }
        this.uploadedGeometryGeneration = Integer.MIN_VALUE;
        this.uploadedLightGeneration = Integer.MIN_VALUE;
        this.uploadedNavigationGeneration = Integer.MIN_VALUE;
        this.uploadedSpawnGeneration = Integer.MIN_VALUE;
    }

}
