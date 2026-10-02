package cn.jehorstudio.minetale.content.entity.flowey;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animatable.processing.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public final class FloweyModel extends DefaultedEntityGeoModel<Flowey> {
    private static final ResourceLocation ASSET =
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID, "flowey");

    public FloweyModel() {
        super(ASSET);
    }

    @Override
    public void setCustomAnimations(AnimationState<Flowey> animationState) {
        if (!(animationState.renderState() instanceof FloweyRenderState state)) {
            return;
        }

        GeoBone stem = getAnimationProcessor().getBone("stem");
        GeoBone face = getAnimationProcessor().getBone("face");
        if (stem != null) {
            stem.setRotY(-state.stemYaw * Mth.DEG_TO_RAD);
        }
        if (face != null) {
            face.setRotX(state.faceX * Mth.DEG_TO_RAD);
            face.setRotY(-state.faceYaw * Mth.DEG_TO_RAD);
            face.setRotZ(0.0F);
        }
        GeoBone root = getAnimationProcessor().getBone("root");
        root.setScaleX(0.6F);
        root.setScaleY(0.6F);
        root.setScaleZ(0.6F);
    }
}
