package cn.jehorstudio.minetale.lib;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.RawAnimation;

// 集中映射 GeckoLib 模型、动画、贴图与默认动画；资源路径省略 GeckoLib 固定前缀。
public enum GeckoModels {
    TEST_BLASTER(
            "entity/test_blaster",
            "entity/test_blaster",
            "textures/entity/test_blaster.png",
            RawAnimation.begin().thenPlay("open_mouth")
    );

    private final ResourceLocation modelResource;
    private final ResourceLocation animationResource;
    private final ResourceLocation textureResource;
    private final RawAnimation defaultAnimation;

    GeckoModels(
            String modelPath,
            String animationPath,
            String texturePath,
            RawAnimation defaultAnimation
    ) {
        this.modelResource = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, modelPath);
        this.animationResource = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, animationPath);
        this.textureResource = ResourceLocation.fromNamespaceAndPath(MineTale.MODID, texturePath);
        this.defaultAnimation = defaultAnimation;
    }

    public ResourceLocation getModelResource() {
        return modelResource;
    }

    public ResourceLocation getAnimationResource() {
        return animationResource;
    }

    public ResourceLocation getTextureResource() {
        return textureResource;
    }

    public RawAnimation getDefaultAnimation() {
        return defaultAnimation;
    }
}
