package cn.jehorstudio.minetale.dimension.ebott.transition.client.particle;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.transition.client.TransitionClient;
import cn.jehorstudio.minetale.lib.client.particle.gpu.ParticleAction;
import cn.jehorstudio.minetale.lib.client.particle.gpu.ParticleDefinition;
import cn.jehorstudio.minetale.lib.client.particle.gpu.ParticleFrame;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

// 将同一结界采样为环流、盘面旋涡和分段粒子弧三层视觉结构。
public final class BarrierVortexParticleAction implements ParticleAction {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(
            MineTale.MODID,
            "barrier_vortex"
    );
    static final int PARTICLE_COUNT = 16_384;

    private static final float PARTICLE_PLANE_OFFSET = 1.0F / 64.0F;
    private static final float RADIUS_INSET = 2.0F;
    private static final float MIN_EFFECT_RADIUS = 1.0F;
    private static final float MIN_PARTICLE_SIZE = 0.055F;
    private static final float MAX_PARTICLE_SIZE = 0.095F;
    private static final float MAX_LIFT = 50.0F;
    private static final float MIN_HALO_EXTENT = 2.8F;
    private static final float NEAR_DISTANCE = 2.0F;
    private static final float INTENSE_DISTANCE = 28.0F;
    private static final float FADE_START_DISTANCE = 56.0F;
    private static final float FADE_END_DISTANCE = 72.0F;
    private static final double MAX_MOTION_DELTA_SECONDS = 0.10;
    private static final double FAR_MOTION_RATE = 0.52;
    private static final double NEAR_MOTION_RATE = 0.82;
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 WORLD_RIGHT = new Vec3(1.0, 0.0, 0.0);
    private static final Vec3 WORLD_FORWARD = new Vec3(0.0, 0.0, 1.0);
    private static final ResourceLocation PARTICLE_TEXTURE = ResourceLocation.withDefaultNamespace(
            "textures/particle/spark_0.png"
    );
    private static final ParticleDefinition DEFINITION = new ParticleDefinition(
            ID,
            PARTICLE_COUNT,
            PARTICLE_TEXTURE,
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "core/particle/barrier_vortex"),
            0.04F,
            null
    );
    public static final BarrierVortexParticleAction INSTANCE = new BarrierVortexParticleAction();

    private boolean running;
    private long lastMotionUpdateNanos;
    private double motionTime;

    private BarrierVortexParticleAction() {
    }

    @Override
    public ParticleDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean start(Minecraft minecraft) {
        if (minecraft.level == null
                || !Level.OVERWORLD.equals(minecraft.level.dimension())
                || TransitionClient.INSTANCE.renderableBarrier() == null) {
            return false;
        }
        if (!this.running) {
            this.lastMotionUpdateNanos = System.nanoTime();
            this.motionTime = 0.0;
            this.running = true;
        }
        return true;
    }

    @Override
    public void stop() {
        this.running = false;
        this.lastMotionUpdateNanos = 0L;
        this.motionTime = 0.0;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public ParticleFrame extractFrame(ClientLevel level, Camera camera) {
        if (!this.running || !Level.OVERWORLD.equals(level.dimension()) || !camera.isInitialized()) {
            return null;
        }
        TransitionClient.RenderableBarrier barrier = TransitionClient.INSTANCE.renderableBarrier();
        if (barrier == null || barrier.targetSide()) {
            return null;
        }

        Vec3 cameraPosition = camera.getPosition();
        if (cameraPosition.y < barrier.seamY()) {
            return null;
        }

        Vec3 planeCenter = new Vec3(
                barrier.blockGridCenterX() + barrier.apertureCenterOffsetX(),
                barrier.seamY(),
                barrier.blockGridCenterZ() + barrier.apertureCenterOffsetZ()
        );
        float distance = distanceToDisk(cameraPosition, planeCenter, (float) barrier.radius());
        float intensity = 1.0F - smoothstep(NEAR_DISTANCE, INTENSE_DISTANCE, distance);
        float motionTime = advanceMotion(intensity);
        float visibility = 1.0F - smoothstep(FADE_START_DISTANCE, FADE_END_DISTANCE, distance);
        if (visibility <= 0.001F) {
            return null;
        }

        float radius = Math.max(MIN_EFFECT_RADIUS, (float) barrier.radius() - RADIUS_INSET);
        float particleSize = Math.clamp(
                radius * 0.006F,
                MIN_PARTICLE_SIZE,
                MAX_PARTICLE_SIZE
        );
        float radialExtent = radius + Math.max(MIN_HALO_EXTENT, radius * 0.30F)
                + particleSize * 2.0F;
        return new ParticleFrame(
                planeCenter.add(WORLD_UP.scale(PARTICLE_PLANE_OFFSET)),
                WORLD_UP,
                WORLD_RIGHT,
                WORLD_FORWARD,
                vector(camera.getLeftVector()).scale(-1.0).normalize(),
                vector(camera.getUpVector()).normalize(),
                motionTime,
                radius,
                MAX_LIFT,
                particleSize,
                intensity,
                visibility,
                new ParticleFrame.Bounds(
                        -particleSize * 4.0F,
                        MAX_LIFT + particleSize * 4.0F,
                        radialExtent
                )
        );
    }

    // 距离只影响后续相位速度，位置变化不能重算已经累计的相位。
    private float advanceMotion(float intensity) {
        long now = System.nanoTime();
        double elapsedSeconds = Math.clamp(
                (now - this.lastMotionUpdateNanos) * 1.0E-9,
                0.0,
                MAX_MOTION_DELTA_SECONDS
        );
        this.lastMotionUpdateNanos = now;
        double rate = FAR_MOTION_RATE + (NEAR_MOTION_RATE - FAR_MOTION_RATE) * intensity;
        this.motionTime += elapsedSeconds * rate;
        return (float) this.motionTime;
    }

    private static float distanceToDisk(Vec3 position, Vec3 center, float radius) {
        double dx = position.x - center.x;
        double dz = position.z - center.z;
        double radialOutside = Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - radius);
        return (float) Math.hypot(Math.abs(position.y - center.y), radialOutside);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float progress = Math.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return progress * progress * (3.0F - 2.0F * progress);
    }

    private static Vec3 vector(Vector3f value) {
        return new Vec3(value.x(), value.y(), value.z());
    }
}
