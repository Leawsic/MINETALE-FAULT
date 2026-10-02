package cn.jehorstudio.minetale.contentpack.client;

import cn.jehorstudio.minetale.contentpack.ContentPackActiveProfile;
import cn.jehorstudio.minetale.contentpack.ContentPackRepository;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.List;

// 仅展示安装库；当前单人世界的启用与排序交给原生数据包界面。
public final class ContentPackManagementScreen extends Screen {
    private static final int BUTTON_WIDTH = 150;
    private final Screen parent;

    public ContentPackManagementScreen(Screen parent) {
        super(Component.translatable("screen.minetale.content_pack.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ContentPackRepository.LibraryInspection inspection = ContentPackRepository.inspectLibrary();
        MultiLineTextWidget summary = new MultiLineTextWidget(
                24, 42, buildSummary(inspection), this.font)
                .setMaxWidth(Math.max(100, this.width - 48))
                .setMaxRows(Math.max(6, (this.height - 112) / 9));
        this.addRenderableWidget(summary);

        int rowY = this.height - 52;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.minetale.content_pack.open_folder"),
                ignored -> Util.getPlatform().openPath(ContentPackRepository.libraryPath()))
                .bounds(this.width / 2 - BUTTON_WIDTH - 4, rowY, BUTTON_WIDTH, 20)
                .build());

        Button manage = Button.builder(
                Component.translatable("screen.minetale.content_pack.manage_world"),
                ignored -> openWorldPackSelection())
                .bounds(this.width / 2 + 4, rowY, BUTTON_WIDTH, 20)
                .build();
        manage.active = this.minecraft != null && this.minecraft.getSingleplayerServer() != null;
        if (!manage.active) {
            manage.setTooltip(Tooltip.create(Component.translatable("screen.minetale.content_pack.manage_world.unavailable")));
        }
        this.addRenderableWidget(manage);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, ignored -> onClose())
                .bounds(this.width / 2 - 75, this.height - 27, 150, 20)
                .build());
    }

    private Component buildSummary(ContentPackRepository.LibraryInspection inspection) {
        IntegratedServer server = this.minecraft == null ? null : this.minecraft.getSingleplayerServer();
        List<String> activeOrder = server == null ? List.of() : server.getPackRepository().getSelectedPacks().stream()
                .map(Pack::getId)
                .filter(id -> id.startsWith(ContentPackRepository.PACK_ID_PREFIX))
                .toList();
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable(
                "screen.minetale.content_pack.summary",
                inspection.installed().size(),
                inspection.rejected().size()).getString());
        lines.add(Component.translatable(server == null
                ? "screen.minetale.content_pack.authority.offline"
                : "screen.minetale.content_pack.authority.local").getString());
        lines.add("");
        for (ContentPackRepository.InstalledPack installed : inspection.installed()) {
            int activeIndex = activeOrder.indexOf(installed.repositoryId());
            String state = activeIndex >= 0
                    ? Component.translatable("screen.minetale.content_pack.state.active", activeIndex + 1).getString()
                    : Component.translatable("screen.minetale.content_pack.state.inactive").getString();
            lines.add("[" + state + "] " + ContentPackRepository.packTitle(installed.manifest()).getString() + "  "
                    + installed.manifest().contentPackId() + "@" + installed.manifest().version());
        }
        for (ContentPackRepository.RejectedPack rejected : inspection.rejected()) {
            lines.add(Component.translatable(
                    "screen.minetale.content_pack.state.rejected",
                    rejected.path().getFileName(),
                    rejected.reason()).getString());
        }
        if (inspection.installed().isEmpty() && inspection.rejected().isEmpty()) {
            lines.add(Component.translatable("screen.minetale.content_pack.empty").getString());
        }
        return Component.literal(String.join("\n", lines));
    }

    private void openWorldPackSelection() {
        Minecraft client = this.minecraft;
        IntegratedServer server = client == null ? null : client.getSingleplayerServer();
        if (client == null || server == null) return;

        PackRepository repository = server.getPackRepository();
        List<String> previous = repository.getSelectedPacks().stream().map(Pack::getId).toList();
        repository.reload();
        client.setScreen(new PackSelectionScreen(
                repository,
                selected -> applyWorldSelection(client, server, selected, previous),
                ContentPackRepository.libraryPath(),
                Component.translatable("screen.minetale.content_pack.profile_title")));
    }

    private void applyWorldSelection(
            Minecraft client,
            IntegratedServer server,
            PackRepository repository,
            List<String> previous
    ) {
        List<String> requested = repository.getSelectedPacks().stream().map(Pack::getId).toList();
        client.setScreen(this);
        server.reloadResources(requested).whenComplete((ignored, failure) -> client.execute(() -> {
            if (failure == null) {
                try {
                    ContentPackActiveProfile.save(server);
                    SystemToast.add(
                            client.getToastManager(),
                            SystemToast.SystemToastId.WORLD_BACKUP,
                            Component.translatable("screen.minetale.content_pack.reload_success"),
                            null);
                    client.setScreen(new ContentPackManagementScreen(parent));
                } catch (java.io.IOException exception) {
                    SystemToast.addOrUpdate(
                            client.getToastManager(),
                            SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                            Component.translatable("screen.minetale.content_pack.profile_save_failed"),
                            Component.literal(readableMessage(exception)));
                }
            } else {
                repository.setSelected(previous);
                SystemToast.addOrUpdate(
                        client.getToastManager(),
                        SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                        Component.translatable("screen.minetale.content_pack.reload_failed"),
                        Component.literal(readableMessage(failure)));
            }
        }));
    }

    private static String readableMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, -1);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
