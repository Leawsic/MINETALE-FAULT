package cn.jehorstudio.minetale.magic.data;

import cn.jehorstudio.minetale.magic.Magic;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class MagicTranslations {
    private MagicTranslations() {}

    public static void addZhCn(LanguageProvider p) {
        p.add("minetale.options.camera_shake", "MineTale 镜头震动");
        p.add("minetale.options.camera_shake.tooltip", "调整MINETALE引起的镜头震动。0% 完全关闭。");
        p.add("key.category.minetale.magic", "MineTale 魔法");
        p.add("key.minetale.cast", "施法");
        p.add("key.minetale.lock_target", "锁定目标");
        p.add("entity.minetale.gaster_blaster", "伽斯特冲击炮");
        p.add("skill.minetale.gaster_impact", "伽斯特冲击");
        p.add("school.minetale.gaster_weapons", "Gaster 武器");
        p.add("magic.minetale.result.success", "操作完成");
        p.add("death.attack.minetale.karma_contact", "%1$s 被伽斯特冲击炮轰碎了灵魂");
        p.add("death.attack.minetale.karma_contact.player", "%1$s 被 %2$s 的伽斯特冲击炮轰碎了灵魂");
        p.add("death.attack.minetale.karma_delayed", "%1$s 遭到报应");
    }

    public static void addEnUs(LanguageProvider p) {
        p.add("minetale.options.camera_shake", "MineTale Camera Shake");
        p.add("minetale.options.camera_shake.tooltip", "Adjust camera shake from MINETALE. 0% disables it. ");
        p.add("key.category.minetale.magic", "MineTale Magic");
        p.add("key.minetale.cast", "Cast");
        p.add("key.minetale.lock_target", "Lock Target");
        p.add("entity.minetale.gaster_blaster", "Gaster Blaster");
        p.add("skill.minetale.gaster_impact", "Gaster Impact");
        p.add("school.minetale.gaster_weapons", "Gaster Weapons");
        p.add("magic.minetale.result.success", "Done");
        p.add("death.attack.minetale.karma_contact", "%1$s was blasted apart by a Gaster Blaster");
        p.add("death.attack.minetale.karma_contact.player", "%1$s was blasted apart by %2$s's Gaster Blaster");
        p.add("death.attack.minetale.karma_delayed", "%1$s was claimed by Karma");
    }
}
