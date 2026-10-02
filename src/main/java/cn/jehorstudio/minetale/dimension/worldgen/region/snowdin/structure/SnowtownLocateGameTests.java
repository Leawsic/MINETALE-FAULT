package cn.jehorstudio.minetale.dimension.worldgen.region.snowdin.structure;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.dimension.worldgen.WorldgenSamplingContext;
import cn.jehorstudio.minetale.dimension.worldgen.network.settings.UndergroundSamplingSettings;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

// 锁定 locate 只返回真实 accepted lot 的服务端契约。
public final class SnowtownLocateGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, MineTale.MODID);
    private static final long WORLD_SEED = 0L;
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 319;
    private static final BlockPos SEARCH_ORIGIN = new BlockPos(0, 80, 2000);

    static {
        TEST_FUNCTIONS.register("snowtown_locate_targets_reachable_lot", () -> SnowtownLocateGameTests::verify);
    }

    private SnowtownLocateGameTests() {}

    public static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }

    private static void verify(GameTestHelper helper) {
        helper.succeedIf(() -> {
            var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
            CommandNode<CommandSourceStack> locate = dispatcher.getRoot().getChild("locate");
            CommandNode<CommandSourceStack> structure = locate == null ? null : locate.getChild("structure");
            if (structure == null || structure.getChild("minetale:snowtown") == null) {
                helper.fail("标准 Snowtown locate 子命令未注册");
            }
            ParseResults<CommandSourceStack> parsed = dispatcher.parse(
                    "locate structure minetale:snowtown",
                    helper.getLevel().getServer().createCommandSourceStack()
            );
            if (parsed.getReader().canRead() || parsed.getContext().getCommand() == null) {
                helper.fail("标准 Snowtown locate 语法未绑定可执行命令");
            }

            WorldgenSamplingContext sampling = new WorldgenSamplingContext(
                    WORLD_SEED,
                    UndergroundSamplingSettings.defaults()
            );
            SnowtownLocator.Result target = SnowtownLocator.findNearest(
                            sampling,
                            MIN_Y,
                            MAX_Y,
                            SEARCH_ORIGIN,
                            100
                    )
                    .orElseThrow(() -> helper.assertionException("规划器未找到 Snowtown accepted lot"));
            if (target.position().getX() < target.lot().bounds().minX()
                    || target.position().getX() > target.lot().bounds().maxX()
                    || target.position().getZ() < target.lot().bounds().minZ()
                    || target.position().getZ() > target.lot().bounds().maxZ()) {
                helper.fail("Snowtown locate 目标的水平坐标不在 accepted lot 内: " + target.position());
            }
        });
    }
}
