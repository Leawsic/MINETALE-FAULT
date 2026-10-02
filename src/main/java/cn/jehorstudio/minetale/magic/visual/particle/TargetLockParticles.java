package cn.jehorstudio.minetale.magic.visual.particle;

import cn.jehorstudio.minetale.lib.client.particle.gpu.GpuParticleSystem;
import cn.jehorstudio.minetale.lib.client.particle.gpu.ParticleAction;
import cn.jehorstudio.minetale.lib.client.particle.gpu.ParticleDefinition;
import cn.jehorstudio.minetale.lib.client.particle.gpu.ParticleFrame;
import cn.jehorstudio.minetale.magic.Magic;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class TargetLockParticles implements ParticleAction {
    private static final ResourceLocation ID = Magic.id("target_lock");
    public static final TargetLockParticles INSTANCE = new TargetLockParticles();
    private static final ParticleDefinition DEFINITION = new ParticleDefinition(ID,
            256,
            ResourceLocation.withDefaultNamespace("textures/particle/spark_0.png"), Magic.id("core/magic/target_lock"), 0.02F, null);
    private boolean running;
    private Entity target;

    private TargetLockParticles() {}

    /** 在客户端 Tick 更新跟随对象；null 停止效果 */
    public static void track(Entity target) {
        INSTANCE.target = target;
        if (target == null) GpuParticleSystem.stop(ID);
        else GpuParticleSystem.start(ID);
    }

    @Override public ParticleDefinition definition() { return DEFINITION; }
    @Override public boolean start(Minecraft mc) { return running = target != null; }
    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }

    @Override public ParticleFrame extractFrame(ClientLevel level, Camera camera) {
        if (!running || target == null || target.level() != level || !target.isAlive() || !camera.isInitialized()) return null;
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 center = target.getPosition(partial).add(0, target.getBbHeight() * 0.5, 0);
        float radius = Math.max(0.6F, target.getBbWidth() * 0.5F + 0.25F);
        float height = target.getBbHeight();
        return new ParticleFrame(center, new Vec3(0, 1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1),
                new Vec3(camera.getLeftVector()).scale(-1), new Vec3(camera.getUpVector()),
                (level.getGameTime() % 24000 + partial) / 20F, radius, height, 0.045F, 1, 1,
                new ParticleFrame.Bounds(-height, height, radius + 0.2F));
    }
}
