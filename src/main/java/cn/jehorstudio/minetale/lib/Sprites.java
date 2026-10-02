package cn.jehorstudio.minetale.lib;

import cn.jehorstudio.minetale.MineTale;
import net.minecraft.resources.ResourceLocation;

public enum Sprites {

    TEST(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "textures/gui/battle/test.png"
            ),
            8,
            8
    ),
    SOUL_HEART(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "textures/gui/battle/heart.png"
            ),
            16,
            16
    ),
    BULLET1(
            ResourceLocation.fromNamespaceAndPath(MineTale.MODID,
                    "textures/gui/battle/bullet1.png"
            ),
            7,
            7
    )
    ;

    private final ResourceLocation path;
    private final int width;
    private final int height;

    Sprites(ResourceLocation path, int width, int height) {
        this.path=path;
        this.width=width;
        this.height=height;
    }

    public ResourceLocation getPath(){
        return path;
    }
    public int getWidth() {
        return width;
    }
    public int getHeight() {
        return height;
    }
}
