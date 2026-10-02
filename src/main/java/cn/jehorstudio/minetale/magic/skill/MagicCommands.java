package cn.jehorstudio.minetale.magic.skill;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.function.BiConsumer;

final class MagicCommands {
    private MagicCommands() {}

    static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("magic")
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.entity()).requires(s -> s.hasPermission(2))
                                .executes(ctx -> status(ctx.getSource(), EntityArgument.getEntity(ctx, "target")))))
                .then(Commands.literal("select")
                        .then(Commands.argument("skill", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(MagicCatalog.skills(), builder))
                                .executes(ctx -> result(ctx.getSource(), MagicCasting.select(ctx.getSource().getPlayerOrException(), ResourceLocationArgument.getId(ctx, "skill"))))))
                .then(Commands.literal("mana").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, 1_000_000))
                                        .executes(ctx -> {
                                            var targets = EntityArgument.getEntities(ctx, "targets");
                                            targets.forEach(e -> MagicCasting.setMana(e, DoubleArgumentType.getDouble(ctx, "amount")));
                                            return changed(ctx.getSource(), targets);
                                        }))))
                .then(Commands.literal("configure_mana").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("maximum", DoubleArgumentType.doubleArg(0, 1_000_000))
                                        .then(Commands.argument("regeneration", DoubleArgumentType.doubleArg(0, 100_000))
                                                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0, 1000))
                                                        .executes(ctx -> {
                                                            var targets = EntityArgument.getEntities(ctx, "targets");
                                                            targets.forEach(e -> MagicCasting.configureMana(e, DoubleArgumentType.getDouble(ctx, "maximum"),
                                                                    DoubleArgumentType.getDouble(ctx, "regeneration"), DoubleArgumentType.getDouble(ctx, "multiplier")));
                                                            return changed(ctx.getSource(), targets);
                                                        }))))));
        root.then(grantTree("grant", MagicCasting::grantSchool, MagicCasting::grantSkill));
        root.then(grantTree("revoke", MagicCasting::revokeSchool, MagicCasting::revokeSkill));
        for (String action : new String[]{"begin", "cast", "select_for"}) {
            root.then(Commands.literal(action).requires(s -> s.hasPermission(2))
                    .then(Commands.argument("targets", EntityArgument.entities())
                            .then(Commands.argument("skill", ResourceLocationArgument.id())
                                    .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(MagicCatalog.skills(), b))
                                    .executes(ctx -> {
                                        int success = 0;
                                        for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
                                            ResourceLocation id = ResourceLocationArgument.getId(ctx, "skill");
                                            Skill.Result value = action.equals("select_for") ? MagicCasting.select(entity, id) : MagicCasting.begin(entity, id);
                                            if (value == Skill.Result.SUCCESS && action.equals("cast") && MagicCasting.snapshot(entity).casting()) {
                                                value = MagicCasting.release(entity);
                                            }
                                            success += result(ctx.getSource(), value);
                                        }
                                        return success;
                                    }))));
        }
        for (String action : new String[]{"release", "cancel"}) {
            root.then(Commands.literal(action).requires(s -> s.hasPermission(2))
                    .then(Commands.argument("targets", EntityArgument.entities()).executes(ctx -> {
                        int count = 0;
                        for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
                            if (action.equals("cancel")) { MagicCasting.cancel(entity); count++; }
                            else count += result(ctx.getSource(), MagicCasting.release(entity));
                        }
                        return count;
                    })));
        }
        event.getDispatcher().register(Commands.literal("minetale").then(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> grantTree(String action,
            BiConsumer<Entity, ResourceLocation> schools, BiConsumer<Entity, ResourceLocation> skills) {
        var root = Commands.literal(action).requires(s -> s.hasPermission(2));
        for (String kind : new String[]{"school", "skill"}) {
            root.then(Commands.literal(kind).then(Commands.argument("targets", EntityArgument.entities())
                    .then(Commands.argument("id", ResourceLocationArgument.id())
                            .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                                    kind.equals("school") ? MagicCatalog.schools() : MagicCatalog.skills(), b))
                            .executes(ctx -> {
                                ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                if (!(kind.equals("school") ? MagicCatalog.schools() : MagicCatalog.skills()).contains(id)) {
                                    return result(ctx.getSource(), Skill.Result.UNAVAILABLE);
                                }
                                var targets = EntityArgument.getEntities(ctx, "targets");
                                targets.forEach(entity -> (kind.equals("school") ? schools : skills).accept(entity, id));
                                return changed(ctx.getSource(), targets);
                            }))));
        }
        return root;
    }

    private static int status(CommandSourceStack source, Entity target) {
        MagicCasting.Snapshot state = MagicCasting.snapshot(target);
        source.sendSuccess(() -> Component.literal(target.getName().getString() + "：魔力 " + state.mana() + "/" + state.maximum()
                + "，恢复 " + state.regeneration() + "/s，倍率 " + state.costMultiplier() + "，技能 " + state.skills()
                + "，流派 " + state.schools() + "，选择 " + state.selected() + "，蓄力 " + state.progress()), false);
        return 1;
    }

    private static int result(CommandSourceStack source, Skill.Result result) {
        Component message = Component.translatable("magic.minetale.result." + result.name().toLowerCase(java.util.Locale.ROOT));
        if (result == Skill.Result.SUCCESS) source.sendSuccess(() -> message, false);
        return result == Skill.Result.SUCCESS ? 1 : 0;
    }

    private static int changed(CommandSourceStack source, Collection<? extends Entity> targets) {
        source.sendSuccess(() -> Component.literal("已更新 " + targets.size() + " 个实体的魔法状态"), false);
        return targets.size();
    }
}
