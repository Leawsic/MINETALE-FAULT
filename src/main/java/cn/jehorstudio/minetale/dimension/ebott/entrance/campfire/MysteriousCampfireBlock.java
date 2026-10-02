package cn.jehorstudio.minetale.dimension.ebott.entrance.campfire;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;

// 复用原版营火外观与行为，但使用专属剪刀掉落和高烟柱粒子。
/* 为什么掉落剪刀？好难猜啊…… */
public final class MysteriousCampfireBlock extends CampfireBlock {
    private static final String USED_MARKER = "minetale:someone_used";
    private static final int SHEARS_REMAINING_DURABILITY = 2;
    private static final int UNREPAIRABLE_ANVIL_COST = 40;
    private static final double VANILLA_SMOKE_VERTICAL_SPEED = 0.07;

    public MysteriousCampfireBlock(BlockBehaviour.Properties properties) {
        super(true, 1, properties);
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        drops.add(createUsedShears());
        return drops;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> blockEntityType
    ) {
        if (!level.isClientSide() || !state.getValue(LIT)) {
            return super.getTicker(level, state, blockEntityType);
        }
        return createTickerHelper(
                blockEntityType,
                BlockEntityType.CAMPFIRE,
                MysteriousCampfireBlock::particleTick
        );
    }

    private static ItemStack createUsedShears() {
        ItemStack shears = new ItemStack(Items.SHEARS);
        shears.set(DataComponents.DAMAGE, shears.getMaxDamage() - SHEARS_REMAINING_DURABILITY);
        shears.set(DataComponents.REPAIR_COST, UNREPAIRABLE_ANVIL_COST);
        shears.remove(DataComponents.REPAIRABLE);
        CustomData.update(
                DataComponents.CUSTOM_DATA,
                shears,
                tag -> tag.putBoolean(USED_MARKER, true)
        );
        return shears;
    }

    private static void particleTick(
            Level level,
            BlockPos pos,
            BlockState state,
            CampfireBlockEntity blockEntity
    ) {
        RandomSource random = level.random;
        if (random.nextFloat() < 0.11F) {
            for (int i = 0; i < random.nextInt(2) + 2; i++) {
                makeTallSmokeParticle(level, pos, state.getValue(SIGNAL_FIRE));
            }
        }

        int facing = state.getValue(FACING).get2DDataValue();
        for (int slot = 0; slot < blockEntity.getItems().size(); slot++) {
            if (!blockEntity.getItems().get(slot).isEmpty() && random.nextFloat() < 0.2F) {
                Direction direction = Direction.from2DDataValue(Math.floorMod(slot + facing, 4));
                double x = pos.getX() + 0.5 - direction.getStepX() * 0.3125
                        + direction.getClockWise().getStepX() * 0.3125;
                double y = pos.getY() + 0.5;
                double z = pos.getZ() + 0.5 - direction.getStepZ() * 0.3125
                        + direction.getClockWise().getStepZ() * 0.3125;
                for (int i = 0; i < 4; i++) {
                    level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 5.0E-4, 0.0);
                }
            }
        }
    }

    private static void makeTallSmokeParticle(Level level, BlockPos pos, boolean signalFire) {
        RandomSource random = level.getRandom();
        var particle = signalFire
                ? MysteriousCampfireRegistry.SIGNAL_SMOKE.get()
                : MysteriousCampfireRegistry.COSY_SMOKE.get();
        level.addAlwaysVisibleParticle(
                particle,
                true,
                pos.getX() + 0.5 + random.nextDouble() / 3.0 * (random.nextBoolean() ? 1 : -1),
                pos.getY() + random.nextDouble() + random.nextDouble(),
                pos.getZ() + 0.5 + random.nextDouble() / 3.0 * (random.nextBoolean() ? 1 : -1),
                0.0,
                VANILLA_SMOKE_VERTICAL_SPEED,
                0.0
        );
    }
}
