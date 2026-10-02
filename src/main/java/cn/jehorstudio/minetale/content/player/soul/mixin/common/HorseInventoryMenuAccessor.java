package cn.jehorstudio.minetale.content.player.soul.mixin.common;

import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.HorseInventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 暴露驴、骡与羊驼菜单背后的移动容器实体。
@Mixin(HorseInventoryMenu.class)
public interface HorseInventoryMenuAccessor {
    @Accessor("horse")
    AbstractHorse minetale$getHorse();
}
