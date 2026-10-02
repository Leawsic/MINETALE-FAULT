package cn.jehorstudio.minetale.lib.client.particle.gpu;

import cn.jehorstudio.minetale.lib.client.render.WorldModelRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

// 使用原版 RenderType 绘制粒子效果可选的中心锚点模型。
final class ParticleAnchorRenderer {
    private final WorldModelRenderer modelRenderer;

    ParticleAnchorRenderer(ParticleDefinition.AnchorModel appearance) {
        ParticleDefinition.Color tint = appearance.tint();
        this.modelRenderer = new WorldModelRenderer(
                appearance.model(),
                appearance.scale(),
                tint.red(),
                tint.green(),
                tint.blue(),
                appearance.opacity()
        );
    }

    void render(RenderLevelStageEvent.AfterEntities event, ParticleFrame frame) {
        this.modelRenderer.render(event, frame.anchor(), modelOrientation(frame));
    }

    // soul.obj 轴约定：-Y 对齐帧轴，X 对齐径向上方，Z 对齐径向左右。
    static Matrix4f modelOrientation(ParticleFrame frame) {
        Vec3 axis = frame.axis();
        Vec3 right = frame.radialRight();
        Vec3 up = frame.radialUp();
        return new Matrix4f(
                (float) up.x, (float) up.y, (float) up.z, 0.0F,
                (float) -axis.x, (float) -axis.y, (float) -axis.z, 0.0F,
                (float) -right.x, (float) -right.y, (float) -right.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F
        );
    }
}
