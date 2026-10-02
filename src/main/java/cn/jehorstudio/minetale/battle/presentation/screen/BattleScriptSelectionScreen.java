package cn.jehorstudio.minetale.battle.presentation.screen;

import cn.jehorstudio.minetale.battle.network.payload.DebugStartBattleRequestPayload;
import cn.jehorstudio.minetale.battle.script.BattleDefinition;
import cn.jehorstudio.minetale.battle.script.BattleScriptCatalog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.Comparator;

// 本地调试入口只展示已经完成加载与校验的 BattleScript。
public final class BattleScriptSelectionScreen extends Screen {
    private static final Component TITLE = Component.translatable("screen.minetale.battle_script_selection.title");
    private static final Component EMPTY = Component.translatable("screen.minetale.battle_script_selection.empty");
    private static final Component START = Component.translatable("screen.minetale.battle_script_selection.start");

    private final Screen previousScreen;
    private BattleDefinitionList definitionList;
    private Button startButton;

    public BattleScriptSelectionScreen(Screen previousScreen) {
        super(TITLE);
        this.previousScreen = previousScreen;
    }

    @Override
    protected void init() {
        this.definitionList = this.addRenderableWidget(new BattleDefinitionList());
        this.startButton = this.addRenderableWidget(Button.builder(START, button -> this.startSelectedBattle())
                .bounds(this.width / 2 - 154, this.height - 36, 150, 20)
                .build());
        this.startButton.active = false;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
                .bounds(this.width / 2 + 4, this.height - 36, 150, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (this.definitionList.children().isEmpty()) {
            graphics.drawCenteredString(this.font, EMPTY, this.width / 2, this.height / 2 - 5, 0xFFA0A0A0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.previousScreen);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void select(BattleDefinitionList.Entry entry) {
        this.definitionList.setSelected(entry);
        this.startButton.active = entry != null;
    }

    private void startSelectedBattle() {
        BattleDefinitionList.Entry selected = this.definitionList.getSelected();
        if (selected == null) {
            return;
        }
        this.minecraft.setScreen(null);
        ClientPacketDistributor.sendToServer(new DebugStartBattleRequestPayload(selected.id));
    }

    private final class BattleDefinitionList extends ObjectSelectionList<BattleDefinitionList.Entry> {
        private BattleDefinitionList() {
            super(BattleScriptSelectionScreen.this.minecraft, BattleScriptSelectionScreen.this.width,
                    BattleScriptSelectionScreen.this.height - 76, 32, 28);
            BattleScriptCatalog.definitions().stream()
                    .sorted(Comparator.comparing(BattleDefinition::id))
                    .map(definition -> new Entry(definition.id()))
                    .forEach(this::addEntry);
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, super.getRowWidth());
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final ResourceLocation id;
            private final Component fileName;
            private final Component resourcePath;

            private Entry(ResourceLocation id) {
                this.id = id;
                String path = id.getPath();
                int lastSlash = path.lastIndexOf('/');
                this.fileName = Component.literal(path.substring(lastSlash + 1) + ".json");
                this.resourcePath = Component.literal("data/" + id.getNamespace() + "/battles/" + path + ".json");
            }

            @Override
            public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean isHovering, float partialTick) {
                int x = this.getContentX() + 4;
                graphics.drawString(BattleScriptSelectionScreen.this.font, this.fileName, x, this.getContentY() + 2, 0xFFFFFFFF);
                graphics.drawString(BattleScriptSelectionScreen.this.font, this.resourcePath, x, this.getContentY() + 14, 0xFF808080);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
                BattleScriptSelectionScreen.this.select(this);
                if (isDoubleClick) {
                    BattleScriptSelectionScreen.this.startSelectedBattle();
                }
                return super.mouseClicked(event, isDoubleClick);
            }

            @Override
            public boolean keyPressed(KeyEvent event) {
                if (event.isSelection()) {
                    BattleScriptSelectionScreen.this.select(this);
                    BattleScriptSelectionScreen.this.startSelectedBattle();
                    return true;
                }
                return super.keyPressed(event);
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", this.resourcePath);
            }
        }
    }
}
