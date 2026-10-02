package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.generator.UndergroundNoiseGenerator;
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
import net.minecraft.util.Mth;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// 将标准 locate 查询直接绑定到确定性规划中的 accepted lots。
public final class SnowtownCommands {
    private static final String LOCATE_NODE = "locate";
    private static final String STRUCTURE_NODE = "structure";
    private static final String SNOWTOWN_ID = "minetale:snowtown";
    // 搜索上限与原版 structure locate 的 100 个 random-spread 区域一致。
    private static final int MAX_SEARCH_AREA_RADIUS = 100;

    private SnowtownCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandNode<CommandSourceStack> locate = event.getDispatcher().getRoot().getChild(LOCATE_NODE);
        if (locate == null) {
            MineTale.LOGGER.error("[Snowtown] 原版命令节点 /{} 不存在，跳过 locate 注册。", LOCATE_NODE);
            return;
        }

        CommandNode<CommandSourceStack> structure = locate.getChild(STRUCTURE_NODE);
        if (structure == null) {
            MineTale.LOGGER.error(
                    "[Snowtown] 原版命令节点 /{} {} 不存在，跳过 locate 注册。",
                    LOCATE_NODE,
                    STRUCTURE_NODE
            );
            return;
        }
        if (structure.getChild(SNOWTOWN_ID) != null) {
            MineTale.LOGGER.error("[Snowtown] 子命令 {} 已存在，跳过注册（禁止覆盖）。", SNOWTOWN_ID);
            return;
        }

        structure.addChild(Commands.literal(SNOWTOWN_ID)
                .executes(SnowtownCommands::locateSnowtown)
                .build());
    }

    private static int locateSnowtown(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getLevel().getChunkSource().getGenerator() instanceof UndergroundNoiseGenerator generator)) {
            return notFound(source);
        }

        BlockPos origin = BlockPos.containing(source.getPosition());
        WorldgenSamplingContext sampling = new WorldgenSamplingContext(
                source.getLevel().getSeed(),
                generator.samplingSettings()
        );
        return SnowtownLocator.findNearest(
                        sampling,
                        source.getLevel().getMinY(),
                        source.getLevel().getMaxY(),
                        origin,
                        MAX_SEARCH_AREA_RADIUS
                )
                .map(result -> showLocateResult(source, result))
                .orElseGet(() -> notFound(source));
    }

    private static int notFound(CommandSourceStack source) {
        source.sendFailure(Component.translatable("commands.locate.structure.not_found", SNOWTOWN_ID));
        return 0;
    }

    private static int showLocateResult(CommandSourceStack source, SnowtownLocator.Result result) {
        BlockPos target = result.position();
        int distance = Mth.floor(Mth.sqrt((float) result.distanceSqr()));
        Component coordinates = ComponentUtils.wrapInSquareBrackets(
                Component.translatable("chat.coordinates", target.getX(), "~", target.getZ())
        ).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand(
                        "/tp @s " + target.getX() + " ~ " + target.getZ()
                ))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip")))
        );
        source.sendSuccess(
                () -> Component.translatable(
                        "commands.locate.structure.success",
                        SNOWTOWN_ID,
                        coordinates,
                        distance
                ),
                false
        );
        return distance;
    }
}
