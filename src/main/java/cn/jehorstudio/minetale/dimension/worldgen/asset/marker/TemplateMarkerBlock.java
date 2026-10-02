package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class TemplateMarkerBlock extends Block implements EntityBlock {
    public static final MapCodec<TemplateMarkerBlock> CODEC = simpleCodec(TemplateMarkerBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    public static final EnumProperty<MarkerVisual> VISUAL = EnumProperty.create("visual", MarkerVisual.class);

    public TemplateMarkerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(VISUAL, MarkerVisual.CONNECTOR));
    }

    public static BlockBehaviour.Properties markerProperties() {
        return BlockBehaviour.Properties.of()
                .strength(1.5f, 6.0f)
                .sound(SoundType.METAL)
                .noOcclusion();
    }

    @Override
    public MapCodec<TemplateMarkerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, VISUAL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getClickedFace())
                .setValue(VISUAL, MarkerVisual.CONNECTOR);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TemplateMarkerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return this.openEditor(state, level, pos, player);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        return this.openEditor(state, level, pos, player);
    }

    private InteractionResult openEditor(BlockState state, Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (!canEdit(player)) {
            player.displayClientMessage(Component.literal("Template Marker requires creative mode or operator permission."), false);
            return InteractionResult.SUCCESS;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof TemplateMarkerBlockEntity markerBlockEntity)) {
            return InteractionResult.PASS;
        }

        TemplateMarkerData data = markerBlockEntity.getData().copy();
        Direction facing = state.getValue(FACING);
        MarkerVisual visual = state.getValue(VISUAL);
        serverPlayer.openMenu(
                new SimpleMenuProvider(
                        (containerId, inventory, menuPlayer) -> new TemplateMarkerMenu(
                                containerId,
                                inventory,
                                pos,
                                data,
                                facing,
                                visual,
                                ContainerLevelAccess.create(level, pos)
                        ),
                        Component.translatable("screen.minetale.template_marker")
                ),
                buf -> {
                    buf.writeBlockPos(pos);
                    data.writeToBuffer(buf);
                    buf.writeEnum(facing);
                    buf.writeUtf(visual.getSerializedName());
                }
        );
        return InteractionResult.SUCCESS;
    }

    public static boolean canEdit(Player player) {
        return player.isCreative() || player.hasPermissions(2) || player.canUseGameMasterBlocks();
    }
}
