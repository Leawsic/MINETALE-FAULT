package cn.jehorstudio.minetale.lib;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.resources.ResourceLocation;

public enum ObjModels {
    TEST(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "models/mod/test.obj"),
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "textures/mod/test.png")
    ),
    SOUL(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "models/mod/soul.obj"),
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "textures/mod/soul.png")
    ),
    BULLET1(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "models/mod/bullet1.obj"),
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "textures/mod/bullet1.png")
    )
    ;

    private final ResourceLocation modelPath;
    private final ResourceLocation texturePath;
    ObjModels(
            ResourceLocation modelPath,
            ResourceLocation texturePath
    ){
        this.modelPath=modelPath;
        this.texturePath=texturePath;
    }

    public ResourceLocation getModelPath() {
        return modelPath;
    }

    public ResourceLocation getTexturePath() {
        return texturePath;
    }
}
