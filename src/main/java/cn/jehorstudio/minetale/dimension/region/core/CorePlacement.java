package cn.jehorstudio.minetale.dimension.region.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;

/** 服务端放置的 Core 权威坐标；原点取方块整坐标，保证碰撞格与世界方块格对齐。 */
public record CorePlacement(ResourceLocation scene, int x, int y, int z) {
    public static final Codec<CorePlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("scene").forGetter(CorePlacement::scene),
            Codec.INT.fieldOf("x").forGetter(CorePlacement::x),
            Codec.INT.fieldOf("y").forGetter(CorePlacement::y),
            Codec.INT.fieldOf("z").forGetter(CorePlacement::z)
    ).apply(instance, CorePlacement::new));

    public static CorePlacement snap(ResourceLocation scene, double x, double y, double z) {
        return new CorePlacement(scene, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public String toString() {
        return scene + " @ (" + x + ", " + y + ", " + z + ")";
    }
}
