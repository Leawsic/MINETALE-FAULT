package cn.jehorstudio.minetale.dimension.ebott;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.ebott.entrance.CaveEntranceGenerator;
import cn.jehorstudio.minetale.dimension.ebott.mountain.MountainGenerator;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftData;
import cn.jehorstudio.minetale.dimension.ebott.shaft.ShaftGenerator;
import cn.jehorstudio.minetale.dimension.worldgen.registry.ModWorldgenKeys;
import cn.jehorstudio.minetale.dimension.worldgen.region.origin.settings.OriginSettings;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

//  Ebott 调试与验收命令
public final class EbottCommands {
    private static final String LOCATE_NODE = "locate";
    private static final String STRUCTURE_NODE = "structure";
    private static final String EBOTT_MOUNTAIN_ID = "minetale:ebott_mountain";
    private static final String ORIGIN_ID = "minetale:origin";
    private static final String EBOTT_MOUNTAIN_ALIAS = "ebott_mountain";
    private static final String ORIGIN_ALIAS = "origin";
    private static final int LOCATE_SAFE_CLEARANCE = 8;
    // false 时不注册整个 /minetale ebott 命令树。
    public static boolean ENABLED = true;

    private EbottCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("minetale")
                        .then(commandTree())
        );
        registerLocateQueries(event);
    }

    private static void registerLocateQueries(RegisterCommandsEvent event) {
        CommandNode<CommandSourceStack> locate = event.getDispatcher().getRoot().getChild(LOCATE_NODE);
        if (locate == null) {
            MineTale.LOGGER.error("[Ebott] 原版命令节点 /{} 不存在，跳过 locate 注册。", LOCATE_NODE);
            return;
        }

        CommandNode<CommandSourceStack> structure = locate.getChild(STRUCTURE_NODE);
        if (structure == null) {
            MineTale.LOGGER.error("[Ebott] 原版命令节点 /{} {} 不存在，跳过 locate 注册。", LOCATE_NODE, STRUCTURE_NODE);
            return;
        }

        addLiteral(structure, EBOTT_MOUNTAIN_ID, EbottCommands::locateEbottMountain);
        addLiteral(structure, EBOTT_MOUNTAIN_ALIAS, EbottCommands::locateEbottMountain);
        addLiteral(structure, ORIGIN_ID, EbottCommands::locateOrigin);
        addLiteral(structure, ORIGIN_ALIAS, EbottCommands::locateOrigin);
    }

    private static void addLiteral(
            CommandNode<CommandSourceStack> parent,
            String literal,
            Command<CommandSourceStack> command
    ) {
        if (parent.getChild(literal) != null) {
            MineTale.LOGGER.error("[Ebott] 子命令 {} 已存在，跳过注册（禁止覆盖）。", literal);
            return;
        }
        parent.addChild(Commands.literal(literal).executes(command).build());
    }

    private static int locateEbottMountain(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getLevel().dimension() != Level.OVERWORLD) {
            return notFound(source, EBOTT_MOUNTAIN_ID);
        }

        EbottData.Snapshot snapshot = EbottData.snapshotFor(source.getServer());
        if (snapshot == null) {
            return notFound(source, EBOTT_MOUNTAIN_ID);
        }

        PlaceManager.Place place = snapshot.place();
        int safeOffset = (int) StrictMath.ceil(
                snapshot.shaft().profile().maximumShaftRadius()
        ) + LOCATE_SAFE_CLEARANCE;
        return showLocateResult(
                source,
                EBOTT_MOUNTAIN_ID,
                new BlockPos(
                        place.centerX() + safeOffset,
                        place.summitY() + 2,
                        place.centerZ() + safeOffset
                )
        );
    }

    private static int locateOrigin(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!source.getLevel().dimension().equals(ModWorldgenKeys.UNDERGROUND_LEVEL)) {
            return notFound(source, ORIGIN_ID);
        }

        return showLocateResult(
                source,
                ORIGIN_ID,
                new BlockPos(
                        OriginSettings.centerX(),
                        OriginSettings.FLOOR_Y,
                        OriginSettings.centerZ()
                )
        );
    }

    private static int notFound(CommandSourceStack source, String id) {
        source.sendFailure(Component.translatable("commands.locate.structure.not_found", id));
        return 0;
    }

    private static int showLocateResult(CommandSourceStack source, String id, BlockPos target) {
        BlockPos sourcePos = BlockPos.containing(source.getPosition());
        int distance = Mth.floor(dist(sourcePos.getX(), sourcePos.getZ(), target.getX(), target.getZ()));

        Component coordinates = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", target.getX(), target.getY(), target.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand(
                        "/tp @s " + target.getX() + " " + target.getY() + " " + target.getZ()
                ))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip")))
        );

        source.sendSuccess(
                () -> Component.translatable("commands.locate.structure.success", id, coordinates, distance),
                false
        );
        return distance;
    }

    private static float dist(int x1, int z1, int x2, int z2) {
        int dx = x2 - x1;
        int dz = z2 - z1;
        return Mth.sqrt(dx * dx + dz * dz);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("ebott")
                .requires(source -> ENABLED && source.hasPermission(2))
                .then(Commands.literal("probe")
                        .executes(context -> probe(context.getSource())))
                .then(Commands.literal("repair")
                        .executes(context -> repair(context.getSource())))
                .then(Commands.literal("debug")
                        .executes(context -> debug(context.getSource())));
    }

    private static int probe(CommandSourceStack source) {
        EbottData.Snapshot snapshot = requireSnapshot(source);
        if (snapshot == null) {
            return 0;
        }

        ShaftGenerator.VerificationResult result;

        if (source.getLevel().dimension() == Level.OVERWORLD) {
            result = ShaftGenerator.verifySource(
                    source.getLevel(),
                    snapshot
            );
        } else if (source.getLevel().dimension().equals(ModWorldgenKeys.UNDERGROUND_LEVEL)) {
            result = ShaftGenerator.verifyTarget(
                    source.getLevel(),
                    snapshot,
                    EbottDestination.centerX(),
                    EbottDestination.centerZ(),
                    EbottDestination.targetMinYResolver(
                            source.getLevel(),
                            source.getLevel().getChunkSource().getGenerator()
                    ),
                    EbottDestination.targetSeamY(
                            source.getLevel(),
                            source.getLevel().getChunkSource().getGenerator(),
                            snapshot.shaft().profile()
                    )
            );
        } else {
            source.sendFailure(
                    Component.literal("当前维度不包含伊伯特共享竖井。")
            );
            return 0;
        }

        if (!result.success()) {
            source.sendFailure(
                    Component.literal(
                            "伊伯特共享竖井 FAIL：%s；已检查 %d 方块。"
                                    .formatted(
                                            result.detail(),
                                            result.checkedBlocks()
                                    )
                    )
            );
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "伊伯特共享竖井 PASS：dimension=%s，完整检查 %d 方块。"
                                .formatted(
                                        source.getLevel().dimension().location(),
                                        result.checkedBlocks()
                                )
                ),
                false
        );

        return 1;
    }

    private static int repair(CommandSourceStack source) {
        EbottData.Snapshot snapshot = requireSnapshot(source);
        if (snapshot == null) {
            return 0;
        }

        EbottData data = EbottData.get(
                source.getServer().overworld()
        );

        MountainGenerator.RepairResult result = MountainGenerator.repairNow(
                source.getServer(),
                data,
                snapshot
        );

        source.sendSuccess(
                () -> Component.literal(
                        (
                                "伊伯特入口已幂等修复：洞窟 %d Chunk，主世界竖井 %d Chunk，"
                                        + "地下世界竖井 %d Chunk。"
                        ).formatted(
                                result.caveChunks(),
                                result.sourceChunks(),
                                result.targetChunks()
                        )
                ),
                true
        );

        return 1;
    }

    private static int debug(CommandSourceStack source) {
        EbottData.Snapshot snapshot = requireSnapshot(source);
        if (snapshot == null) {
            return 0;
        }

        PlaceManager.Place place = snapshot.place();
        ShaftData shaft = snapshot.shaft();
        CaveEntranceGenerator.Placement cave = snapshot.caveEntrance();
        ServerLevel target = source.getServer().getLevel(ModWorldgenKeys.UNDERGROUND_LEVEL);
        int resolvedTargetSeamY = target == null
                ? shaft.targetSeamY()
                : EbottDestination.targetSeamY(
                        target,
                        target.getChunkSource().getGenerator(),
                        shaft.profile()
                );

        source.sendSuccess(
                () -> Component.literal(
                        (
                                "伊伯特调试：center=(%d,%d), base=%d, summit=%d, "
                                        + "radius=%d, blend=%d, "
                                        + "sourceLogicalOriginY=%d, sourceSeamY=%d, "
                                        + "targetLogicalOriginY=%d, storedTargetSeamY=%d, resolvedTargetSeamY=%d, "
                                        + "fallbackTargetArrivalY=%d, "
                                        + "target=%s(%d,%d), "
                                        + "caveOrigin=%s, caveMouth=%s, shaftOpeningY=%d, "
                                        + "profile=%d, generation=%d, "
                                        + "mountainSeed=%d, shaftSeed=%d"
                        ).formatted(
                                place.centerX(),
                                place.centerZ(),
                                place.baseY(),
                                place.summitY(),
                                place.mountainOuterRadius(),
                                place.outerBlendWidth(),
                                shaft.sourceLogicalOriginY(),
                                shaft.sourceSeamY(),
                                shaft.targetLogicalOriginY(),
                                shaft.targetSeamY(),
                                resolvedTargetSeamY,
                                shaft.fallbackTargetArrivalY(),
                                EbottDestination.ENABLE_SNOWDIN_TARGET ? "snowdin" : "origin",
                                EbottDestination.centerX(),
                                EbottDestination.centerZ(),
                                cave.origin(),
                                cave.mouth(),
                                cave.shaftOpeningY(),
                                place.profileVersion(),
                                place.generationVersion(),
                                place.mountainSeed(),
                                shaft.shaftSeed()
                        )
                ),
                false
        );

        return 1;
    }

    private static EbottData.Snapshot requireSnapshot(CommandSourceStack source) {
        EbottData.Snapshot snapshot = EbottData.snapshotFor(
                source.getServer()
        );

        if (snapshot == null) {
            source.sendFailure(
                    Component.literal("伊伯特数据尚未解析或发布。")
            );
        }

        return snapshot;
    }
}
