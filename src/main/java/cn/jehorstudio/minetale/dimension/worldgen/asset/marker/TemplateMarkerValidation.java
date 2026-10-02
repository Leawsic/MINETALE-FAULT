package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public final class TemplateMarkerValidation {
    private static final ResourceLocation DEBUG_TAG = ResourceLocation.fromNamespaceAndPath("minetale", "debug");

    private TemplateMarkerValidation() {
    }

    public static List<String> validateForSave(TemplateMarkerData data) {
        List<String> errors = new ArrayList<>(data.validate());
        errors.addAll(validateSemanticContract(data));
        errors.addAll(validateFinalState(data.finalState()));
        return errors;
    }

    public static List<String> notes(TemplateMarkerData data) {
        List<String> notes = new ArrayList<>();
        switch (data.kind()) {
            case CONNECTOR -> {
                if (data.group().isBlank()) {
                    notes.add("connector channel is empty");
                }
                if (data.role().isBlank()) {
                    notes.add("connector endpoint is empty");
                }
            }
            case SLOT, VOLUME -> notes.add(data.kind().getSerializedName() + " is experimental and does not participate in connector matching");
            case ANCHOR -> {
            }
            case LOT, POINT -> notes.add(data.kind().getSerializedName() + " is legacy; use slot or anchor");
        }
        return notes;
    }

    private static List<String> validateSemanticContract(TemplateMarkerData data) {
        List<String> errors = new ArrayList<>();
        switch (data.kind()) {
            case CONNECTOR -> {
                if (data.id().equals(TemplateMarkerData.DEFAULT_ID) && !isDebugMarker(data)) {
                    errors.add("connector id must not be " + TemplateMarkerData.DEFAULT_ID + " outside debug markers");
                }
                if (data.accepts().isEmpty()) {
                    errors.add("connector accepts must not be empty");
                }
                if (data.connectMode() == null) {
                    errors.add("connector connect_mode is required");
                }
            }
            case ANCHOR -> {
                if (data.id().equals(TemplateMarkerData.DEFAULT_ID)) {
                    errors.add("anchor id must not be " + TemplateMarkerData.DEFAULT_ID);
                }
            }
            case SLOT, VOLUME, LOT, POINT -> {
            }
        }
        return errors;
    }

    private static boolean isDebugMarker(TemplateMarkerData data) {
        return data.tags().contains(DEBUG_TAG)
                || data.group().startsWith("debug")
                || data.role().startsWith("debug")
                || data.id().getPath().startsWith("debug");
    }

    public static List<String> validateFinalState(String finalState) {
        List<String> errors = new ArrayList<>();
        String trimmed = finalState == null ? "" : finalState.trim();
        if (trimmed.isEmpty()) {
            errors.add("final_state must not be empty");
            return errors;
        }

        String blockIdText = trimmed;
        int bracketIndex = trimmed.indexOf('[');
        if (bracketIndex >= 0) {
            blockIdText = trimmed.substring(0, bracketIndex).trim();
            // 当前只验证方块 ID，方块状态属性不在此处解析
        }

        ResourceLocation blockId = ResourceLocation.tryParse(blockIdText);
        if (blockId == null) {
            errors.add("final_state has invalid block id: " + blockIdText);
            return errors;
        }
        if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
            errors.add("final_state block does not exist: " + blockId);
        }
        return errors;
    }
}
