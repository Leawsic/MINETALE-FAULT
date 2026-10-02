package cn.jehorstudio.minetale.magic.skill;

import cn.jehorstudio.minetale.magic.MagicConfig;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.Set;

// Attachment 的持久化根；仅 MagicCasting 可修改领域状态，网络只发送只读快照。
final class MagicState {
    static final Codec<MagicState> CODEC = CompoundTag.CODEC.xmap(MagicState::read, MagicState::write);
    double maximum = MagicConfig.MAX_MANA;
    double mana = maximum;
    double regeneration = MagicConfig.REGEN;
    double costMultiplier = MagicConfig.COST_MULTIPLIER;
    final Set<ResourceLocation> schools = new LinkedHashSet<>();
    final Set<ResourceLocation> skills = new LinkedHashSet<>();
    ResourceLocation selected;

    private CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("maximum", maximum);
        tag.putDouble("mana", mana);
        tag.putDouble("regeneration", regeneration);
        tag.putDouble("cost_multiplier", costMultiplier);
        tag.put("schools", ids(schools));
        tag.put("skills", ids(skills));
        if (selected != null) tag.putString("selected", selected.toString());
        return tag;
    }

    private static MagicState read(CompoundTag tag) {
        MagicState state = new MagicState();
        state.maximum = bounded(tag.getDoubleOr("maximum", state.maximum), state.maximum, 1_000_000);
        state.mana = bounded(tag.getDoubleOr("mana", state.maximum), state.maximum, state.maximum);
        state.regeneration = bounded(tag.getDoubleOr("regeneration", state.regeneration), state.regeneration, 100_000);
        state.costMultiplier = bounded(tag.getDoubleOr("cost_multiplier", state.costMultiplier), state.costMultiplier, 1000);
        readIds(tag.getListOrEmpty("schools"), state.schools);
        readIds(tag.getListOrEmpty("skills"), state.skills);
        state.selected = ResourceLocation.tryParse(tag.getStringOr("selected", ""));
        if (!state.skills.contains(state.selected)) state.selected = null;
        return state;
    }

    private static double bounded(double value, double fallback, double maximum) {
        return Double.isFinite(value) ? Math.clamp(value, 0, maximum) : fallback;
    }

    private static ListTag ids(Set<ResourceLocation> values) {
        ListTag result = new ListTag();
        values.forEach(id -> result.add(StringTag.valueOf(id.toString())));
        return result;
    }

    private static void readIds(ListTag list, Set<ResourceLocation> result) {
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getStringOr(i, ""));
            if (id != null) result.add(id);
        }
    }
}
