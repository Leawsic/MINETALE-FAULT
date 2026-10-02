package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class TemplateMarkerScreen extends AbstractContainerScreen<TemplateMarkerMenu> {
    private static final String KEY_KIND = "gui.minetale.template_marker.kind";
    private static final String KEY_FACING = "gui.minetale.template_marker.facing";
    private static final String KEY_VISUAL = "gui.minetale.template_marker.visual";
    private static final String KEY_ID = "gui.minetale.template_marker.id";
    private static final String KEY_ACCEPTS = "gui.minetale.template_marker.accepts";
    private static final String KEY_CHANNEL = "template_marker.field.channel";
    private static final String KEY_ENDPOINT = "template_marker.field.endpoint";
    private static final String KEY_CONNECT_MODE = "template_marker.field.connect_mode";
    private static final String KEY_FINAL_STATE = "gui.minetale.template_marker.final_state";
    private static final String KEY_PRIORITY = "gui.minetale.template_marker.priority";
    private static final String KEY_PAYLOAD = "gui.minetale.template_marker.payload";
    private static final String KEY_POSITION = "gui.minetale.template_marker.position";
    private static final String KEY_FACING_VISUAL = "gui.minetale.template_marker.facing_visual";

    private static final String KEY_SAVE = "gui.minetale.template_marker.save";
    private static final String KEY_CANCEL = "gui.minetale.template_marker.cancel";
    private static final String KEY_RESET_DEFAULTS = "gui.minetale.template_marker.reset_defaults";
    private static final String KEY_VALIDATE = "gui.minetale.template_marker.validate";

    private static final String KEY_PRESET_ROAD_CONNECTOR = "gui.minetale.template_marker.preset.road_connector";
    private static final String KEY_PRESET_BUILDING_ANCHOR = "gui.minetale.template_marker.preset.building_anchor";
    private static final String KEY_STATUS_PRESET_LOADED = "gui.minetale.template_marker.status.preset_loaded";
    private static final String KEY_STATUS_VALID = "gui.minetale.template_marker.status.valid";

    private static final String KEY_ERROR_FINAL_STATE_EMPTY = "gui.minetale.template_marker.error.final_state_empty";
    private static final String KEY_ERROR_PAYLOAD_EMPTY = "gui.minetale.template_marker.error.payload_empty";
    private static final String KEY_ERROR_PRIORITY_INTEGER = "gui.minetale.template_marker.error.priority_integer";
    private static final String KEY_ERROR_RESOURCE_LOCATION = "gui.minetale.template_marker.error.resource_location";
    private static final String KEY_ERROR_RESOURCE_LOCATION_LIST = "gui.minetale.template_marker.error.resource_location_list";

    private static final int PANEL_COLOR = 0xE0101010;
    private static final int BORDER_COLOR = 0xFF6EA8FF;
    private static final int TEXT_COLOR = 0xFFE8F2FF;
    private static final int MUTED_COLOR = 0xFFB8C7D9;
    private static final int ERROR_COLOR = 0xFFFF6060;
    private static final int OK_COLOR = 0xFF70FF90;

    private MarkerKind kind;
    private Direction facing;
    private MarkerVisual visual;
    private ConnectMode connectMode;
    private List<ResourceLocation> hiddenTags = List.of();
    private EditBox idBox;
    private EditBox acceptsBox;
    private EditBox channelBox;
    private EditBox endpointBox;
    private EditBox finalStateBox;
    private EditBox priorityBox;
    private EditBox payloadBox;
    private Button kindButton;
    private Button facingButton;
    private Button visualButton;
    private Button connectModeButton;
    private String status = "";
    private int statusColor = MUTED_COLOR;

    public TemplateMarkerScreen(TemplateMarkerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 440;
        this.imageHeight = 376;
        this.titleLabelX = 14;
        this.titleLabelY = 10;
        TemplateMarkerData data = menu.data();
        this.kind = visibleKind(data.kind());
        this.facing = menu.facing();
        this.visual = menu.visual();
        this.connectMode = data.connectMode();
        this.hiddenTags = data.tags();
    }

    @Override
    protected void init() {
        super.init();
        TemplateMarkerData data = this.menu.data();
        int x = this.leftPos + 14;
        int editX = this.leftPos + 112;
        int y = this.topPos + 58;
        int editWidth = 306;
        int editHeight = 18;

        this.kindButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
            this.kind = nextVisibleKind(this.kind);
            this.updateCycleButtons();
        }).bounds(x, y, 128, 20).build());
        this.facingButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
            this.facing = nextDirection(this.facing);
            this.updateCycleButtons();
        }).bounds(x + 134, y, 128, 20).build());
        this.visualButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
            this.visual = this.visual.next();
            this.updateCycleButtons();
        }).bounds(x + 268, y, 142, 20).build());

        this.idBox = editBox(editX, this.topPos + 88, editWidth, editHeight, Component.translatable(KEY_ID), data.id().toString());
        this.acceptsBox = editBox(editX, this.topPos + 112, editWidth, editHeight, Component.translatable(KEY_ACCEPTS), formatList(data.accepts()));
        this.channelBox = editBox(editX, this.topPos + 136, editWidth, editHeight, Component.translatable(KEY_CHANNEL), data.group());
        this.endpointBox = editBox(editX, this.topPos + 160, editWidth, editHeight, Component.translatable(KEY_ENDPOINT), data.role());
        this.connectModeButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
            this.connectMode = this.connectMode.next();
            this.updateCycleButtons();
        }).bounds(editX, this.topPos + 184, editWidth, 20).build());
        this.finalStateBox = editBox(editX, this.topPos + 208, editWidth, editHeight, Component.translatable(KEY_FINAL_STATE), data.finalState());
        this.priorityBox = editBox(editX, this.topPos + 232, editWidth, editHeight, Component.translatable(KEY_PRIORITY), Integer.toString(data.priority()));
        this.priorityBox.setFilter(value -> value.isEmpty() || value.equals("-") || value.matches("-?\\d+"));
        this.payloadBox = editBox(editX, this.topPos + 256, editWidth, editHeight, Component.translatable(KEY_PAYLOAD), data.payload());
        this.payloadBox.setMaxLength(8192);

        this.addRenderableWidget(Button.builder(Component.translatable(KEY_SAVE), button -> this.save()).bounds(x, this.topPos + 282, 76, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable(KEY_CANCEL), button -> this.onClose()).bounds(x + 82, this.topPos + 282, 76, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable(KEY_RESET_DEFAULTS), button -> this.applyData(TemplateMarkerData.defaults(), this.facing, MarkerVisual.CONNECTOR))
                .bounds(x + 164, this.topPos + 282, 126, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable(KEY_VALIDATE), button -> this.validateCurrent()).bounds(x + 296, this.topPos + 282, 96, 20).build());

        addPresetButton(x, this.topPos + 308, 132, Component.translatable(KEY_PRESET_ROAD_CONNECTOR), TemplateMarkerPresets.roadConnector());
        addPresetButton(x + 138, this.topPos + 308, 132, Component.translatable(KEY_PRESET_BUILDING_ANCHOR), TemplateMarkerPresets.buildingAnchor());

        this.updateCycleButtons();
    }

    private EditBox editBox(int x, int y, int width, int height, Component label, String value) {
        EditBox box = new EditBox(this.font, x, y, width, height, label);
        box.setMaxLength(1024);
        box.setValue(value);
        this.addRenderableWidget(box);
        return box;
    }

    private void addPresetButton(int x, int y, int width, Component label, TemplateMarkerData data) {
        this.addRenderableWidget(Button.builder(label, button -> this.applyData(data, this.facing, visualFor(data.kind()))).bounds(x, y, width, 20).build());
    }

    private void applyData(TemplateMarkerData data, Direction facing, MarkerVisual visual) {
        this.kind = visibleKind(data.kind());
        this.facing = facing;
        this.visual = visual;
        this.connectMode = data.connectMode();
        this.hiddenTags = data.tags();
        this.idBox.setValue(data.id().toString());
        this.acceptsBox.setValue(formatList(data.accepts()));
        this.channelBox.setValue(data.group());
        this.endpointBox.setValue(data.role());
        this.finalStateBox.setValue(data.finalState());
        this.priorityBox.setValue(Integer.toString(data.priority()));
        this.payloadBox.setValue(data.payload());
        this.status = Component.translatable(KEY_STATUS_PRESET_LOADED).getString();
        this.statusColor = MUTED_COLOR;
        this.updateCycleButtons();
    }

    private void updateCycleButtons() {
        if (this.kindButton != null) {
            this.kindButton.setMessage(Component.translatable(KEY_KIND, Component.translatable(markerKindKey(this.kind))));
        }
        if (this.facingButton != null) {
            this.facingButton.setMessage(Component.translatable(KEY_FACING, this.facing.getSerializedName()));
        }
        if (this.visualButton != null) {
            this.visualButton.setMessage(Component.translatable(KEY_VISUAL, this.visual.getSerializedName()));
        }
        if (this.connectModeButton != null) {
            this.connectModeButton.setMessage(Component.translatable(KEY_CONNECT_MODE)
                    .append(": ")
                    .append(Component.translatable(connectModeKey(this.connectMode))));
        }
    }

    private void validateCurrent() {
        BuildResult result = this.buildDataFromForm();
        if (result.errors().isEmpty()) {
            this.status = Component.translatable(KEY_STATUS_VALID).getString();
            this.statusColor = OK_COLOR;
        } else {
            this.status = String.join("; ", result.errors());
            this.statusColor = ERROR_COLOR;
        }
    }

    private void save() {
        BuildResult result = this.buildDataFromForm();
        if (!result.errors().isEmpty()) {
            this.status = String.join("; ", result.errors());
            this.statusColor = ERROR_COLOR;
            return;
        }
        ClientPacketDistributor.sendToServer(new TemplateMarkerUpdatePayload(this.menu.pos(), result.data(), this.facing, this.visual));
        this.onClose();
    }

    private BuildResult buildDataFromForm() {
        List<String> errors = new ArrayList<>();
        ResourceLocation id = parseResource(this.idBox.getValue(), KEY_ID, errors);
        List<ResourceLocation> accepts = parseResourceList(this.acceptsBox.getValue(), KEY_ACCEPTS, errors);
        String finalState = this.finalStateBox.getValue().trim();
        if (finalState.isEmpty()) {
            errors.add(Component.translatable(KEY_ERROR_FINAL_STATE_EMPTY).getString());
        }
        String payload = this.payloadBox.getValue().trim();
        if (payload.isEmpty()) {
            errors.add(Component.translatable(KEY_ERROR_PAYLOAD_EMPTY).getString());
        }
        int priority = 0;
        try {
            priority = Integer.parseInt(this.priorityBox.getValue().trim());
        } catch (NumberFormatException ex) {
            errors.add(Component.translatable(KEY_ERROR_PRIORITY_INTEGER).getString());
        }

        TemplateMarkerData data = new TemplateMarkerData(
                TemplateMarkerData.CURRENT_SCHEMA,
                this.kind,
                id == null ? TemplateMarkerData.DEFAULT_ID : id,
                accepts,
                this.connectMode,
                this.channelBox.getValue().trim(),
                this.endpointBox.getValue().trim(),
                finalState,
                priority,
                this.hiddenTags,
                payload
        );
        errors.addAll(TemplateMarkerValidation.validateForSave(data));
        return new BuildResult(data, errors);
    }

    private static ResourceLocation parseResource(String value, String fieldKey, List<String> errors) {
        ResourceLocation location = ResourceLocation.tryParse(value.trim());
        if (location == null) {
            errors.add(Component.translatable(fieldKey).getString() + ": " + Component.translatable(KEY_ERROR_RESOURCE_LOCATION).getString());
        }
        return location;
    }

    private static List<ResourceLocation> parseResourceList(String value, String fieldKey, List<String> errors) {
        List<ResourceLocation> resources = new ArrayList<>();
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return resources;
        }
        for (String part : trimmed.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            ResourceLocation location = ResourceLocation.tryParse(entry);
            if (location == null) {
                errors.add(Component.translatable(fieldKey).getString() + ": " + Component.translatable(KEY_ERROR_RESOURCE_LOCATION_LIST).getString() + " " + entry);
            } else {
                resources.add(location);
            }
        }
        return resources;
    }

    private static Direction nextDirection(Direction direction) {
        Direction[] values = Direction.values();
        return values[(direction.ordinal() + 1) % values.length];
    }

    private static MarkerKind nextVisibleKind(MarkerKind kind) {
        return visibleKind(kind) == MarkerKind.CONNECTOR ? MarkerKind.ANCHOR : MarkerKind.CONNECTOR;
    }

    private static MarkerKind visibleKind(MarkerKind kind) {
        return switch (kind) {
            case ANCHOR, POINT -> MarkerKind.ANCHOR;
            case CONNECTOR, SLOT, LOT, VOLUME -> MarkerKind.CONNECTOR;
        };
    }

    private static MarkerVisual visualFor(MarkerKind kind) {
        return switch (kind) {
            case CONNECTOR -> MarkerVisual.CONNECTOR;
            case ANCHOR, POINT -> MarkerVisual.ANCHOR;
            case SLOT, LOT -> MarkerVisual.LOT;
            case VOLUME -> MarkerVisual.VOLUME;
        };
    }

    private static String markerKindKey(MarkerKind kind) {
        return visibleKind(kind) == MarkerKind.ANCHOR ? "template_marker.type.anchor" : "template_marker.type.connector";
    }

    private static String connectModeKey(ConnectMode mode) {
        return switch (mode) {
            case ADJACENT -> "template_marker.connect_mode.adjacent";
            case OVERLAP -> "template_marker.connect_mode.overlap";
        };
    }

    private static String formatList(List<ResourceLocation> values) {
        return values.stream().map(ResourceLocation::toString).collect(Collectors.joining(","));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (isEditingTextField() && this.getFocused() instanceof EditBox editBox && editBox.keyPressed(event)) {
            return true;
        }
        if (isEditingTextField() && this.minecraft != null && this.minecraft.options.keyInventory.isActiveAndMatches(InputConstants.getKey(event))) {
            return true;
        }
        return super.keyPressed(event);
    }

    private boolean isEditingTextField() {
        return isFocused(this.idBox)
                || isFocused(this.acceptsBox)
                || isFocused(this.channelBox)
                || isFocused(this.endpointBox)
                || isFocused(this.finalStateBox)
                || isFocused(this.priorityBox)
                || isFocused(this.payloadBox);
    }

    private static boolean isFocused(EditBox box) {
        return box != null && box.isFocused();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, PANEL_COLOR);
        graphics.submitOutline(this.leftPos, this.topPos, this.imageWidth, this.imageHeight, BORDER_COLOR);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, TEXT_COLOR, false);
        graphics.drawString(this.font, Component.translatable(KEY_POSITION, this.menu.pos().toShortString()).getString(), 14, 26, MUTED_COLOR, false);
        graphics.drawString(this.font, Component.translatable(KEY_FACING_VISUAL, this.facing.getSerializedName(), this.visual.getSerializedName()).getString(), 14, 38, MUTED_COLOR, false);

        drawLabel(graphics, Component.translatable(KEY_ID).getString(), 14, 92);
        drawLabel(graphics, Component.translatable(KEY_ACCEPTS).getString(), 14, 116);
        drawLabel(graphics, Component.translatable(KEY_CHANNEL).getString(), 14, 140);
        drawLabel(graphics, Component.translatable(KEY_ENDPOINT).getString(), 14, 164);
        drawLabel(graphics, Component.translatable(KEY_CONNECT_MODE).getString(), 14, 188);
        drawLabel(graphics, Component.translatable(KEY_FINAL_STATE).getString(), 14, 212);
        drawLabel(graphics, Component.translatable(KEY_PRIORITY).getString(), 14, 236);
        drawLabel(graphics, Component.translatable(KEY_PAYLOAD).getString(), 14, 260);
        if (!this.status.isEmpty()) {
            graphics.drawString(this.font, this.status, 14, 50, this.statusColor, false);
        }
    }

    private void drawLabel(GuiGraphics graphics, String label, int x, int y) {
        graphics.drawString(this.font, label, x, y, MUTED_COLOR, false);
    }

    private record BuildResult(TemplateMarkerData data, List<String> errors) {
    }
}
