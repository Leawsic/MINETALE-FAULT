package cn.jehorstudio.minetale.lib.client.particle.gpu;

import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

// 领域粒子效果的窄接口；GpuParticleSystem 独占 pipeline、GPU 资源与渲染事件。
public interface ParticleAction {
    // 定义在动作生命周期内必须稳定。
    ParticleDefinition definition();

    // 仅从停止态进入运行态时调用。
    boolean start(Minecraft minecraft);

    // stop 必须幂等。
    void stop();

    boolean isRunning();

    default void tick(Minecraft minecraft) {
    }

    // 返回冻结帧；null 表示本帧不提交渲染。
    @Nullable
    ParticleFrame extractFrame(ClientLevel level, Camera camera);
}
