package cn.jehorstudio.minetale.battle.presentation;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.presentation.screen.render.BattleResourceSet;
import cn.jehorstudio.minetale.battle.presentation.screen.render.environment.EnvironmentCaptureService;
import cn.jehorstudio.minetale.battle.script.BattleDefinition;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

// 物理客户端唯一的准备会话；候选 Battle 通过环境捕获门后才能激活。
public final class BattlePreparation {
    public static final BattlePreparation INSTANCE = new BattlePreparation();

    private final AtomicReference<UUID> announcedBattleId = new AtomicReference<>();
    private volatile Candidate candidate;

    private BattlePreparation() {
    }

    public void beginInitial(UUID battleId) {
        Objects.requireNonNull(battleId, "battleId");
        if (battleId.equals(this.announcedBattleId.get())) {
            return;
        }
        this.announcedBattleId.set(battleId);
        MineTale.LOGGER.info("Battle Preparation 已登记: battle={}", battleId);
    }

    // 构造 PreparedBattle 并启动环境捕获。
    public void attach(
            Minecraft minecraft,
            BattlePresentation presentation,
            BattleDefinition definition
    ) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(presentation, "presentation");
        UUID battleId = presentation.instance().battleId();
        if (BattlePresentation.active() != null) {
            throw new IllegalStateException("已有激活 Battle，不能开始新的 Battle Preparation");
        }
        if (!battleId.equals(this.announcedBattleId.get())) {
            beginInitial(battleId);
        }
        abortCurrent(minecraft, "被新的 Battle Preparation 替换");
        BattleResourceSet resources = definition == null
                ? BattleResourceSet.EMPTY
                : BattleResourceSet.collect(definition);
        UUID captureId = EnvironmentCaptureService.INSTANCE.request(minecraft, battleId);
        PreparedBattle prepared = new PreparedBattle(presentation, resources, captureId);
        this.candidate = new Candidate(prepared);
        MineTale.LOGGER.info(
                "Battle Preparation 等待环境捕获: battle={}, textures={}, models={}, texts={}",
                battleId,
                resources.textures().size(),
                resources.models().size(),
                resources.texts().size()
        );
    }

    public void tick(Minecraft minecraft) {
        Candidate current = this.candidate;
        if (current == null) {
            return;
        }
        UUID battleId = current.prepared.presentation().instance().battleId();
        EnvironmentCaptureService.Outcome outcome = EnvironmentCaptureService.INSTANCE.outcome(
                battleId,
                current.prepared.environmentCaptureId()
        );
        if (outcome == EnvironmentCaptureService.Outcome.CAPTURING) {
            return;
        }
        this.candidate = null;
        this.announcedBattleId.compareAndSet(battleId, null);
        try {
            current.prepared.presentation().activate(minecraft, current.prepared);
            MineTale.LOGGER.info(
                    "Battle Preparation READY，已激活: battle={}, environment={}",
                    battleId,
                    outcome
            );
        } catch (RuntimeException exception) {
            EnvironmentCaptureService.INSTANCE.close(
                    minecraft,
                    battleId,
                    current.prepared.environmentCaptureId()
            );
            current.prepared.presentation().failPreparation(exception);
            MineTale.LOGGER.error("Battle Activation 失败: battle={}", battleId, exception);
        }
    }

    public boolean preparing() {
        return this.announcedBattleId.get() != null || this.candidate != null;
    }

    public BattlePresentation presentation() {
        Candidate current = this.candidate;
        return current == null ? null : current.prepared.presentation();
    }

    private void abortCurrent(Minecraft minecraft, String reason) {
        Candidate current = this.candidate;
        if (current == null) {
            return;
        }
        UUID battleId = current.prepared.presentation().instance().battleId();
        EnvironmentCaptureService.INSTANCE.close(
                minecraft,
                battleId,
                current.prepared.environmentCaptureId()
        );
        current.prepared.presentation().failPreparation(new IllegalStateException(reason));
        this.candidate = null;
        this.announcedBattleId.compareAndSet(battleId, null);
    }

    private record Candidate(PreparedBattle prepared) {
        private Candidate {
            Objects.requireNonNull(prepared, "prepared");
        }
    }
}
