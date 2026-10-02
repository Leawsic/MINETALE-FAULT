package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.entity.monsternpc.client.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MappableRingBuffer;
import java.nio.ByteBuffer;

// 集中写入双缓冲模拟与拾取 pass 共用的 std140 参数布局。
final class SnowtownGpuCrowdUniforms implements AutoCloseable {
    static final float EXTENT_X = SnowtownGpuCrowdStaticField.HALF_SIZE;
    static final float EXTENT_Z = SnowtownGpuCrowdStaticField.HALF_SIZE;
    // 纹理编码上限为该速度的 1.25 倍，为长腿外观保留步速余量。
    static final float WALK_SPEED = 2.0F;
    // 只用于末端投影；动画占地已单独包含 0.05 格安全边。
    static final float HARD_COLLISION_MARGIN = 0.03F;
    // TTC 将其视为可压缩社交间距
    static final float PERSONAL_SPACE = 0.34F;
    static final float COLLISION_HORIZON_SECONDS = 2.5F;
    static final float MAX_ACCELERATION = 3.2F;
    private static final int BUFFER_USAGE = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE;
    private static final int SIMULATION_SIZE = new Std140SizeCalculator()
            .putVec4().putVec4().putVec4().putVec4().putVec4().putVec4().get();
    private static final int INTERACTION_SIZE = interactionUniformSize();

    private final MappableRingBuffer simulation = new MappableRingBuffer(
            () -> "Snowtown GPU crowd simulation uniforms", BUFFER_USAGE, SIMULATION_SIZE);
    private final MappableRingBuffer interaction = new MappableRingBuffer(
            () -> "Snowtown GPU crowd interaction uniforms", BUFFER_USAGE, INTERACTION_SIZE);
    private final int stateSize;
    private final int stateOriginX;
    private final int stateOriginY;

    SnowtownGpuCrowdUniforms(int stateSize, int stateOriginX, int stateOriginY) {
        if (stateSize <= 0) {
            throw new IllegalArgumentException("Snowtown GPU 状态纹理尺寸必须大于零");
        }
        if (stateOriginX < 0 || stateOriginY < 0) {
            throw new IllegalArgumentException("Snowtown GPU 状态页原点不能为负数");
        }
        this.stateSize = stateSize;
        this.stateOriginX = stateOriginX;
        this.stateOriginY = stateOriginY;
    }

    void uploadSimulation(
            float deltaSeconds,
            boolean reset,
            int agentCount,
            float elapsedSeconds,
            int walkableCount
    ) {
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.simulation.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            putVec4(data, 0, deltaSeconds, reset ? 1.0F : 0.0F, agentCount, this.stateSize);
            putVec4(data, 16, EXTENT_X, EXTENT_Z, WALK_SPEED, HARD_COLLISION_MARGIN);
            putVec4(data, 32, elapsedSeconds, PERSONAL_SPACE,
                    COLLISION_HORIZON_SECONDS, MAX_ACCELERATION);
            putVec4(data, 48,
                    SnowtownGpuCrowdStaticField.SIZE,
                    SnowtownGpuCrowdStaticField.HEIGHT_RANGE,
                    SnowtownGpuCrowdStaticField.MAX_CLEARANCE,
                    walkableCount);
            putVec4(data, 64, 0.0F, 0.0F, 0.0F, 0.0F);
            putVec4(data, 80, this.stateOriginX, this.stateOriginY, 0.0F, 0.0F);
        }
    }

    void uploadMigration(
            int agentCount,
            float elapsedSeconds,
            int walkableCount,
            float rebaseX,
            float rebaseZ
    ) {
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.simulation.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            putVec4(data, 0, 0.0F, 0.0F, agentCount, this.stateSize);
            putVec4(data, 16, EXTENT_X, EXTENT_Z, WALK_SPEED, HARD_COLLISION_MARGIN);
            putVec4(data, 32, elapsedSeconds, PERSONAL_SPACE,
                    COLLISION_HORIZON_SECONDS, MAX_ACCELERATION);
            putVec4(data, 48,
                    SnowtownGpuCrowdStaticField.SIZE,
                    SnowtownGpuCrowdStaticField.HEIGHT_RANGE,
                    SnowtownGpuCrowdStaticField.MAX_CLEARANCE,
                    walkableCount);
            putVec4(data, 64, rebaseX, rebaseZ, 1.0F, 0.0F);
            putVec4(data, 80, this.stateOriginX, this.stateOriginY, 0.0F, 0.0F);
        }
    }

    void uploadPick(
            SnowtownCrowdClient.FrameState frame,
            float interpolation,
            int candidateToken,
            float interactionReach
    ) {
        if (candidateToken <= 0 || candidateToken > 255) {
            throw new IllegalArgumentException("Snowtown GPU 拾取候选编号越界");
        }
        if (!(interactionReach > 0.0F) || !Float.isFinite(interactionReach)) {
            throw new IllegalArgumentException("Snowtown GPU 拾取距离无效");
        }
        try (var mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(this.interaction.currentBuffer(), false, true)) {
            ByteBuffer data = mapped.data();
            putVec4(data, 0, frame.anchorX(), frame.anchorY(), frame.anchorZ(), interpolation);
            putVec4(data, 16, frame.elapsedSeconds(), frame.visibleAgentCount(), this.stateSize, EXTENT_X);
            putVec4(data, 32, EXTENT_Z, WALK_SPEED, 0.0F, candidateToken);
            putVec4(data, 48,
                    SnowtownGpuCrowdStaticField.SIZE,
                    SnowtownGpuCrowdStaticField.HEIGHT_RANGE,
                    SnowtownGpuCrowdStaticField.MAX_CLEARANCE,
                    interactionReach);
            putVec4(data, 64, this.stateOriginX, this.stateOriginY, 0.0F, 0.0F);
        }
    }

    void bindSimulation(RenderPass pass) {
        pass.setUniform("CrowdSimulation", this.simulation.currentBuffer());
    }

    void bindInteraction(RenderPass pass) {
        pass.setUniform("CrowdInteraction", this.interaction.currentBuffer());
    }

    void rotate() {
        this.simulation.rotate();
        this.interaction.rotate();
    }

    void rotateInteraction() {
        this.interaction.rotate();
    }

    @Override
    public void close() {
        this.simulation.close();
        this.interaction.close();
    }

    private static void putVec4(ByteBuffer data, int offset, float x, float y, float z, float w) {
        data.putFloat(offset, x);
        data.putFloat(offset + 4, y);
        data.putFloat(offset + 8, z);
        data.putFloat(offset + 12, w);
    }

    private static int interactionUniformSize() {
        return new Std140SizeCalculator()
                .putVec4().putVec4().putVec4().putVec4().putVec4()
                .get();
    }
}
