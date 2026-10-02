package cn.jehorstudio.minetale.magic.skill;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Java 技能注册目录。注册应在模组构造阶段完成，common setup 后只读。 */
public final class MagicCatalog {
    private static final Set<ResourceLocation> SCHOOLS = new LinkedHashSet<>();
    private static final Map<ResourceLocation, Skill> SKILLS = new LinkedHashMap<>();
    private static boolean frozen;

    private MagicCatalog() {}

    /** 注册流派资格，重复 ID 或冻结后注册会抛出异常。 */
    public static synchronized void registerSchool(ResourceLocation id) {
        requireOpen();
        if (!SCHOOLS.add(java.util.Objects.requireNonNull(id))) throw new IllegalArgumentException("重复流派: " + id);
    }

    /** 注册技能；所属流派必须在 common setup 冻结前注册。 */
    public static synchronized void registerSkill(Skill skill) {
        requireOpen();
        if (SKILLS.putIfAbsent(skill.definition().id(), skill) != null) {
            throw new IllegalArgumentException("重复技能: " + skill.definition().id());
        }
    }

    /** 返回不可变 ID 集合。 */
    public static Set<ResourceLocation> schools() { return Set.copyOf(SCHOOLS); }
    /** 返回不可变 ID 集合。 */
    public static Set<ResourceLocation> skills() { return Set.copyOf(SKILLS.keySet()); }
    /** 查找注册技能；未知 ID 返回 null。 */
    public static Skill skill(ResourceLocation id) { return SKILLS.get(id); }

    static void freeze() {
        for (Skill skill : SKILLS.values()) {
            if (!SCHOOLS.contains(skill.definition().school())) {
                throw new IllegalStateException("技能引用未注册流派: " + skill.definition().id());
            }
        }
        frozen = true;
    }

    private static void requireOpen() {
        if (frozen) throw new IllegalStateException("MagicCatalog 已冻结");
    }
}
