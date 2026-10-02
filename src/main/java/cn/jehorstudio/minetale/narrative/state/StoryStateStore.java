package cn.jehorstudio.minetale.narrative.state;

import cn.jehorstudio.minetale.narrative.data.NarrativeCatalog;
import cn.jehorstudio.minetale.narrative.data.NarrativeValidationException;
import cn.jehorstudio.minetale.narrative.NarrativeAttachments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BooleanValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IdValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.IntValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryScope;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryStateDefinition;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StoryValue;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ValueType;

public final class StoryStateStore {
    private static final String TYPE_KEY = "type";
    private static final String VALUE_KEY = "value";

    private StoryStateStore() {
    }

    public static StoryValue get(ServerPlayer player, ResourceLocation stateId) {
        StoryStateDefinition definition = requireDefinition(stateId);
        CompoundTag entry = values(player, definition).getCompoundOrEmpty(stateId.toString());
        StoryValue stored = decode(entry);
        if (stored == null || stored.type() != definition.type()) {
            return definition.defaultValue();
        }
        try {
            definition.validate(stored);
            return stored;
        } catch (NarrativeValidationException ignored) {
            return definition.defaultValue();
        }
    }

    public static void set(ServerPlayer player, ResourceLocation stateId, StoryValue value) {
        StoryStateDefinition definition = requireDefinition(stateId);
        definition.validate(value);
        CompoundTag values = values(player, definition);
        values.put(stateId.toString(), encode(value));
        if (definition.scope() == StoryScope.WORLD) {
            world(player).setDirty();
        }
    }

    public static void add(ServerPlayer player, ResourceLocation stateId, int delta) {
        StoryStateDefinition definition = requireDefinition(stateId);
        if (definition.type() != ValueType.INT) {
            throw new NarrativeValidationException(stateId + " is not an int story state.");
        }
        int current = ((IntValue) get(player, stateId)).value();
        long result = (long) current + delta;
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new NarrativeValidationException(stateId + " add operation overflows 32-bit int.");
        }
        set(player, stateId, new IntValue((int) result));
    }

    private static StoryStateDefinition requireDefinition(ResourceLocation id) {
        StoryStateDefinition definition = NarrativeCatalog.current().storyStates().get(id);
        if (definition == null) {
            throw new NarrativeValidationException("Unknown story state " + id + ".");
        }
        return definition;
    }

    private static CompoundTag values(
            ServerPlayer player,
            StoryStateDefinition definition
    ) {
        if (definition.scope() == StoryScope.PLAYER) {
            return playerStoryValues(player);
        }
        return world(player).values();
    }

    // getCompoundOrEmpty 不写回缺失节点，首次访问玩家状态时必须显式建立根容器。
    public static CompoundTag playerStoryValues(ServerPlayer player) {
        CompoundTag root = player.getData(NarrativeAttachments.PLAYER_DATA.get());
        CompoundTag values = root.getCompoundOrEmpty("story_values");
        if (!root.contains("story_values")) {
            root.put("story_values", values);
        }
        return values;
    }

    private static WorldStorySavedData world(ServerPlayer player) {
        return player.level().getServer().overworld().getDataStorage().computeIfAbsent(WorldStorySavedData.TYPE);
    }

    private static CompoundTag encode(StoryValue value) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TYPE_KEY, switch (value.type()) {
            case BOOLEAN -> "boolean";
            case INT -> "int";
            case ID -> "id";
        });
        switch (value) {
            case BooleanValue bool -> tag.putBoolean(VALUE_KEY, bool.value());
            case IntValue integer -> tag.putInt(VALUE_KEY, integer.value());
            case IdValue identifier -> tag.putString(VALUE_KEY, identifier.value().toString());
        }
        return tag;
    }

    private static StoryValue decode(CompoundTag tag) {
        String type = tag.getStringOr(TYPE_KEY, "");
        return switch (type) {
            case "boolean" -> new BooleanValue(tag.getBooleanOr(VALUE_KEY, false));
            case "int" -> tag.getInt(VALUE_KEY).map(IntValue::new).orElse(null);
            case "id" -> {
                ResourceLocation id = ResourceLocation.tryParse(tag.getStringOr(VALUE_KEY, ""));
                yield id == null ? null : new IdValue(id);
            }
            default -> null;
        };
    }
}
