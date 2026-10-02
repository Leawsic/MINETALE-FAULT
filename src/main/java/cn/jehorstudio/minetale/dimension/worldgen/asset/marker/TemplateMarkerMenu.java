package cn.jehorstudio.minetale.dimension.worldgen.asset.marker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

public class TemplateMarkerMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final TemplateMarkerData data;
    private final Direction facing;
    private final MarkerVisual visual;
    private final ContainerLevelAccess access;

    public TemplateMarkerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(
                containerId,
                inventory,
                extraData.readBlockPos(),
                TemplateMarkerData.readFromBuffer(extraData),
                extraData.readEnum(Direction.class),
                MarkerVisual.byName(extraData.readUtf()),
                ContainerLevelAccess.NULL
        );
    }

    public TemplateMarkerMenu(
            int containerId,
            Inventory inventory,
            BlockPos pos,
            TemplateMarkerData data,
            Direction facing,
            MarkerVisual visual,
            ContainerLevelAccess access
    ) {
        super(TemplateMarkerRegistry.TEMPLATE_MARKER_MENU.get(), containerId);
        this.pos = pos;
        this.data = data.copy();
        this.facing = facing;
        this.visual = visual;
        this.access = access;
    }

    public BlockPos pos() {
        return this.pos;
    }

    public TemplateMarkerData data() {
        return this.data.copy();
    }

    public Direction facing() {
        return this.facing;
    }

    public MarkerVisual visual() {
        return this.visual;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int quickMovedSlotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return AbstractContainerMenu.stillValid(this.access, player, TemplateMarkerRegistry.TEMPLATE_MARKER.get());
    }
}
