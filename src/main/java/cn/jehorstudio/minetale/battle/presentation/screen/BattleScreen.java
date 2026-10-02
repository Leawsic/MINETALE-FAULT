package cn.jehorstudio.minetale.battle.presentation.screen;

import cn.jehorstudio.minetale.battle.presentation.BattlePresentation;
import cn.jehorstudio.minetale.battle.presentation.VisualConfig;
import cn.jehorstudio.minetale.battle.presentation.screen.render.Renderer;
import cn.jehorstudio.minetale.battle.network.payload.BattleResultState;
import cn.jehorstudio.minetale.battle.presentation.BattleKeyMappings;
import cn.jehorstudio.minetale.battle.logic.input.BattleInputKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class BattleScreen extends Screen {
    // 单一开关同时禁用调参快捷键与界面重建。
    private static final boolean TEMP_VISUAL_EFFECTS_TUNING_ENABLED = true;
    private final BattlePresentation presentation;
    private boolean tuningPanelOpen;
    private VisualEffectsTuningPanel tuningPanel;

    public BattleScreen(BattlePresentation presentation) {
        super(Component.literal("Battle"));
        this.presentation = Objects.requireNonNull(presentation, "presentation");
    }

    public BattlePresentation presentation() {
        return this.presentation;
    }

    @Override
    protected void init() {
        super.init();
        this.presentation.clearInput();
        if (TEMP_VISUAL_EFFECTS_TUNING_ENABLED && this.tuningPanelOpen) {
            openTuningPanel();
        }
    }

    @Override
    @ParametersAreNonnullByDefault
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Battle Renderer 自行提交背景，不要在这里绘制背景。
    }

    @Override
    @ParametersAreNonnullByDefault
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        BattlePresentation.RenderSnapshot snapshot = this.presentation.captureRenderSnapshot();
        graphics.submitPictureInPictureRenderState(
                new Renderer.State(
                        0,
                        0,
                        this.width,
                        this.height,
                        1.0F,
                        graphics.peekScissorStack(),
                        this.presentation.instance().battleId(),
                        snapshot.scene(),
                        snapshot.effects(),
                        snapshot.environment()
                )
        );
        graphics.nextStratum();
        if (this.tuningPanelOpen && this.tuningPanel != null) {
            int panelWidth = VisualEffectsTuningPanel.panelWidth(this.width);
            graphics.fill(4, 4, 4 + panelWidth, VisualEffectsTuningPanel.HEADER_HEIGHT, 0xDC101114);
            graphics.drawString(
                    this.font,
                    Component.literal("视效实时调参  ·  鼠标拖动  ·  H 关闭"),
                    12,
                    11,
                    0xFFFFFFFF
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.tuningPanel != null) {
            this.tuningPanel.tick();
        }
    }

    @Override
    public void removed() {
        if (this.tuningPanel != null) {
            this.tuningPanel.flush();
        } else {
            VisualConfig.flushLiveValues();
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (TEMP_VISUAL_EFFECTS_TUNING_ENABLED
                && BattleKeyMappings.OPEN_VISUAL_EFFECTS_TUNING.matches(event)) {
            toggleTuningPanel();
            return true;
        }
        // 面板只消费鼠标；Battle 键位优先。
        if (BattleKeyMappings.SWITCH_PROJECTION.matches(event)) {
            this.presentation.toggleProjection();
            return true;
        }
        if (BattleKeyMappings.SWITCH_SCENE.matches(event)) {
            this.presentation.toggleDebugSceneMode();
            return true;
        }
        if (BattleKeyMappings.TURN_LEFT.matches(event)) {
            this.presentation.rotateDebugCamera(-VisualConfig.DEBUG_ORBIT_ROTATION_STEP_DEGREES(), 0.0D);
            return true;
        }
        if (BattleKeyMappings.TURN_RIGHT.matches(event)) {
            this.presentation.rotateDebugCamera(VisualConfig.DEBUG_ORBIT_ROTATION_STEP_DEGREES(), 0.0D);
            return true;
        }
        if (BattleKeyMappings.TURN_UP.matches(event)) {
            this.presentation.rotateDebugCamera(0.0D, VisualConfig.DEBUG_ORBIT_ROTATION_STEP_DEGREES());
            return true;
        }
        if (BattleKeyMappings.TURN_DOWN.matches(event)) {
            this.presentation.rotateDebugCamera(0.0D, -VisualConfig.DEBUG_ORBIT_ROTATION_STEP_DEGREES());
            return true;
        }
        Set<String> battleInputKeys = battleInputKeys(event);
        if (!battleInputKeys.isEmpty()) {
            battleInputKeys.forEach(key -> this.presentation.setScreenInputKeyHeld(key, true));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        Set<String> battleInputKeys = battleInputKeys(event);
        if (!battleInputKeys.isEmpty()) {
            battleInputKeys.forEach(key -> this.presentation.setScreenInputKeyHeld(key, false));
            return true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        Set<String> battleInputKeys = battleInputKeys(event);
        if (battleInputKeys.isEmpty()) {
            return false;
        }
        battleInputKeys.forEach(key -> this.presentation.setScreenInputKeyHeld(key, true));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        Set<String> battleInputKeys = battleInputKeys(event);
        battleInputKeys.forEach(key -> this.presentation.setScreenInputKeyHeld(key, false));
        return super.mouseReleased(event) || !battleInputKeys.isEmpty();
    }

    private void toggleTuningPanel() {
        if (this.tuningPanelOpen) {
            closeTuningPanel();
        } else {
            this.tuningPanelOpen = true;
            openTuningPanel();
        }
    }

    private void openTuningPanel() {
        if (this.tuningPanel != null) {
            this.removeWidget(this.tuningPanel);
        }
        this.tuningPanel = this.addRenderableWidget(
                new VisualEffectsTuningPanel(this.minecraft, this.width, this.height)
        );
    }

    private void closeTuningPanel() {
        this.tuningPanelOpen = false;
        if (this.tuningPanel == null) {
            VisualConfig.flushLiveValues();
            return;
        }
        this.tuningPanel.flush();
        this.removeWidget(this.tuningPanel);
        this.tuningPanel = null;
    }

    private static Set<String> battleInputKeys(KeyEvent event) {
        var options = Minecraft.getInstance().options;
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (options.keyUp.matches(event)) keys.add(options.keyUp.getName());
        if (options.keyDown.matches(event)) keys.add(options.keyDown.getName());
        if (options.keyLeft.matches(event)) keys.add(options.keyLeft.getName());
        if (options.keyRight.matches(event)) keys.add(options.keyRight.getName());
        if (options.keyJump.matches(event)) keys.add(options.keyJump.getName());
        if (options.keyShift.matches(event)) keys.add(options.keyShift.getName());
        if (BattleKeyMappings.matchesConfirm(event)) keys.add(BattleInputKey.CONFIRM);
        if (BattleKeyMappings.matchesSkip(event)) keys.add(BattleInputKey.SKIP);
        return keys;
    }

    private static Set<String> battleInputKeys(MouseButtonEvent event) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (BattleKeyMappings.matchesConfirm(event)) keys.add(BattleInputKey.CONFIRM);
        if (BattleKeyMappings.matchesSkip(event)) keys.add(BattleInputKey.SKIP);
        return keys;
    }

    @Override
    public void onClose() {
        this.presentation.closeWithResult(BattleResultState.ESCAPED);
        super.onClose();
    }
}
