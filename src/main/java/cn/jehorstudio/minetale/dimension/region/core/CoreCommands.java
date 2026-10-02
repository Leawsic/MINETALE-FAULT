package cn.jehorstudio.minetale.dimension.region.core;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Core 的服务端放置指令：/minetale core load [scene] position 与 /minetale core clear。
 * 缺省场景为 minetale:core；坐标向下取整到方块。
 */
public final class CoreCommands {
    public static final ResourceLocation DEFAULT_SCENE =
            ResourceLocation.fromNamespaceAndPath("minetale", "core");

    private CoreCommands() {}

    public static void register(RegisterCommandsEvent event) {
        // 先注册纯坐标形式，匹配顺序决定 "load 0 64 0" 与 "load minetale:core 0 64 0" 都可用。
        var load = Commands.literal("load").then(position(DEFAULT_SCENE)).then(
                Commands.argument("scene", ResourceLocationArgument.id()).then(position(null)));
        event.getDispatcher().register(
                Commands.literal("minetale").then(
                        Commands.literal("core")
                                .requires(source -> source.hasPermission(2))
                                .then(load)
                                .then(Commands.literal("clear").executes(CoreCommands::clear))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Coordinates> position(ResourceLocation fixed) {
        return Commands.argument("position", Vec3Argument.vec3(false)).executes(context -> load(context, fixed));
    }

    private static int load(CommandContext<CommandSourceStack> context, ResourceLocation fixed) {
        CommandSourceStack source = context.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;
        ResourceLocation scene = fixed != null ? fixed : ResourceLocationArgument.getId(context, "scene");
        Vec3 position = Vec3Argument.getVec3(context, "position");
        CoreRegionManager manager = CoreRegionManager.of(level);
        boolean replaced = manager.placement() != null;
        manager.generateCore(scene, position.x, position.y, position.z);
        source.sendSuccess(
                () -> Component.literal(
                        (replaced ? "已卸载原 Core 并重新生成：" : "Core 已生成：")
                                + manager.placement()
                                + "，碰撞生成中并写入地图数据"),
                true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getLevel() instanceof ServerLevel level)) return 0;
        CoreRegionManager manager = CoreRegionManager.of(level);
        if (manager.placement() == null) {
            source.sendFailure(Component.literal("当前维度没有已放置的 Core。"));
            return 0;
        }
        manager.removeCore();
        source.sendSuccess(() -> Component.literal("Core 已卸载，碰撞已清除。"), true);
        return 1;
    }
}
