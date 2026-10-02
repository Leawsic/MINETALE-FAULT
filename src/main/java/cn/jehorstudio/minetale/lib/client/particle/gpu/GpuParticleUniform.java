package cn.jehorstudio.minetale.lib.client.particle.gpu;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import net.minecraft.world.phys.Vec3;

// 将 ParticleFrame 编码为全部粒子 vertex shader 共用的固定 std140 ABI。
final class GpuParticleUniform {
    static final String NAME = "ParticleUniform";
    static final int FRAME_VECTOR_COUNT = 7;
    static final int BUFFER_SIZE = uniformSize();

    private GpuParticleUniform() {
    }

    static void write(
            Std140Builder uniform,
            ParticleDefinition definition,
            ParticleFrame frame,
            Vec3 cameraPosition
    ) {
        Vec3 relativeAnchor = frame.anchor().subtract(cameraPosition);
        put(uniform, relativeAnchor, frame.motionTimeSeconds());
        put(uniform, frame.axis(), frame.radialScale());
        put(uniform, frame.radialRight(), frame.axialScale());
        put(uniform, frame.radialUp(), frame.intensity());
        put(uniform, frame.billboardRight(), frame.visibility());
        put(uniform, frame.billboardUp(), frame.particleSizeScale());
        uniform.putVec4(definition.alphaCutout(), 0.0F, 0.0F, 0.0F);
    }

    private static void put(Std140Builder uniform, Vec3 vector, float fourth) {
        uniform.putVec4((float) vector.x, (float) vector.y, (float) vector.z, fourth);
    }

    private static int uniformSize() {
        Std140SizeCalculator calculator = new Std140SizeCalculator();
        for (int index = 0; index < FRAME_VECTOR_COUNT; index++) {
            calculator.putVec4();
        }
        return calculator.get();
    }
}
