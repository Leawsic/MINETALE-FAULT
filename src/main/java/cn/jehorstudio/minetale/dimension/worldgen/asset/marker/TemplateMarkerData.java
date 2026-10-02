package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public record TemplateMarkerData(
        int schema,
        MarkerKind kind,
        ResourceLocation id,
        List<ResourceLocation> accepts,
        ConnectMode connectMode,
        String group,
        String role,
        String finalState,
        int priority,
        List<ResourceLocation> tags,
        String payload
) {
    public static final int CURRENT_SCHEMA = 1;
    public static final ResourceLocation DEFAULT_ID = ResourceLocation.fromNamespaceAndPath("minetale", "marker");
    public static final String DEFAULT_FINAL_STATE = "minecraft:air";
    public static final String DEFAULT_PAYLOAD = "{}";

    public static final String KEY_SCHEMA = "schema";
    public static final String KEY_KIND = "kind";
    public static final String KEY_ID = "marker_id";
    public static final String KEY_ACCEPTS = "accepts";
    public static final String KEY_CONNECT_MODE = "connect_mode";
    public static final String KEY_GROUP = "group";
    public static final String KEY_ROLE = "role";
    public static final String KEY_FINAL_STATE = "final_state";
    public static final String KEY_PRIORITY = "priority";
    public static final String KEY_TAGS = "tags";
    public static final String KEY_PAYLOAD = "payload";

    public TemplateMarkerData {
        kind = kind == null ? MarkerKind.CONNECTOR : kind;
        id = id == null ? DEFAULT_ID : id;
        accepts = List.copyOf(accepts == null ? List.of() : accepts);
        connectMode = connectMode == null ? ConnectMode.ADJACENT : connectMode;
        group = group == null ? "" : group;
        role = role == null ? "" : role;
        finalState = finalState == null || finalState.isBlank() ? DEFAULT_FINAL_STATE : finalState;
        tags = List.copyOf(tags == null ? List.of() : tags);
        payload = payload == null || payload.isBlank() ? DEFAULT_PAYLOAD : payload;
    }

    public static TemplateMarkerData defaults() {
        return new TemplateMarkerData(
                CURRENT_SCHEMA,
                MarkerKind.CONNECTOR,
                DEFAULT_ID,
                List.of(),
                ConnectMode.ADJACENT,
                "",
                "",
                DEFAULT_FINAL_STATE,
                0,
                List.of(),
                DEFAULT_PAYLOAD
        );
    }

    public TemplateMarkerData copy() {
        return new TemplateMarkerData(
                this.schema,
                this.kind,
                this.id,
                this.accepts,
                this.connectMode,
                this.group,
                this.role,
                this.finalState,
                this.priority,
                this.tags,
                this.payload
        );
    }

    public TemplateMarkerData withKind(MarkerKind kind) {
        return new TemplateMarkerData(this.schema, kind, this.id, this.accepts, this.connectMode, this.group, this.role, this.finalState, this.priority, this.tags, this.payload);
    }

    public TemplateMarkerData withId(ResourceLocation id) {
        return new TemplateMarkerData(this.schema, this.kind, id, this.accepts, this.connectMode, this.group, this.role, this.finalState, this.priority, this.tags, this.payload);
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (this.schema < 1) {
            errors.add("schema must be >= 1");
        }
        if (this.kind == null) {
            errors.add("kind is required");
        }
        if (this.id == null) {
            errors.add("id is required");
        }
        if (this.accepts.stream().anyMatch(Objects::isNull)) {
            errors.add("accepts contains null entry");
        }
        if (this.finalState == null || this.finalState.isBlank()) {
            errors.add("final_state must not be empty");
        }
        if (this.tags.stream().anyMatch(Objects::isNull)) {
            errors.add("tags contains null entry");
        }
        if (this.payload == null || this.payload.isBlank()) {
            errors.add("payload must not be empty");
        } else {
            try {
                JsonParser.parseString(this.payload);
            } catch (JsonSyntaxException ex) {
                errors.add("payload must be valid JSON: " + ex.getMessage());
            }
        }
        return errors;
    }

    public void write(ValueOutput output) {
        output.putInt(KEY_SCHEMA, this.schema);
        output.putString(KEY_KIND, this.kind.getSerializedName());
        output.store(KEY_ID, ResourceLocation.CODEC, this.id);
        ValueOutput.TypedOutputList<ResourceLocation> acceptsOutput = output.list(KEY_ACCEPTS, ResourceLocation.CODEC);
        this.accepts.forEach(acceptsOutput::add);
        output.putString(KEY_CONNECT_MODE, this.connectMode.getSerializedName());
        output.putString(KEY_GROUP, this.group);
        output.putString(KEY_ROLE, this.role);
        output.putString(KEY_FINAL_STATE, this.finalState);
        output.putInt(KEY_PRIORITY, this.priority);
        ValueOutput.TypedOutputList<ResourceLocation> tagsOutput = output.list(KEY_TAGS, ResourceLocation.CODEC);
        this.tags.forEach(tagsOutput::add);
        output.putString(KEY_PAYLOAD, this.payload);
    }

    public static TemplateMarkerData read(ValueInput input) {
        int schema = input.getIntOr(KEY_SCHEMA, CURRENT_SCHEMA);
        MarkerKind kind = MarkerKind.byName(input.getStringOr(KEY_KIND, MarkerKind.CONNECTOR.getSerializedName()));
        ResourceLocation id = input.read(KEY_ID, ResourceLocation.CODEC).orElse(DEFAULT_ID);
        List<ResourceLocation> accepts = input.listOrEmpty(KEY_ACCEPTS, ResourceLocation.CODEC).stream().toList();
        ConnectMode connectMode = ConnectMode.readLegacy(input.getStringOr(KEY_CONNECT_MODE, ""));
        String group = input.getStringOr(KEY_GROUP, "");
        String role = input.getStringOr(KEY_ROLE, "");
        String finalState = input.getStringOr(KEY_FINAL_STATE, DEFAULT_FINAL_STATE);
        int priority = input.getIntOr(KEY_PRIORITY, 0);
        List<ResourceLocation> tags = input.listOrEmpty(KEY_TAGS, ResourceLocation.CODEC).stream().toList();
        String payload = input.getStringOr(KEY_PAYLOAD, DEFAULT_PAYLOAD);
        return new TemplateMarkerData(schema, kind, id, accepts, connectMode, group, role, finalState, priority, tags, payload);
    }

    public void writeToBuffer(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(this.schema);
        buf.writeUtf(this.kind.getSerializedName());
        buf.writeResourceLocation(this.id);
        writeResourceList(buf, this.accepts);
        buf.writeUtf(this.connectMode.getSerializedName());
        buf.writeUtf(this.group);
        buf.writeUtf(this.role);
        buf.writeUtf(this.finalState);
        buf.writeVarInt(this.priority);
        writeResourceList(buf, this.tags);
        buf.writeUtf(this.payload);
    }

    public static TemplateMarkerData readFromBuffer(RegistryFriendlyByteBuf buf) {
        int schema = buf.readVarInt();
        MarkerKind kind = MarkerKind.byName(buf.readUtf());
        ResourceLocation id = buf.readResourceLocation();
        List<ResourceLocation> accepts = readResourceList(buf);
        ConnectMode connectMode = ConnectMode.byName(buf.readUtf());
        String group = buf.readUtf();
        String role = buf.readUtf();
        String finalState = buf.readUtf();
        int priority = buf.readVarInt();
        List<ResourceLocation> tags = readResourceList(buf);
        String payload = buf.readUtf();
        return new TemplateMarkerData(schema, kind, id, accepts, connectMode, group, role, finalState, priority, tags, payload);
    }

    private static void writeResourceList(RegistryFriendlyByteBuf buf, List<ResourceLocation> values) {
        buf.writeVarInt(values.size());
        values.forEach(buf::writeResourceLocation);
    }

    private static List<ResourceLocation> readResourceList(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<ResourceLocation> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buf.readResourceLocation());
        }
        return values;
    }
}
