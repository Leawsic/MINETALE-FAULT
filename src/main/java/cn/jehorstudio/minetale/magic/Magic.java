package cn.jehorstudio.minetale.magic;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.magic.effect.karma.Karma;
import cn.jehorstudio.minetale.magic.skill.MagicCasting;
import cn.jehorstudio.minetale.magic.spell.gasterblaster.GasterBlaster;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

// 模组装配入口
public final class Magic {
    public static final ResourceLocation GASTER_SCHOOL = id("gaster_weapons");
    public static final ResourceLocation GASTER_IMPACT = id("gaster_impact");
    public static final ResourceLocation GASTER_BLASTER = id("gaster_blaster");

    private Magic() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineTale.MODID, path);
    }

    public static void register(IEventBus bus) {
        MagicCasting.register(bus);
        GasterBlaster.register(bus);
        Karma.register(bus);
    }
}
