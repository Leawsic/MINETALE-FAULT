package cn.jehorstudio.minetale.content.entity.flowey;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public final class FloweyRenderer extends GeoEntityRenderer<Flowey, FloweyRenderState> {
    public FloweyRenderer(EntityRendererProvider.Context context) {
        super(context, new FloweyModel());
        this.shadowRadius = 0.45F;
    }

    @Override
    public FloweyRenderState createRenderState(Flowey animatable, Void relatedObject) {
        return new FloweyRenderState();
    }

    @Override
    public void addRenderData(
            Flowey animatable,
            Void relatedObject,
            FloweyRenderState renderState,
            float partialTick
    ) {
        renderState.faceX = animatable.getFaceX(partialTick);
        renderState.faceYaw = animatable.getFaceYaw(partialTick);
        renderState.stemYaw = animatable.getStemYaw(partialTick);
    }
}
