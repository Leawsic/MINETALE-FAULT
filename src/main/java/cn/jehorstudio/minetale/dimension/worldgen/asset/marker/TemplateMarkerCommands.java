package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerScanner;
import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerValidationResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.PlacementBlockerValidator;
import cn.jehorstudio.minetale.dimension.worldgen.asset.blocker.ScannedPlacementBlocker;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetCatalog;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetDefinition;
import cn.jehorstudio.minetale.dimension.worldgen.asset.catalog.StructureAssetFilters;
import cn.jehorstudio.minetale.dimension.worldgen.asset.importer.StructureAssetImportResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.importer.StructureAssetImportTool;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatch;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatchRequest;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatchResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.matching.ConnectorMatcher;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.OccupancyMap;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.PlacementBlockerCleanupProcessor;
import cn.jehorstudio.minetale.dimension.worldgen.asset.placement.TemplateMarkerCleanupProcessor;
import cn.jehorstudio.minetale.dimension.worldgen.asset.plan.PlannedPiece;
import cn.jehorstudio.minetale.dimension.worldgen.asset.plan.StructurePlan;
import cn.jehorstudio.minetale.dimension.worldgen.asset.plan.StructurePlanChainBuilder;
import cn.jehorstudio.minetale.dimension.worldgen.asset.plan.StructurePlanDebugPlacer;
import cn.jehorstudio.minetale.dimension.worldgen.asset.plan.StructurePlanExtender;
import cn.jehorstudio.minetale.dimension.worldgen.asset.plan.StructurePlanPairBuilder;
import cn.jehorstudio.minetale.dimension.worldgen.asset.plan.StructurePlanResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.ScannedTemplateMarker;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.StructureAssetScanner;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.StructureAssetMarkerQueries;
import cn.jehorstudio.minetale.dimension.worldgen.asset.scanner.TemplateScanResult;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.StructureAssetTransform;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedAssetPlacement;
import cn.jehorstudio.minetale.dimension.worldgen.asset.transform.TransformedTemplateMarker;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.TemplateMirrorArgument;
import net.minecraft.commands.arguments.TemplateRotationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class TemplateMarkerCommands {
    private static final String KEY_INSPECT_TITLE = "commands.minetale.marker.inspect.title";
    private static final String KEY_INSPECT_FACING = "commands.minetale.marker.inspect.facing";
    private static final String KEY_INSPECT_VISUAL = "commands.minetale.marker.inspect.visual";
    private static final String KEY_INSPECT_SCHEMA = "commands.minetale.marker.inspect.schema";
    private static final String KEY_INSPECT_KIND = "commands.minetale.marker.inspect.kind";
    private static final String KEY_INSPECT_ID = "commands.minetale.marker.inspect.id";
    private static final String KEY_INSPECT_ACCEPTS = "commands.minetale.marker.inspect.accepts";
    private static final String KEY_INSPECT_CHANNEL = "commands.minetale.marker.inspect.channel";
    private static final String KEY_INSPECT_ENDPOINT = "commands.minetale.marker.inspect.endpoint";
    private static final String KEY_INSPECT_CONNECT_MODE = "commands.minetale.marker.inspect.connect_mode";
    private static final String KEY_INSPECT_FINAL_STATE = "commands.minetale.marker.inspect.final_state";
    private static final String KEY_INSPECT_PRIORITY = "commands.minetale.marker.inspect.priority";
    private static final String KEY_INSPECT_PAYLOAD = "commands.minetale.marker.inspect.payload";
    private static final String KEY_VALIDATE_SUCCESS = "commands.minetale.marker.validate.success";
    private static final String KEY_VALIDATE_FAILED = "commands.minetale.marker.validate.failed";
    private static final String KEY_MARKER_ERROR_NOT_MARKER = "commands.minetale.marker.error.not_marker";
    private static final String KEY_MARKER_ERROR_NO_BLOCK_ENTITY = "commands.minetale.marker.error.no_block_entity";
    private static final String KEY_SCAN_NOT_FOUND = "commands.minetale.asset.scan_template.not_found";
    private static final String KEY_SCAN_TITLE = "commands.minetale.asset.scan_template.title";
    private static final String KEY_SCAN_COUNT = "commands.minetale.asset.scan_template.count";
    private static final String KEY_SCAN_EMPTY = "commands.minetale.asset.scan_template.empty";
    private static final String KEY_SCAN_MARKER_LINE = "commands.minetale.asset.scan_template.marker_line";
    private static final String KEY_TRANSFORM_SCAN_FAILED = "commands.minetale.asset.transform.scan_failed";
    private static final String KEY_TRANSFORM_TITLE = "commands.minetale.asset.transform.title";
    private static final String KEY_TRANSFORM_TEMPLATE = "commands.minetale.asset.transform.template";
    private static final String KEY_TRANSFORM_ORIGIN = "commands.minetale.asset.transform.origin";
    private static final String KEY_TRANSFORM_ROTATION = "commands.minetale.asset.transform.rotation";
    private static final String KEY_TRANSFORM_MIRROR = "commands.minetale.asset.transform.mirror";
    private static final String KEY_TRANSFORM_SIZE = "commands.minetale.asset.transform.size";
    private static final String KEY_TRANSFORM_BOUNDS = "commands.minetale.asset.transform.bounds";
    private static final String KEY_TRANSFORM_COUNT = "commands.minetale.asset.transform.count";
    private static final String KEY_TRANSFORM_MARKER_LINE = "commands.minetale.asset.transform.marker_line";
    private static final String KEY_PLAN_TITLE = "commands.minetale.asset.plan_pair.title";
    private static final String KEY_PLAN_PIECES = "commands.minetale.asset.plan_pair.pieces";
    private static final String KEY_PLAN_BOUNDS = "commands.minetale.asset.plan_pair.bounds";
    private static final String KEY_PLAN_NOTE = "commands.minetale.asset.plan_pair.note";
    private static final String KEY_PLAN_PIECE_LINE = "commands.minetale.asset.plan_pair.piece_line";
    private static final String KEY_PLAN_PLACED = "commands.minetale.asset.plan_pair.placed";
    private static final String KEY_PLAN_NO_MATCH = "commands.minetale.asset.plan_pair.no_match";
    private static final String KEY_PLAN_MARKER_INDEX_OUT_OF_RANGE = "commands.minetale.asset.plan_pair.marker_index_out_of_range";
    private static final String KEY_PLAN_SCAN_FAILED = "commands.minetale.asset.plan_pair.scan_failed";
    private static final String KEY_PLAN_PLACE_FAILED = "commands.minetale.asset.plan_pair.place_failed";
    private static final String KEY_PLAN_EXTEND_TITLE = "commands.minetale.asset.plan_extend.title";
    private static final String KEY_PLAN_EXTEND_PLACED = "commands.minetale.asset.plan_extend.placed";
    private static final String KEY_PLAN_EXTEND_NO_START_ASSET = "commands.minetale.asset.plan_extend.no_start_asset";
    private static final String KEY_PLAN_EXTEND_SCAN_FAILED = "commands.minetale.asset.plan_extend.scan_failed";
    private static final String KEY_PLAN_EXTEND_FRONTIER_EMPTY = "commands.minetale.asset.plan_extend.frontier_empty";
    private static final String KEY_PLAN_EXTEND_NO_MORE_MATCHES = "commands.minetale.asset.plan_extend.no_more_matches";
    private static final String KEY_PLAN_EXTEND_NOTE = "commands.minetale.asset.plan_extend.note";
    private static final String KEY_PLAN_CHAIN_TITLE = "commands.minetale.asset.plan_chain.title";
    private static final String KEY_PLAN_CHAIN_GROUP = "commands.minetale.asset.plan_chain.group";
    private static final String KEY_PLAN_CHAIN_PLACED = "commands.minetale.asset.plan_chain.placed";
    private static final String KEY_PLAN_CHAIN_NO_TAIL = "commands.minetale.asset.plan_chain.no_tail";
    private static final String KEY_PLAN_CHAIN_NO_MATCH = "commands.minetale.asset.plan_chain.no_match";
    private static final String KEY_PLAN_CHAIN_CHAIN_END = "commands.minetale.asset.plan_chain.chain_end";
    private static final String KEY_PLAN_CHAIN_MAX_REACHED = "commands.minetale.asset.plan_chain.max_reached";
    private static final String KEY_CANDIDATES_TAG = "commands.minetale.asset.candidates.tag";
    private static final String KEY_CANDIDATES_COUNT = "commands.minetale.asset.candidates.count";
    private static final String KEY_CANDIDATES_EMPTY = "commands.minetale.asset.candidates.empty";
    private static final String KEY_IMPORT_DISABLED = "commands.minetale.asset.import_saved.disabled";
    private static final String KEY_IMPORT_NO_ROOT = "commands.minetale.asset.import_saved.no_root";
    private static final String KEY_IMPORT_SOURCE_MISSING = "commands.minetale.asset.import_saved.source_missing";
    private static final String KEY_IMPORT_SUCCESS = "commands.minetale.asset.import_saved.success";
    private static final String KEY_IMPORT_SOURCE = "commands.minetale.asset.import_saved.source";
    private static final String KEY_IMPORT_STRUCTURE_TARGET = "commands.minetale.asset.import_saved.structure_target";
    private static final String KEY_IMPORT_ASSET_TARGET = "commands.minetale.asset.import_saved.asset_target";
    private static final String KEY_IMPORT_SIZE = "commands.minetale.asset.import_saved.size";
    private static final String KEY_IMPORT_MARKERS = "commands.minetale.asset.import_saved.markers";
    private static final String KEY_IMPORT_OVERWRITTEN = "commands.minetale.asset.import_saved.overwritten";
    private static final String KEY_IMPORT_NEXT_RELOAD = "commands.minetale.asset.import_saved.next_reload";
    private static final String KEY_DRAFT_TITLE = "commands.minetale.asset.draft_json.title";
    private static final String KEY_DRAFT_BODY = "commands.minetale.asset.draft_json.body";

    private TemplateMarkerCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("minetale")
                .then(markerCommands())
                .then(assetCommands()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> markerCommands() {
        return Commands.literal("marker")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(TemplateMarkerCommands::inspect)))
                .then(Commands.literal("validate")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(TemplateMarkerCommands::validate)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> assetCommands() {
        return Commands.literal("asset")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(TemplateMarkerCommands::listAssets))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("asset_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        StructureAssetCatalog.ids(),
                                        builder
                                ))
                                .executes(TemplateMarkerCommands::inspectAsset)))
                .then(Commands.literal("validate")
                        .then(Commands.argument("asset_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        StructureAssetCatalog.ids(),
                                        builder
                                ))
                                .executes(TemplateMarkerCommands::validateAsset)))
                .then(Commands.literal("validate_all")
                        .executes(TemplateMarkerCommands::validateAllAssets))
                .then(Commands.literal("import_saved")
                        .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .then(Commands.argument("category", ResourceLocationArgument.id())
                                                .executes(TemplateMarkerCommands::importSavedAsset)))))
                .then(Commands.literal("draft_json")
                        .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                .then(Commands.argument("role", StringArgumentType.word())
                                        .then(Commands.argument("category", ResourceLocationArgument.id())
                                                .executes(TemplateMarkerCommands::draftAssetJson)))))
                .then(Commands.literal("scan_template")
                        .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        context.getSource().getLevel().getStructureManager().listTemplates(),
                                        builder
                                ))
                                .executes(TemplateMarkerCommands::scanTemplate)))
                .then(Commands.literal("scan_blockers")
                        .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        context.getSource().getLevel().getStructureManager().listTemplates(),
                                        builder
                                ))
                                .executes(TemplateMarkerCommands::scanBlockers)))
                .then(Commands.literal("validate_blockers")
                        .then(Commands.argument("template_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        context.getSource().getLevel().getStructureManager().listTemplates(),
                                        builder
                                ))
                                .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                        .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                                .then(Commands.argument("mirror", TemplateMirrorArgument.templateMirror())
                                                        .executes(TemplateMarkerCommands::validateBlockers))))))
                .then(Commands.literal("transform")
                        .then(Commands.argument("asset_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        StructureAssetCatalog.ids(),
                                        builder
                                ))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                                .then(Commands.argument("mirror", TemplateMirrorArgument.templateMirror())
                                                        .executes(TemplateMarkerCommands::transformAsset))))))
                .then(Commands.literal("match")
                        .then(Commands.argument("parent_asset_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        StructureAssetCatalog.ids(),
                                        builder
                                ))
                                .then(Commands.argument("parent_origin", BlockPosArgument.blockPos())
                                        .then(Commands.argument("parent_rotation", TemplateRotationArgument.templateRotation())
                                                .then(Commands.argument("parent_mirror", TemplateMirrorArgument.templateMirror())
                                                        .then(Commands.argument("parent_marker_index", IntegerArgumentType.integer(0))
                                                                .executes(TemplateMarkerCommands::matchAsset)
                                                                .then(Commands.argument("candidate_tag", ResourceLocationArgument.id())
                                                                        .executes(TemplateMarkerCommands::matchAsset))))))))
                .then(Commands.literal("debug_place")
                        .then(Commands.argument("asset_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        StructureAssetCatalog.ids(),
                                        builder
                                ))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                                .then(Commands.argument("mirror", TemplateMirrorArgument.templateMirror())
                                                        .then(Commands.argument("keep_markers", BoolArgumentType.bool())
                                                                .executes(TemplateMarkerCommands::debugPlaceAsset)))))))
                .then(Commands.literal("debug_plan_pair")
                        .then(Commands.argument("parent_asset_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        StructureAssetCatalog.ids(),
                                        builder
                                ))
                                .then(Commands.argument("parent_origin", BlockPosArgument.blockPos())
                                        .then(Commands.argument("parent_rotation", TemplateRotationArgument.templateRotation())
                                                .then(Commands.argument("parent_mirror", TemplateMirrorArgument.templateMirror())
                                                        .then(Commands.argument("parent_marker_index", IntegerArgumentType.integer(0))
                                                                .then(Commands.argument("keep_markers", BoolArgumentType.bool())
                                                                        .executes(TemplateMarkerCommands::debugPlanPair)
                                                                        .then(Commands.argument("candidate_tag", ResourceLocationArgument.id())
                                                                                .executes(TemplateMarkerCommands::debugPlanPair)))))))))
                .then(Commands.literal("debug_plan_extend")
                        .then(Commands.argument("start_asset_id", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        StructureAssetCatalog.ids(),
                                        builder
                                ))
                                .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                        .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                                .then(Commands.argument("mirror", TemplateMirrorArgument.templateMirror())
                                                        .then(Commands.argument("max_pieces", IntegerArgumentType.integer(1, 32))
                                                                .then(Commands.argument("keep_markers", BoolArgumentType.bool())
                                                                        .executes(TemplateMarkerCommands::debugPlanExtend)
                                                                        .then(Commands.argument("candidate_tag", ResourceLocationArgument.id())
                                                                                .executes(TemplateMarkerCommands::debugPlanExtend)))))))))
                .then(debugPlanChainCommands());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> debugPlanChainCommands() {
        return Commands.literal("debug_plan_chain")
                .then(Commands.argument("start_asset_id", ResourceLocationArgument.id())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                StructureAssetCatalog.ids(),
                                builder
                        ))
                        .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                        .then(Commands.argument("mirror", TemplateMirrorArgument.templateMirror())
                                                .then(Commands.argument("max_pieces", IntegerArgumentType.integer(1, 32))
                                                        .then(Commands.argument("keep_markers", BoolArgumentType.bool())
                                                                .then(Commands.argument("candidate_tag", ResourceLocationArgument.id())
                                                                        .then(Commands.argument("chain_group", StringArgumentType.word())
                                                                                .executes(TemplateMarkerCommands::debugPlanChain)))))))));
    }

    private static int inspect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        TemplateMarkerBlockEntity marker = getMarker(source, pos);
        if (marker == null) {
            return 0;
        }

        BlockState state = source.getLevel().getBlockState(pos);
        TemplateMarkerData data = marker.getData();
        send(source, Component.translatable(KEY_INSPECT_TITLE, pos.toShortString()));
        send(source, Component.translatable(KEY_INSPECT_FACING, state.getValue(TemplateMarkerBlock.FACING).getSerializedName()));
        send(source, Component.translatable(KEY_INSPECT_VISUAL, state.getValue(TemplateMarkerBlock.VISUAL).getSerializedName()));
        send(source, Component.translatable(KEY_INSPECT_SCHEMA, data.schema()));
        send(source, Component.translatable(KEY_INSPECT_KIND, data.kind().getSerializedName()));
        send(source, Component.translatable(KEY_INSPECT_ID, data.id().toString()));
        send(source, Component.translatable(KEY_INSPECT_ACCEPTS, formatList(data.accepts())));
        send(source, Component.translatable(KEY_INSPECT_CHANNEL, data.group()));
        send(source, Component.translatable(KEY_INSPECT_ENDPOINT, data.role()));
        send(source, Component.translatable(KEY_INSPECT_CONNECT_MODE, data.connectMode().getSerializedName()));
        send(source, Component.translatable(KEY_INSPECT_FINAL_STATE, data.finalState()));
        send(source, Component.translatable(KEY_INSPECT_PRIORITY, data.priority()));
        send(source, Component.translatable(KEY_INSPECT_PAYLOAD, data.payload()));
        return 1;
    }

    private static int validate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        TemplateMarkerBlockEntity marker = getMarker(source, pos);
        if (marker == null) {
            return 0;
        }

        List<String> errors = TemplateMarkerValidation.validateForSave(marker.getData());
        List<String> notes = TemplateMarkerValidation.notes(marker.getData());
        if (errors.isEmpty()) {
            send(source, Component.translatable(KEY_VALIDATE_SUCCESS, pos.toShortString()));
            for (String note : notes) {
                send(source, Component.literal("note: " + note));
            }
            return 1;
        }
        send(source, Component.translatable(KEY_VALIDATE_FAILED, pos.toShortString()));
        for (String error : errors) {
            send(source, Component.literal("- " + error));
        }
        for (String note : notes) {
            send(source, Component.literal("note: " + note));
        }
        return 0;
    }

    private static int listAssets(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<StructureAssetDefinition> assets = StructureAssetCatalog.list();
        send(source, Component.literal("Structure assets: " + assets.size()));
        for (StructureAssetDefinition asset : assets) {
            send(source, Component.literal("- " + asset.id()
                    + " role=" + asset.role()
                    + " template=" + asset.template()
                    + " markers=" + asset.markers().size()
                    + (asset.scanMarkers() ? "" : " (not scanned)")
                    + (asset.isValid() ? "" : " invalid")));
        }
        return assets.isEmpty() ? 0 : 1;
    }

    private static int inspectAsset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation assetId = ResourceLocationArgument.getId(context, "asset_id");
        StructureAssetDefinition asset = getAsset(source, assetId);
        if (asset == null) {
            return 0;
        }

        send(source, Component.literal("Structure Asset: " + asset.id()));
        send(source, Component.literal("template: " + asset.template()));
        send(source, Component.literal("role: " + asset.role()));
        send(source, Component.literal("category: " + asset.category()));
        send(source, Component.literal("weight: " + asset.weight()));
        send(source, Component.literal("tags: " + formatBracketedList(asset.tags())));
        send(source, Component.literal("footprint: " + asset.footprint().describe()));
        send(source, Component.literal("placement: " + asset.placement().describe()));
        send(source, Component.literal("scan_markers: " + asset.scanMarkers()));
        send(source, Component.literal("marker count: " + asset.markers().size() + (asset.scanMarkers() ? "" : " (not scanned)")));
        send(source, Component.literal("blocker_count: " + blockerCountForTemplate(source, asset.template())));
        if (!asset.loadErrors().isEmpty()) {
            send(source, Component.literal("load errors:"));
            for (String error : asset.loadErrors()) {
                send(source, Component.literal("- " + error));
            }
        }
        for (int i = 0; i < asset.markers().size(); i++) {
            send(source, formatMarkerLine(i, asset.markers().get(i)));
        }
        return 1;
    }

    private static int validateAsset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation assetId = ResourceLocationArgument.getId(context, "asset_id");
        StructureAssetDefinition asset = getAsset(source, assetId);
        if (asset == null) {
            return 0;
        }

        List<String> errors = asset.validate();
        send(source, Component.literal("blocker_count: " + blockerCountForTemplate(source, asset.template())));
        if (errors.isEmpty()) {
            send(source, Component.literal("Structure Asset valid: " + asset.id()));
            return 1;
        }

        send(source, Component.literal("Structure Asset invalid: " + asset.id()));
        for (String error : errors) {
            send(source, Component.literal("- " + error));
        }
        return 0;
    }

    private static int validateAllAssets(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<StructureAssetDefinition> assets = StructureAssetCatalog.list();
        int valid = 0;
        int invalid = 0;
        for (StructureAssetDefinition asset : assets) {
            if (asset.validate().isEmpty()) {
                valid++;
            } else {
                invalid++;
                send(source, Component.literal("- invalid " + asset.id()));
            }
        }
        send(source, Component.literal("Structure assets validate_all: total=" + assets.size() + " valid=" + valid + " invalid=" + invalid));
        return invalid == 0 ? 1 : 0;
    }

    private static int importSavedAsset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation templateId = ResourceLocationArgument.getId(context, "template_id");
        String role = StringArgumentType.getString(context, "role");
        ResourceLocation category = ResourceLocationArgument.getId(context, "category");
        if (!StructureAssetImportTool.enabled()) {
            source.sendFailure(Component.translatable(KEY_IMPORT_DISABLED));
            return 0;
        }

        try {
            StructureAssetImportResult result;
            if (!StructureAssetImportTool.hasResourceRoot()) {
                result = StructureAssetImportTool.draft(source.getLevel(), templateId, role, category);
                send(source, Component.translatable(KEY_IMPORT_NO_ROOT));
                sendImportResult(source, result);
                sendDraftJson(source, result);
                return 1;
            }

            result = StructureAssetImportTool.importSaved(source.getLevel(), templateId, role, category);
            send(source, Component.translatable(KEY_IMPORT_SUCCESS, templateId.toString()));
            sendImportResult(source, result);
            send(source, Component.translatable(KEY_IMPORT_NEXT_RELOAD));
            return 1;
        } catch (StructureAssetImportTool.MissingSavedStructureException ex) {
            source.sendFailure(Component.translatable(KEY_IMPORT_SOURCE_MISSING, ex.getMessage()));
            return 0;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("import_saved failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int draftAssetJson(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation templateId = ResourceLocationArgument.getId(context, "template_id");
        String role = StringArgumentType.getString(context, "role");
        ResourceLocation category = ResourceLocationArgument.getId(context, "category");
        try {
            StructureAssetImportResult result = StructureAssetImportTool.draft(source.getLevel(), templateId, role, category);
            sendImportResult(source, result);
            sendDraftJson(source, result);
            return 1;
        } catch (StructureAssetImportTool.MissingSavedStructureException ex) {
            source.sendFailure(Component.translatable(KEY_IMPORT_SOURCE_MISSING, ex.getMessage()));
            return 0;
        } catch (IOException ex) {
            source.sendFailure(Component.literal("draft_json failed: " + ex.getMessage()));
            return 0;
        }
    }

    private static int scanTemplate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation templateId = ResourceLocationArgument.getId(context, "template_id");
        TemplateScanResult result = StructureAssetScanner.scanTemplate(source.getLevel(), templateId).orElse(null);
        if (result == null) {
            source.sendFailure(Component.translatable(KEY_SCAN_NOT_FOUND, templateId.toString()));
            return 0;
        }

        send(source, Component.translatable(KEY_SCAN_TITLE, result.templateId().toString()));
        send(source, Component.translatable(KEY_SCAN_COUNT, result.markers().size()));
        send(source, Component.literal("connectors=" + StructureAssetMarkerQueries.connectors(result).size()
                + " anchors=" + StructureAssetMarkerQueries.anchors(result).size()));
        send(source, Component.literal("blocker_count=" + blockerCountForTemplate(source, templateId)));
        if (result.markers().isEmpty()) {
            send(source, Component.translatable(KEY_SCAN_EMPTY));
            return 1;
        }

        for (int i = 0; i < result.markers().size(); i++) {
            send(source, formatMarkerLine(i, result.markers().get(i)));
        }
        return 1;
    }

    private static int scanBlockers(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation templateId = ResourceLocationArgument.getId(context, "template_id");
        List<ScannedPlacementBlocker> blockers = PlacementBlockerScanner.scanTemplate(source.getLevel(), templateId).orElse(null);
        if (blockers == null) {
            source.sendFailure(Component.translatable(KEY_SCAN_NOT_FOUND, templateId.toString()));
            return 0;
        }

        send(source, Component.literal("Placement blockers: template=" + templateId));
        send(source, Component.literal("blocker_count=" + blockers.size()));
        int limit = Math.min(16, blockers.size());
        for (int i = 0; i < limit; i++) {
            send(source, Component.literal("[" + i + "] localPos=" + formatPos(blockers.get(i).localPos())));
        }
        if (blockers.size() > limit) {
            send(source, Component.literal("... " + (blockers.size() - limit) + " more"));
        }
        return 1;
    }

    private static int validateBlockers(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation templateId = ResourceLocationArgument.getId(context, "template_id");
        StructureTemplate template = source.getLevel().getStructureManager().get(templateId).orElse(null);
        if (template == null) {
            source.sendFailure(Component.translatable(KEY_SCAN_NOT_FOUND, templateId.toString()));
            return 0;
        }

        BlockPos origin = BlockPosArgument.getLoadedBlockPos(context, "origin");
        Rotation rotation = TemplateRotationArgument.getRotation(context, "rotation");
        Mirror mirror = TemplateMirrorArgument.getMirror(context, "mirror");
        PlacementBlockerValidationResult result = PlacementBlockerValidator.validate(source.getLevel(), template, origin, rotation, mirror);
        send(source, Component.literal("Placement blocker validation: template=" + templateId));
        send(source, Component.literal("origin=" + formatPos(origin)
                + " rotation=" + rotation.getSerializedName()
                + " mirror=" + mirror.getSerializedName()));
        send(source, Component.literal(result.summary()));
        send(source, Component.literal("blocker_count=" + result.blockerCount()
                + " success=" + result.success()
                + " failed_count=" + result.failedCount()));
        int limit = Math.min(16, result.failedStates().size());
        for (int i = 0; i < limit; i++) {
            send(source, Component.literal("failed[" + i + "] " + result.failedStates().get(i)));
        }
        if (result.failedStates().size() > limit) {
            send(source, Component.literal("... " + (result.failedStates().size() - limit) + " more failures"));
        }
        return result.success() ? 1 : 0;
    }

    private static int transformAsset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ResourceLocation assetId = ResourceLocationArgument.getId(context, "asset_id");
        StructureAssetDefinition asset = getAsset(source, assetId);
        if (asset == null) {
            return 0;
        }

        BlockPos origin = BlockPosArgument.getBlockPos(context, "pos");
        Rotation rotation = TemplateRotationArgument.getRotation(context, "rotation");
        Mirror mirror = TemplateMirrorArgument.getMirror(context, "mirror");
        TemplateScanResult scanResult = asset.scanResult();
        if (scanResult == null) {
            scanResult = StructureAssetScanner.scanTemplate(source.getLevel(), asset.template()).orElse(null);
        }
        if (scanResult == null) {
            source.sendFailure(Component.translatable(KEY_TRANSFORM_SCAN_FAILED, asset.template().toString()));
            return 0;
        }

        TransformedAssetPlacement placement = StructureAssetTransform.transform(asset, scanResult, origin, rotation, mirror);
        send(source, Component.translatable(KEY_TRANSFORM_TITLE, placement.assetId().toString()));
        send(source, Component.translatable(KEY_TRANSFORM_TEMPLATE, placement.templateId().toString()));
        send(source, Component.translatable(KEY_TRANSFORM_ORIGIN, formatPos(placement.origin())));
        send(source, Component.translatable(KEY_TRANSFORM_ROTATION, placement.rotation().getSerializedName()));
        send(source, Component.translatable(KEY_TRANSFORM_MIRROR, placement.mirror().getSerializedName()));
        send(source, Component.translatable(KEY_TRANSFORM_SIZE, formatPos(placement.templateSize())));
        send(source, Component.translatable(KEY_TRANSFORM_BOUNDS, formatBounds(placement.bounds())));
        send(source, Component.translatable(KEY_TRANSFORM_COUNT, placement.markers().size()));
        for (int i = 0; i < placement.markers().size(); i++) {
            send(source, formatTransformedMarkerLine(i, placement.markers().get(i)));
        }
        return 1;
    }

    private static int matchAsset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation parentAssetId = ResourceLocationArgument.getId(context, "parent_asset_id");
        StructureAssetDefinition parentAsset = getAsset(source, parentAssetId);
        if (parentAsset == null) {
            return 0;
        }

        BlockPos parentOrigin = BlockPosArgument.getBlockPos(context, "parent_origin");
        Rotation parentRotation = TemplateRotationArgument.getRotation(context, "parent_rotation");
        Mirror parentMirror = TemplateMirrorArgument.getMirror(context, "parent_mirror");
        int parentMarkerIndex = IntegerArgumentType.getInteger(context, "parent_marker_index");
        TemplateScanResult parentScanResult = scanResultFor(source, parentAsset);
        if (parentScanResult == null) {
            return 0;
        }

        TransformedAssetPlacement parentPlacement = StructureAssetTransform.transform(parentAsset, parentScanResult, parentOrigin, parentRotation, parentMirror);
        if (parentMarkerIndex >= parentPlacement.markers().size()) {
            source.sendFailure(Component.literal("parent_marker_index out of range: " + parentMarkerIndex + " markers=" + parentPlacement.markers().size()));
            return 0;
        }

        TransformedTemplateMarker parentMarker = parentPlacement.markers().get(parentMarkerIndex);
        OccupancyMap occupancyMap = new OccupancyMap();
        occupancyMap.add(parentAsset.id().toString(), parentPlacement.bounds());
        ResourceLocation candidateTag = optionalCandidateTag(context);
        List<StructureAssetDefinition> candidates = resolveCandidates(source, candidateTag);
        if (candidates == null) {
            return 0;
        }
        ConnectorMatchResult result = ConnectorMatcher.match(new ConnectorMatchRequest(
                parentPlacement,
                parentMarker,
                candidates,
                occupancyMap,
                10
        ));

        send(source, Component.literal("Asset match parent=" + parentAsset.id()));
        send(source, Component.literal("parent marker index=" + parentMarkerIndex
                + " worldPos=" + formatPos(parentMarker.worldPos())
                + " worldFacing=" + parentMarker.worldFacing().getSerializedName()
                + " kind=" + parentMarker.data().kind().getSerializedName()
                + " id=" + parentMarker.data().id()
                + " accepts=" + formatBracketedList(parentMarker.data().accepts())
                + " connect_mode=" + parentMarker.data().connectMode().getSerializedName()
                + " channel=" + formatString(parentMarker.data().group())
                + " endpoint=" + formatString(parentMarker.data().role())));
        send(source, Component.literal("parent bounds=" + formatBounds(parentPlacement.bounds())));
        for (String diagnostic : result.diagnostics()) {
            send(source, Component.literal(diagnostic));
        }
        for (int i = 0; i < result.matches().size(); i++) {
            send(source, formatMatchLine(i, result.matches().get(i)));
        }
        return result.matches().isEmpty() ? 0 : 1;
    }

    private static int debugPlaceAsset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation assetId = ResourceLocationArgument.getId(context, "asset_id");
        StructureAssetDefinition asset = getAsset(source, assetId);
        if (asset == null) {
            return 0;
        }

        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        Rotation rotation = TemplateRotationArgument.getRotation(context, "rotation");
        Mirror mirror = TemplateMirrorArgument.getMirror(context, "mirror");
        boolean keepMarkers = BoolArgumentType.getBool(context, "keep_markers");
        StructureTemplate template = source.getLevel().getStructureManager().get(asset.template()).orElse(null);
        if (template == null) {
            source.sendFailure(Component.literal("Structure template not found: " + asset.template()));
            return 0;
        }

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setMirror(mirror)
                .setRotation(rotation)
                .setKnownShape(false);
        settings.addProcessor(PlacementBlockerCleanupProcessor.INSTANCE);
        if (!keepMarkers) {
            settings.addProcessor(TemplateMarkerCleanupProcessor.INSTANCE);
        }

        boolean placed = template.placeInWorld(source.getLevel(), pos, pos, settings, RandomSource.create(), 2);
        if (!placed) {
            source.sendFailure(Component.literal("debug_place failed: " + asset.id()));
            return 0;
        }
        send(source, Component.literal("debug_place ok asset=" + asset.id()
                + " pos=" + formatPos(pos)
                + " rotation=" + rotation.getSerializedName()
                + " mirror=" + mirror.getSerializedName()
                + " keep_markers=" + keepMarkers));
        return 1;
    }

    private static int debugPlanPair(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation parentAssetId = ResourceLocationArgument.getId(context, "parent_asset_id");
        StructureAssetDefinition parentAsset = getAsset(source, parentAssetId);
        if (parentAsset == null) {
            return 0;
        }

        BlockPos parentOrigin = BlockPosArgument.getLoadedBlockPos(context, "parent_origin");
        Rotation parentRotation = TemplateRotationArgument.getRotation(context, "parent_rotation");
        Mirror parentMirror = TemplateMirrorArgument.getMirror(context, "parent_mirror");
        int parentMarkerIndex = IntegerArgumentType.getInteger(context, "parent_marker_index");
        boolean keepMarkers = BoolArgumentType.getBool(context, "keep_markers");
        ResourceLocation candidateTag = optionalCandidateTag(context);
        List<StructureAssetDefinition> candidates = resolveCandidates(source, candidateTag);
        if (candidates == null) {
            return 0;
        }

        StructurePlanResult planResult = StructurePlanPairBuilder.buildPair(
                source.getLevel(),
                parentAsset,
                parentOrigin,
                parentRotation,
                parentMirror,
                parentMarkerIndex,
                candidates
        );
        if (!planResult.success()) {
            sendPlanError(source, planResult.errors());
            return 0;
        }

        StructurePlan plan = planResult.plan();
        sendPlan(source, plan);
        StructurePlanDebugPlacer.Result placeResult = StructurePlanDebugPlacer.place(source.getLevel(), plan, keepMarkers);
        for (StructurePlanDebugPlacer.PieceResult pieceResult : placeResult.pieces()) {
            if (!pieceResult.placed()) {
                source.sendFailure(Component.translatable(KEY_PLAN_PLACE_FAILED, pieceResult.roleInPlan() + " " + pieceResult.error()));
            }
        }
        send(source, Component.translatable(
                KEY_PLAN_PLACED,
                placeResult.placed("parent"),
                placeResult.placed("child"),
                keepMarkers
        ));
        return placeResult.success() ? 1 : 0;
    }

    private static int debugPlanExtend(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation startAssetId = ResourceLocationArgument.getId(context, "start_asset_id");
        StructureAssetDefinition startAsset = StructureAssetCatalog.get(startAssetId).orElse(null);
        if (startAsset == null) {
            source.sendFailure(Component.translatable(KEY_PLAN_EXTEND_NO_START_ASSET, startAssetId.toString()));
            return 0;
        }

        BlockPos origin = BlockPosArgument.getLoadedBlockPos(context, "origin");
        Rotation rotation = TemplateRotationArgument.getRotation(context, "rotation");
        Mirror mirror = TemplateMirrorArgument.getMirror(context, "mirror");
        int maxPieces = IntegerArgumentType.getInteger(context, "max_pieces");
        boolean keepMarkers = BoolArgumentType.getBool(context, "keep_markers");
        ResourceLocation candidateTag = optionalCandidateTag(context);
        List<StructureAssetDefinition> candidates = resolveCandidates(source, candidateTag);
        if (candidates == null) {
            return 0;
        }

        StructurePlanResult planResult = StructurePlanExtender.extend(
                source.getLevel(),
                startAsset,
                origin,
                rotation,
                mirror,
                maxPieces,
                candidates
        );
        if (!planResult.success()) {
            sendExtendPlanError(source, planResult.errors());
            return 0;
        }

        StructurePlan plan = planResult.plan();
        sendExtendPlan(source, plan);
        StructurePlanDebugPlacer.Result placeResult = StructurePlanDebugPlacer.place(source.getLevel(), plan, keepMarkers);
        for (StructurePlanDebugPlacer.PieceResult pieceResult : placeResult.pieces()) {
            if (!pieceResult.placed()) {
                source.sendFailure(Component.translatable(KEY_PLAN_PLACE_FAILED, pieceResult.roleInPlan() + " " + pieceResult.error()));
            }
        }
        send(source, Component.translatable(KEY_PLAN_EXTEND_PLACED, placeResult.success(), keepMarkers));
        return placeResult.success() ? 1 : 0;
    }

    private static int debugPlanChain(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ResourceLocation startAssetId = ResourceLocationArgument.getId(context, "start_asset_id");
        StructureAssetDefinition startAsset = StructureAssetCatalog.get(startAssetId).orElse(null);
        if (startAsset == null) {
            source.sendFailure(Component.translatable(KEY_PLAN_EXTEND_NO_START_ASSET, startAssetId.toString()));
            return 0;
        }

        BlockPos origin = BlockPosArgument.getLoadedBlockPos(context, "origin");
        Rotation rotation = TemplateRotationArgument.getRotation(context, "rotation");
        Mirror mirror = TemplateMirrorArgument.getMirror(context, "mirror");
        int maxPieces = IntegerArgumentType.getInteger(context, "max_pieces");
        boolean keepMarkers = BoolArgumentType.getBool(context, "keep_markers");
        ResourceLocation candidateTag = ResourceLocationArgument.getId(context, "candidate_tag");
        String chainGroup = StringArgumentType.getString(context, "chain_group");
        List<StructureAssetDefinition> candidates = resolveCandidates(source, candidateTag);
        if (candidates == null) {
            return 0;
        }
        send(source, Component.translatable(KEY_PLAN_CHAIN_GROUP, chainGroup));

        StructurePlanResult planResult = StructurePlanChainBuilder.build(
                source.getLevel(),
                startAsset,
                origin,
                rotation,
                mirror,
                maxPieces,
                candidates,
                candidateTag,
                chainGroup
        );
        if (!planResult.success()) {
            sendChainPlanError(source, planResult.errors());
            return 0;
        }

        StructurePlan plan = planResult.plan();
        sendChainPlan(source, plan);
        StructurePlanDebugPlacer.Result placeResult = StructurePlanDebugPlacer.place(source.getLevel(), plan, keepMarkers);
        for (StructurePlanDebugPlacer.PieceResult pieceResult : placeResult.pieces()) {
            if (!pieceResult.placed()) {
                source.sendFailure(Component.translatable(KEY_PLAN_PLACE_FAILED, pieceResult.roleInPlan() + " " + pieceResult.error()));
            }
        }
        send(source, Component.translatable(KEY_PLAN_CHAIN_PLACED, placeResult.success(), keepMarkers));
        return placeResult.success() ? 1 : 0;
    }

    private static ResourceLocation optionalCandidateTag(CommandContext<CommandSourceStack> context) {
        try {
            return ResourceLocationArgument.getId(context, "candidate_tag");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<StructureAssetDefinition> resolveCandidates(CommandSourceStack source, ResourceLocation candidateTag) {
        List<StructureAssetDefinition> candidates = candidateTag == null
                ? StructureAssetFilters.all()
                : StructureAssetFilters.byTag(candidateTag);
        if (candidateTag != null) {
            send(source, Component.translatable(KEY_CANDIDATES_TAG, candidateTag.toString()));
        }
        send(source, Component.translatable(KEY_CANDIDATES_COUNT, candidates.size()));
        if (candidateTag != null && candidates.isEmpty()) {
            source.sendFailure(Component.translatable(KEY_CANDIDATES_EMPTY, candidateTag.toString()));
            return null;
        }
        return candidates;
    }

    private static TemplateMarkerBlockEntity getMarker(CommandSourceStack source, BlockPos pos) {
        BlockState state = source.getLevel().getBlockState(pos);
        if (!state.is(TemplateMarkerRegistry.TEMPLATE_MARKER.get())) {
            source.sendFailure(Component.translatable(KEY_MARKER_ERROR_NOT_MARKER, pos.toShortString()));
            return null;
        }
        BlockEntity blockEntity = source.getLevel().getBlockEntity(pos);
        if (!(blockEntity instanceof TemplateMarkerBlockEntity marker)) {
            source.sendFailure(Component.translatable(KEY_MARKER_ERROR_NO_BLOCK_ENTITY, pos.toShortString()));
            return null;
        }
        return marker;
    }

    private static StructureAssetDefinition getAsset(CommandSourceStack source, ResourceLocation assetId) {
        StructureAssetDefinition asset = StructureAssetCatalog.get(assetId).orElse(null);
        if (asset == null) {
            source.sendFailure(Component.literal("Structure asset not found: " + assetId));
            return null;
        }
        return asset;
    }

    private static TemplateScanResult scanResultFor(CommandSourceStack source, StructureAssetDefinition asset) {
        TemplateScanResult scanResult = asset.scanResult();
        if (scanResult == null) {
            scanResult = StructureAssetScanner.scanTemplate(source.getLevel(), asset.template()).orElse(null);
        }
        if (scanResult == null) {
            source.sendFailure(Component.translatable(KEY_TRANSFORM_SCAN_FAILED, asset.template().toString()));
            return null;
        }
        return scanResult;
    }

    private static int blockerCountForTemplate(CommandSourceStack source, ResourceLocation templateId) {
        return PlacementBlockerScanner.scanTemplate(source.getLevel(), templateId)
                .map(List::size)
                .orElse(0);
    }

    private static void sendPlanError(CommandSourceStack source, List<String> errors) {
        if (errors.isEmpty()) {
            source.sendFailure(Component.translatable(KEY_PLAN_NO_MATCH));
            return;
        }
        String error = errors.getFirst();
        if ("no_match".equals(error)) {
            source.sendFailure(Component.translatable(KEY_PLAN_NO_MATCH));
            return;
        }
        if (error.startsWith("scan_failed:")) {
            source.sendFailure(Component.translatable(KEY_PLAN_SCAN_FAILED, error.substring("scan_failed:".length())));
            return;
        }
        if (error.startsWith("marker_index_out_of_range:")) {
            String[] parts = error.split(":");
            String index = parts.length > 1 ? parts[1] : "?";
            String count = parts.length > 2 ? parts[2] : "?";
            source.sendFailure(Component.translatable(KEY_PLAN_MARKER_INDEX_OUT_OF_RANGE, index, count));
            return;
        }
        source.sendFailure(Component.literal(error));
    }

    private static void sendExtendPlanError(CommandSourceStack source, List<String> errors) {
        if (errors.isEmpty()) {
            source.sendFailure(Component.translatable(KEY_PLAN_EXTEND_NO_MORE_MATCHES));
            return;
        }
        String error = errors.getFirst();
        if (error.startsWith("scan_failed:")) {
            source.sendFailure(Component.translatable(KEY_PLAN_EXTEND_SCAN_FAILED, error.substring("scan_failed:".length())));
            return;
        }
        source.sendFailure(Component.literal(error));
    }

    private static void sendChainPlanError(CommandSourceStack source, List<String> errors) {
        if (errors.isEmpty()) {
            source.sendFailure(Component.translatable(KEY_PLAN_CHAIN_NO_MATCH));
            return;
        }
        String error = errors.getFirst();
        if (error.startsWith("scan_failed:")) {
            source.sendFailure(Component.translatable(KEY_PLAN_EXTEND_SCAN_FAILED, error.substring("scan_failed:".length())));
            return;
        }
        if (error.startsWith("no_tail:")) {
            source.sendFailure(Component.translatable(KEY_PLAN_CHAIN_NO_TAIL, error.substring("no_tail:".length())));
            return;
        }
        if ("no_more_matches".equals(error)) {
            source.sendFailure(Component.translatable(KEY_PLAN_CHAIN_NO_MATCH));
            return;
        }
        source.sendFailure(Component.literal(error));
    }

    private static void sendPlan(CommandSourceStack source, StructurePlan plan) {
        send(source, Component.translatable(KEY_PLAN_TITLE));
        send(source, Component.translatable(KEY_PLAN_PIECES, plan.pieces().size()));
        send(source, Component.translatable(KEY_PLAN_BOUNDS, formatBounds(plan.bounds())));
        for (String note : plan.notes()) {
            send(source, Component.translatable(KEY_PLAN_NOTE, note));
        }
        for (int i = 0; i < plan.pieces().size(); i++) {
            send(source, formatPlannedPieceLine(i, plan.pieces().get(i)));
        }
    }

    private static void sendExtendPlan(CommandSourceStack source, StructurePlan plan) {
        send(source, Component.translatable(KEY_PLAN_EXTEND_TITLE));
        send(source, Component.translatable(KEY_PLAN_PIECES, plan.pieces().size()));
        send(source, Component.translatable(KEY_PLAN_BOUNDS, formatBounds(plan.bounds())));
        for (String note : plan.notes()) {
            sendExtendNote(source, note);
        }
        for (int i = 0; i < plan.pieces().size(); i++) {
            send(source, formatPlannedPieceLine(i, plan.pieces().get(i)));
        }
    }

    private static void sendExtendNote(CommandSourceStack source, String note) {
        if ("frontier_empty".equals(note)) {
            send(source, Component.translatable(KEY_PLAN_EXTEND_FRONTIER_EMPTY));
            return;
        }
        if ("no_more_matches".equals(note)) {
            send(source, Component.translatable(KEY_PLAN_EXTEND_NO_MORE_MATCHES));
            return;
        }
        send(source, Component.translatable(KEY_PLAN_EXTEND_NOTE, note));
    }

    private static void sendChainPlan(CommandSourceStack source, StructurePlan plan) {
        send(source, Component.translatable(KEY_PLAN_CHAIN_TITLE));
        send(source, Component.translatable(KEY_PLAN_PIECES, plan.pieces().size()));
        send(source, Component.translatable(KEY_PLAN_BOUNDS, formatBounds(plan.bounds())));
        for (String note : plan.notes()) {
            sendChainNote(source, note);
        }
        for (int i = 0; i < plan.pieces().size(); i++) {
            send(source, formatPlannedPieceLine(i, plan.pieces().get(i)));
        }
    }

    private static void sendChainNote(CommandSourceStack source, String note) {
        if ("chain_end".equals(note)) {
            send(source, Component.translatable(KEY_PLAN_CHAIN_CHAIN_END));
            return;
        }
        if ("no_more_matches".equals(note)) {
            send(source, Component.translatable(KEY_PLAN_CHAIN_NO_MATCH));
            return;
        }
        if (note.startsWith("max_pieces reached:")) {
            send(source, Component.translatable(KEY_PLAN_CHAIN_MAX_REACHED, note.substring("max_pieces reached:".length()).trim()));
            return;
        }
        send(source, Component.translatable(KEY_PLAN_EXTEND_NOTE, note));
    }

    private static void sendImportResult(CommandSourceStack source, StructureAssetImportResult result) {
        send(source, Component.translatable(KEY_IMPORT_SOURCE, result.sourceNbt().toString()));
        send(source, Component.translatable(KEY_IMPORT_STRUCTURE_TARGET, result.targetNbt().toString()));
        send(source, Component.translatable(KEY_IMPORT_ASSET_TARGET, result.targetAssetJson().toString()));
        send(source, Component.translatable(KEY_IMPORT_SIZE, formatPos(result.size())));
        send(source, Component.translatable(KEY_IMPORT_MARKERS, result.markerCount()));
        send(source, Component.literal("Blockers: " + result.blockerCount()));
        send(source, Component.translatable(KEY_IMPORT_OVERWRITTEN, result.overwritten()));
    }

    private static void sendDraftJson(CommandSourceStack source, StructureAssetImportResult result) {
        send(source, Component.translatable(KEY_DRAFT_TITLE, result.templateId().toString()));
        send(source, Component.translatable(KEY_DRAFT_BODY, result.draftJson()));
    }

    private static void send(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    private static String formatList(Collection<ResourceLocation> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(ResourceLocation::toString).collect(Collectors.joining(","));
    }

    private static String formatBracketedList(Collection<ResourceLocation> values) {
        String formatted = formatList(values);
        if ("[]".equals(formatted)) {
            return formatted;
        }
        return "[" + formatted + "]";
    }

    private static Component formatMarkerLine(int index, ScannedTemplateMarker marker) {
        TemplateMarkerData data = marker.data();
        return Component.translatable(
                KEY_SCAN_MARKER_LINE,
                index,
                formatPos(marker.localPos()),
                marker.facing().getSerializedName(),
                marker.visual().getSerializedName(),
                markerKindDisplay(data.kind()),
                data.id().toString(),
                formatBracketedList(data.accepts()),
                data.connectMode().getSerializedName(),
                formatString(data.group()),
                formatString(data.role()),
                data.finalState(),
                data.priority(),
                data.payload()
        );
    }

    private static String formatPos(Vec3i pos) {
        return "[" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "]";
    }

    private static String formatBounds(BoundingBox bounds) {
        return "[" + bounds.minX() + "," + bounds.minY() + "," + bounds.minZ()
                + "]..[" + bounds.maxX() + "," + bounds.maxY() + "," + bounds.maxZ() + "]";
    }

    private static String formatString(String value) {
        return value == null || value.isEmpty() ? "\"\"" : value;
    }

    private static Component formatTransformedMarkerLine(int index, TransformedTemplateMarker marker) {
        TemplateMarkerData data = marker.data();
        return Component.translatable(
                KEY_TRANSFORM_MARKER_LINE,
                index,
                formatPos(marker.localPos()),
                marker.localFacing().getSerializedName(),
                formatPos(marker.worldPos()),
                marker.worldFacing().getSerializedName(),
                markerKindDisplay(data.kind()),
                data.id().toString(),
                marker.visual().getSerializedName(),
                formatBracketedList(data.accepts()),
                data.connectMode().getSerializedName(),
                formatString(data.group()),
                formatString(data.role()),
                data.finalState(),
                data.priority(),
                data.payload()
        );
    }

    private static Component formatMatchLine(int index, ConnectorMatch match) {
        return Component.literal("[" + index + "] child=" + match.childAsset().id()
                + " origin=" + formatPos(match.origin())
                + " rotation=" + match.rotation().getSerializedName()
                + " mirror=" + match.mirror().getSerializedName()
                + " childLocal=" + formatPos(match.childMarker().localPos())
                + " parentMarkerPos=" + formatPos(match.parentMarker().worldPos())
                + " parentFacing=" + match.parentMarker().worldFacing().getSerializedName()
                + " parentConnectMode=" + match.parentMarker().data().connectMode().getSerializedName()
                + " childWorld=" + formatPos(match.childMarker().worldPos())
                + " childFacing=" + match.childMarker().worldFacing().getSerializedName()
                + " childConnectMode=" + match.childMarker().data().connectMode().getSerializedName()
                + " targetMode=" + match.parentMarker().data().connectMode().getSerializedName()
                + " childChannel=" + formatString(match.childMarker().data().group())
                + " childEndpoint=" + formatString(match.childMarker().data().role())
                + " bounds=" + formatBounds(match.bounds())
                + " collides=" + match.collides()
                + " notes=" + String.join(";", match.notes()));
    }

    private static String markerKindDisplay(MarkerKind kind) {
        return switch (kind) {
            case SLOT, VOLUME -> kind.getSerializedName() + " (experimental)";
            case LOT -> "slot (experimental, legacy lot)";
            case POINT -> "anchor (legacy point)";
            case CONNECTOR, ANCHOR -> kind.getSerializedName();
        };
    }

    private static Component formatPlannedPieceLine(int index, PlannedPiece piece) {
        return Component.translatable(
                KEY_PLAN_PIECE_LINE,
                index,
                piece.roleInPlan(),
                piece.assetId().toString(),
                piece.templateId().toString(),
                formatPos(piece.origin()),
                piece.rotation().getSerializedName(),
                piece.mirror().getSerializedName(),
                formatBounds(piece.bounds())
        );
    }
}
