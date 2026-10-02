package cn.jehorstudio.minetale.narrative.client;

import cn.jehorstudio.minetale.narrative.network.payload.DialogueAdvancePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueChoicePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueChoiceSelectPayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialoguePagePayload;
import cn.jehorstudio.minetale.battle.presentation.BattleKeyMappings;
import cn.jehorstudio.minetale.lib.client.sound.ClientSoundPlayback;
import cn.jehorstudio.minetale.content.sound.MineTaleSoundEvents;
import cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueSoundSet;
import cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueSoundSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// 在固定 640×360 画布上呈现对话；跳过只完成显字，全文出现后才开放推进或选项输入。
public final class DialogueScreen extends Screen {
    private static final int VIRTUAL_WIDTH = 640;
    private static final int VIRTUAL_HEIGHT = 360;
    private static final int BOX_X = 32;
    private static final int BOX_WIDTH = 576;
    private static final int BOX_HEIGHT = 120;
    private static final int BOX_TOP_Y = 16;
    private static final int BOX_BOTTOM_Y = 224;
    private static final int BORDER = 4;
    private static final int PADDING = 16;
    private static final int PORTRAIT_SIZE = 80;
    private static final int PORTRAIT_GAP = 16;
    private static final float TEXT_SCALE = 1.5F;
    private static final int TEXT_INDENT = 12;
    private static final int LINE_GAP = 3;
    private static final int SOUL_SIZE = 8;
    private static final int SOUL_TEXTURE_SIZE = 16;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final ResourceLocation SOUL_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "minetale",
            "textures/gui/battle/heart.png"
    );

    private Presentation presentation;
    private List<List<String>> graphemesByLine;
    private int totalGraphemes;
    private long revealStartedNanos;
    private boolean revealForced;
    private boolean advanceSent;
    private int completedTicks;
    private int selectedOption;
    private int soundedGraphemes;
    private final RandomSource soundRandom = RandomSource.create();
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;

    public DialogueScreen(DialoguePagePayload page) {
        super(Component.literal("Dialogue"));
        showPage(page);
    }

    public DialogueScreen(DialogueChoicePayload choice) {
        super(Component.literal("Dialogue"));
        showChoice(choice);
    }

    public UUID sessionId() {
        return this.presentation.sessionId();
    }

    public void showPage(DialoguePagePayload nextPage) {
        setPresentation(Presentation.page(Objects.requireNonNull(nextPage, "nextPage")));
    }

    public void showChoice(DialogueChoicePayload nextChoice) {
        setPresentation(Presentation.choice(Objects.requireNonNull(nextChoice, "nextChoice")));
    }

    private void setPresentation(Presentation next) {
        this.presentation = next;
        List<List<String>> split = new ArrayList<>(next.lines().size());
        int count = 0;
        for (String line : next.lines()) {
            List<String> graphemes = DialogueText.graphemes(line);
            split.add(graphemes);
            count += graphemes.size();
        }
        this.graphemesByLine = List.copyOf(split);
        this.totalGraphemes = count;
        this.revealStartedNanos = System.nanoTime();
        this.revealForced = false;
        this.advanceSent = false;
        this.completedTicks = 0;
        this.selectedOption = 0;
        this.soundedGraphemes = 0;
        this.lastMouseX = Double.NaN;
        this.lastMouseY = Double.NaN;
        next.sound().choosePerPage(this.soundRandom).ifPresent(this::playDialogueSound);
    }

    @Override
    public void tick() {
        super.tick();
        updateGraphemeSounds();
        if (!fullyRevealed()) {
            this.completedTicks = 0;
            return;
        }
        if (this.presentation.automatic() && !this.advanceSent) {
            this.completedTicks++;
            if (this.completedTicks >= this.presentation.autoDelayTicks()) {
                requestAdvance();
            }
        }
    }

    @Override
    @ParametersAreNonnullByDefault
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 对话不遮蔽游戏世界，黑边只填充虚拟画布之外的宽高比余量。
    }

    @Override
    @ParametersAreNonnullByDefault
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float scale = Math.min(this.width / (float) VIRTUAL_WIDTH, this.height / (float) VIRTUAL_HEIGHT);
        float canvasWidth = VIRTUAL_WIDTH * scale;
        float canvasHeight = VIRTUAL_HEIGHT * scale;
        float offsetX = (this.width - canvasWidth) * 0.5F;
        float offsetY = (this.height - canvasHeight) * 0.5F;

        drawLetterbox(graphics, offsetX, offsetY, canvasWidth, canvasHeight);
        graphics.pose().pushMatrix();
        graphics.pose().translate(offsetX, offsetY);
        graphics.pose().scale(scale, scale);
        renderDialogueBox(graphics);
        graphics.pose().popMatrix();
    }

    private void drawLetterbox(
            GuiGraphics graphics,
            float offsetX,
            float offsetY,
            float canvasWidth,
            float canvasHeight
    ) {
        if (offsetX > 0.0F) {
            graphics.fill(0, 0, (int) Math.ceil(offsetX), this.height, BLACK);
            graphics.fill((int) Math.floor(offsetX + canvasWidth), 0, this.width, this.height, BLACK);
        }
        if (offsetY > 0.0F) {
            graphics.fill(0, 0, this.width, (int) Math.ceil(offsetY), BLACK);
            graphics.fill(0, (int) Math.floor(offsetY + canvasHeight), this.width, this.height, BLACK);
        }
    }

    private void renderDialogueBox(GuiGraphics graphics) {
        int boxY = this.presentation.top() ? BOX_TOP_Y : BOX_BOTTOM_Y;
        graphics.fill(BOX_X, boxY, BOX_X + BOX_WIDTH, boxY + BOX_HEIGHT, WHITE);
        graphics.fill(
                BOX_X + BORDER,
                boxY + BORDER,
                BOX_X + BOX_WIDTH - BORDER,
                boxY + BOX_HEIGHT - BORDER,
                BLACK
        );

        int contentX = BOX_X + BORDER + PADDING;
        int contentY = boxY + BORDER + PADDING;
        int contentWidth = BOX_WIDTH - 2 * (BORDER + PADDING);
        if (this.presentation.portrait().isPresent()) {
            renderPortrait(graphics, this.presentation.portrait().orElseThrow(), contentX, contentY);
            contentX += PORTRAIT_SIZE + PORTRAIT_GAP;
            contentWidth -= PORTRAIT_SIZE + PORTRAIT_GAP;
        }
        renderLines(graphics, contentX, contentY, contentWidth);
    }

    private void renderPortrait(GuiGraphics graphics, ResourceLocation portraitId, int x, int y) {
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                portraitId.getNamespace(),
                "textures/dialogue/portraits/" + portraitId.getPath() + ".png"
        );
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                x,
                y,
                0.0F,
                0.0F,
                PORTRAIT_SIZE,
                PORTRAIT_SIZE,
                PORTRAIT_SIZE,
                PORTRAIT_SIZE
        );
    }

    private void renderLines(GuiGraphics graphics, int x, int y, int width) {
        int remaining = revealedGraphemeCount();
        int bodyWidth = Math.max(1, (int) Math.floor(width / TEXT_SCALE) - TEXT_INDENT);
        int lineY = 0;
        int logicalIndex = 0;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(TEXT_SCALE, TEXT_SCALE);
        for (List<String> logicalLine : this.graphemesByLine) {
            int visibleCount = Math.min(remaining, logicalLine.size());
            if (visibleCount <= 0) {
                break;
            }
            String visibleText = String.join("", logicalLine.subList(0, visibleCount));
            if (this.presentation.choice()) {
                if (fullyRevealed() && logicalIndex == this.selectedOption) {
                    graphics.blit(
                            RenderPipelines.GUI_TEXTURED,
                            SOUL_TEXTURE,
                            0,
                            lineY,
                            0.0F,
                            0.0F,
                            SOUL_SIZE,
                            SOUL_SIZE,
                            SOUL_TEXTURE_SIZE,
                            SOUL_TEXTURE_SIZE,
                            SOUL_TEXTURE_SIZE,
                            SOUL_TEXTURE_SIZE
                    );
                }
            } else {
                graphics.drawString(this.font, "*", 0, lineY, WHITE, false);
            }
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal(visibleText), bodyWidth);
            for (FormattedCharSequence row : wrapped) {
                graphics.drawString(this.font, row, TEXT_INDENT, lineY, WHITE, false);
                lineY += this.font.lineHeight + LINE_GAP;
            }
            remaining -= visibleCount;
            if (visibleCount < logicalLine.size()) {
                break;
            }
            logicalIndex++;
        }
        graphics.pose().popMatrix();
    }

    private int revealedGraphemeCount() {
        if (this.revealForced) {
            return this.totalGraphemes;
        }
        double elapsedSeconds = (System.nanoTime() - this.revealStartedNanos) / 1_000_000_000.0D;
        return Math.min(
                this.totalGraphemes,
                (int) Math.floor(elapsedSeconds * this.presentation.charactersPerSecond())
        );
    }

    private boolean fullyRevealed() {
        return revealedGraphemeCount() >= this.totalGraphemes;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (matchesSkip(event)) {
            skipTextReveal();
            return true;
        }
        if (this.presentation.choice() && fullyRevealed()) {
            if (matchesPreviousOption(event)) {
                moveSelection(-1);
                return true;
            }
            if (matchesNextOption(event)) {
                moveSelection(1);
                return true;
            }
        }
        if (matchesAdvance(event)) {
            advanceOrConfirm();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (BattleKeyMappings.matchesConfirm(event)) {
            advanceOrConfirm();
            return true;
        }
        if (BattleKeyMappings.matchesSkip(event)) {
            skipTextReveal();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void skipTextReveal() {
        if (this.presentation.skippable() && !fullyRevealed()) {
            this.revealForced = true;
            updateGraphemeSounds();
        }
    }

    private void advanceOrConfirm() {
        if (!fullyRevealed()) {
            return;
        }
        if (this.presentation.choice()) {
            requestChoice();
        } else {
            requestAdvance();
        }
    }

    private void requestAdvance() {
        if (this.advanceSent) {
            return;
        }
        this.advanceSent = true;
        ClientPacketDistributor.sendToServer(new DialogueAdvancePayload(this.presentation.sessionId()));
    }

    private void requestChoice() {
        if (this.advanceSent) {
            return;
        }
        this.advanceSent = true;
        playUiSound(MineTaleSoundEvents.UI_CONFIRM);
        ClientPacketDistributor.sendToServer(new DialogueChoiceSelectPayload(
                this.presentation.sessionId(),
                this.presentation.stepId(),
                this.presentation.optionIds().get(this.selectedOption)
        ));
    }

    private void moveSelection(int delta) {
        int count = this.presentation.optionIds().size();
        this.selectedOption = Math.floorMod(this.selectedOption + delta, count);
        playUiSound(MineTaleSoundEvents.UI_SELECT);
    }

    private void updateGraphemeSounds() {
        if (this.presentation.sound().perGrapheme().isEmpty()) {
            return;
        }
        int revealed = revealedGraphemeCount();
        while (this.soundedGraphemes < revealed) {
            this.presentation.sound()
                    .choosePerGrapheme(this.soundRandom)
                    .ifPresent(this::playDialogueSound);
            this.soundedGraphemes++;
        }
    }

    private void playDialogueSound(DialogueSoundSpec sound) {
        ClientSoundPlayback.playRelative(
                sound.event(),
                sound.source(),
                sound.volumeMultiplier().sample(this.soundRandom),
                sound.pitchMultiplier().sample(this.soundRandom)
        );
    }

    private static void playUiSound(ResourceLocation sound) {
        ClientSoundPlayback.playRelative(sound, SoundSource.UI, 1.0D, 1.0D);
    }

    private static boolean matchesAdvance(KeyEvent event) {
        return BattleKeyMappings.matchesConfirm(event);
    }

    private static boolean matchesSkip(KeyEvent event) {
        return BattleKeyMappings.matchesSkip(event);
    }

    private static boolean matchesPreviousOption(KeyEvent event) {
        var options = Minecraft.getInstance().options;
        return options.keyUp.matches(event) || options.keyLeft.matches(event);
    }

    private static boolean matchesNextOption(KeyEvent event) {
        var options = Minecraft.getInstance().options;
        return options.keyDown.matches(event) || options.keyRight.matches(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (!this.presentation.cameraFree() || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        if (!Double.isNaN(this.lastMouseX)) {
            this.minecraft.player.turn(mouseX - this.lastMouseX, mouseY - this.lastMouseY);
        }
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean canInterruptWithAnotherScreen() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Presentation(
            UUID sessionId,
            String stepId,
            boolean top,
            Optional<ResourceLocation> portrait,
            boolean cameraFree,
            boolean skippable,
            boolean automatic,
            int autoDelayTicks,
            double charactersPerSecond,
            DialogueSoundSet sound,
            List<String> lines,
            boolean choice,
            List<String> optionIds
    ) {
        private Presentation {
            portrait = Objects.requireNonNull(portrait, "portrait");
            sound = Objects.requireNonNull(sound, "sound");
            lines = List.copyOf(lines);
            optionIds = List.copyOf(optionIds);
        }

        private static Presentation page(DialoguePagePayload payload) {
            return new Presentation(
                    payload.sessionId(),
                    payload.pageId(),
                    payload.top(),
                    payload.portrait(),
                    payload.cameraFree(),
                    payload.skippable(),
                    payload.automatic(),
                    payload.autoDelayTicks(),
                    payload.charactersPerSecond(),
                    payload.sound(),
                    payload.lines(),
                    false,
                    List.of()
            );
        }

        private static Presentation choice(DialogueChoicePayload payload) {
            return new Presentation(
                    payload.sessionId(),
                    payload.choiceId(),
                    payload.top(),
                    payload.portrait(),
                    payload.cameraFree(),
                    payload.skippable(),
                    false,
                    -1,
                    payload.charactersPerSecond(),
                    payload.sound(),
                    payload.options().stream().map(DialogueChoicePayload.Option::literal).toList(),
                    true,
                    payload.options().stream().map(DialogueChoicePayload.Option::id).toList()
            );
        }
    }
}
