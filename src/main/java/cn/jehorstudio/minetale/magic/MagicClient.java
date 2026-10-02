package cn.jehorstudio.minetale.magic;

import cn.jehorstudio.minetale.lib.client.particle.gpu.GpuParticleSystem;
import cn.jehorstudio.minetale.magic.skill.client.MagicInput;
import cn.jehorstudio.minetale.magic.spell.gasterblaster.client.GasterBlasterRenderer;
import cn.jehorstudio.minetale.magic.visual.particle.TargetLockParticles;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicBeamRenderer;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicCameraShake;
import cn.jehorstudio.minetale.magic.visual.vfx.MagicScreenDoor;
import net.neoforged.bus.api.IEventBus;

// 仅客户端装配
public final class MagicClient {
    private MagicClient() {}

    public static void register(IEventBus bus) {
        MagicBeamRenderer.register(bus);
        MagicCameraShake.register();
        MagicScreenDoor.register(bus);
        GasterBlasterRenderer.register(bus);
        GpuParticleSystem.register(TargetLockParticles.INSTANCE);
        MagicInput.register(bus);
    }
}
