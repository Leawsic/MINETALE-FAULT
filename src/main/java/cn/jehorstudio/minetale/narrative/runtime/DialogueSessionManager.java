package cn.jehorstudio.minetale.narrative.runtime;

import cn.jehorstudio.minetale.MineTale;
import cn.jehorstudio.minetale.battle.network.server.BattleStartCoordinator;
import cn.jehorstudio.minetale.narrative.data.NarrativeCatalog;
import cn.jehorstudio.minetale.narrative.data.NarrativeModel;
import cn.jehorstudio.minetale.narrative.data.NarrativeSnapshot;
import cn.jehorstudio.minetale.narrative.data.NarrativeValidationException;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueClosePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialogueChoicePayload;
import cn.jehorstudio.minetale.narrative.network.payload.DialoguePagePayload;
import cn.jehorstudio.minetale.narrative.state.StoryStateStore;
import cn.jehorstudio.minetale.narrative.NarrativeAttachments;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.util.ULocale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.AddNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.AdvancePolicy;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BattleParticipantScope;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.BranchNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ChoiceNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ChoiceOption;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialoguePackage;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialoguePlacement;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.DialogueProfile;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.PageNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.RandomNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.RevealPolicy;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.RunNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.SequenceNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.SetNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.StartBattleNode;
import static cn.jehorstudio.minetale.narrative.data.NarrativeModel.ShuffleCycleNode;

@EventBusSubscriber(modid = MineTale.MODID)
public final class DialogueSessionManager {
    private static final int STEP_BUDGET_PER_TICK = 256;
    private static final long INACTIVE_SESSION_TIMEOUT_TICKS = 20L * 60L * 10L;
    private static final String PENDING_KEY = "pending_dialogues";

    private static final Map<UUID, Session> SESSIONS_BY_PLAYER = new LinkedHashMap<>();
    private static final Map<UUID, UUID> TARGET_RESERVATIONS = new HashMap<>();
    private static final Map<UUID, Map<ShuffleKey, Deque<ResourceLocation>>> SHUFFLE_HISTORY = new HashMap<>();

    private DialogueSessionManager() {
    }

    public enum StartResult {
        STARTED,
        BUSY,
        NO_MATCH,
        FAILED
    }

    public static StartResult tryStart(ServerPlayer player, Entity target, ResourceLocation profileId) {
        return tryStart(player, DialogueTargetContext.entity(target), profileId);
    }

    // NPC居民无需创建 Entity。
    public static StartResult tryStartVirtual(
            ServerPlayer player,
            UUID targetId,
            BlockPos targetPosition,
            ResourceLocation targetEntityTypeId,
            ResourceLocation profileId
    ) {
        return tryStart(
                player,
                DialogueTargetContext.virtual(
                        targetId,
                        targetPosition,
                        targetEntityTypeId,
                        player.level().dimension()
                ),
                profileId
        );
    }

    private static StartResult tryStart(
            ServerPlayer player,
            DialogueTargetContext target,
            ResourceLocation profileId
    ) {
        if (SESSIONS_BY_PLAYER.containsKey(player.getUUID())) {
            return StartResult.BUSY;
        }
        UUID sessionId = UUID.randomUUID();
        if (target.reservable()
                && TARGET_RESERVATIONS.putIfAbsent(target.id(), sessionId) != null) {
            return StartResult.BUSY;
        }

        NarrativeSnapshot snapshot = NarrativeCatalog.current();
        DialogueProfile profile = snapshot.profiles().get(profileId);
        if (profile == null) {
            releaseReservation(target, sessionId);
            MineTale.LOGGER.warn("Dialogue target {} references missing profile {}", target.id(), profileId);
            return StartResult.FAILED;
        }

        try {
            Optional<List<Frame>> pending;
            try {
                pending = loadPending(player, profileId, snapshot);
            } catch (RuntimeException invalidPending) {
                removePending(player, profileId);
                pending = Optional.empty();
                MineTale.LOGGER.warn(
                        "Discarded invalid pending dialogue for profile {} and player {}; selecting a new package",
                        profileId,
                        player.getGameProfile().name(),
                        invalidPending
                );
            }

            Session session;
            if (pending.isPresent()) {
                session = new Session(sessionId, player, target, profileId, pending.get());
                removePending(player, profileId);
            } else {
                Optional<ResourceLocation> selected = DialogueRuleSelector.select(
                        profile,
                        player,
                        target,
                        (ServerLevel) player.level()
                );
                if (selected.isEmpty()) {
                    releaseReservation(target, sessionId);
                    return StartResult.NO_MATCH;
                }
                DialoguePackage root = snapshot.requireDialoguePackage(selected.get(), player.getLanguage());
                session = new Session(
                        sessionId,
                        player,
                        target,
                        profileId,
                        List.of(new Frame(root.id(), root.body().id()))
                );
            }
            SESSIONS_BY_PLAYER.put(player.getUUID(), session);
            run(session, STEP_BUDGET_PER_TICK);
            return StartResult.STARTED;
        } catch (RuntimeException exception) {
            releaseReservation(target, sessionId);
            removePending(player, profileId);
            MineTale.LOGGER.error(
                    "Failed to start dialogue profile {} for player {}",
                    profileId,
                    player.getGameProfile().name(),
                    exception
            );
            return StartResult.FAILED;
        }
    }

    public static boolean isActive(ServerPlayer player) {
        return SESSIONS_BY_PLAYER.containsKey(player.getUUID());
    }

    public static void advance(ServerPlayer player, UUID sessionId) {
        Session session = SESSIONS_BY_PLAYER.get(player.getUUID());
        if (session == null || !session.id.equals(sessionId) || !session.waitingForPage) {
            return;
        }
        long now = player.level().getGameTime();
        if (now < session.earliestAdvanceTick) {
            return;
        }
        Frame pageFrame = session.frames.peek();
        if (pageFrame == null || !(node(session, pageFrame) instanceof PageNode)) {
            abort(session, "invalid_page_state", false);
            return;
        }
        session.frames.pop();
        session.waitingForPage = false;
        session.lastProgressTick = now;
        run(session, STEP_BUDGET_PER_TICK);
    }

    public static void selectChoice(ServerPlayer player, UUID sessionId, String choiceId, String optionId) {
        Session session = SESSIONS_BY_PLAYER.get(player.getUUID());
        if (session == null || !session.id.equals(sessionId) || !session.waitingForPage) {
            return;
        }
        long now = player.level().getGameTime();
        if (now < session.earliestAdvanceTick) {
            return;
        }
        Frame choiceFrame = session.frames.peek();
        if (choiceFrame == null || !(node(session, choiceFrame) instanceof ChoiceNode choice)
                || !choice.id().equals(choiceId) || choiceFrame.started) {
            abort(session, "invalid_choice_state", false);
            return;
        }

        try {
            ChoiceOption option = choice.requireOption(optionId);
            ResourceLocation selectedPackage = DialogueRuleSelector.select(
                    choiceFrame.packageId + "." + choice.id() + "." + option.id(),
                    option.plan(),
                    session.player,
                    session.target,
                    (ServerLevel) session.player.level()
            ).orElseThrow(() -> new NarrativeValidationException(
                    "Choice " + choice.id() + " option " + option.id() + " produced no result."
            ));
            choiceFrame.started = true;
            choiceFrame.selectedPackage = selectedPackage;
            session.waitingForPage = false;
            session.lastProgressTick = now;
            pushPackage(session, selectedPackage);
            run(session, STEP_BUDGET_PER_TICK);
        } catch (RuntimeException exception) {
            MineTale.LOGGER.error(
                    "Dialogue choice failed. session={}, choice={}, option={}",
                    session.id,
                    choiceId,
                    optionId,
                    exception
            );
            abort(session, "choice_error", false);
        }
    }

    public static void interrupt(ServerPlayer player, String reason) {
        Session session = SESSIONS_BY_PLAYER.get(player.getUUID());
        if (session != null) {
            abort(session, reason, true);
        }
    }

    // 世界实体协调器只观察会话占用
    public static boolean isTargetReserved(Entity target) {
        return TARGET_RESERVATIONS.containsKey(target.getUUID());
    }

    public static void interruptAll(String reason) {
        for (Session session : List.copyOf(SESSIONS_BY_PLAYER.values())) {
            abort(session, reason, true);
        }
    }

    private static void run(Session session, int budget) {
        try {
            int remaining = budget;
            while (!session.waitingForPage && remaining-- > 0) {
                if (session.frames.isEmpty()) {
                    complete(session);
                    return;
                }
                Frame frame = session.frames.peek();
                DialogueNode node = node(session, frame);
                switch (node) {
                    case SequenceNode sequence -> runSequence(session, frame, sequence);
                    case PageNode page -> showPage(session, page);
                    case ChoiceNode choice -> {
                        if (frame.started) {
                            session.frames.pop();
                        } else {
                            showChoice(session, choice);
                        }
                    }
                    case SetNode set -> {
                        StoryStateStore.set(session.player, set.state(), set.value());
                        session.frames.pop();
                    }
                    case AddNode add -> {
                        StoryStateStore.add(session.player, add.state(), add.value());
                        session.frames.pop();
                    }
                    case RunNode run -> runCall(session, frame, run.dialoguePackage());
                    case BranchNode branch -> runBranch(session, frame, branch);
                    case RandomNode random -> runRandom(session, frame, random);
                    case ShuffleCycleNode shuffle -> runShuffle(session, frame, shuffle);
                    case StartBattleNode startBattle -> {
                        runStartBattle(session, startBattle);
                        return;
                    }
                }
                session.lastProgressTick = session.player.level().getGameTime();
            }
        } catch (RuntimeException exception) {
            MineTale.LOGGER.error(
                    "Dialogue execution failed. session={}, profile={}, frame={}",
                    session.id,
                    session.profileId,
                    session.frames.peek(),
                    exception
            );
            abort(session, "execution_error", false);
        }
    }

    private static void runSequence(Session session, Frame frame, SequenceNode sequence) {
        if (frame.cursor >= sequence.children().size()) {
            session.frames.pop();
            return;
        }
        DialogueNode child = sequence.children().get(frame.cursor++);
        session.frames.push(new Frame(frame.packageId, child.id()));
    }

    private static void runCall(Session session, Frame frame, ResourceLocation packageId) {
        if (!frame.started) {
            frame.started = true;
            frame.selectedPackage = packageId;
            pushPackage(session, packageId);
        } else {
            session.frames.pop();
        }
    }

    private static void runBranch(Session session, Frame frame, BranchNode branch) {
        if (!frame.started) {
            frame.started = true;
            frame.selectedPackage = DialogueRuleSelector.select(
                    frame.packageId + "." + branch.id(),
                    branch.plan(),
                    session.player,
                    session.target,
                    (ServerLevel) session.player.level()
            ).orElse(null);
            if (frame.selectedPackage != null) {
                pushPackage(session, frame.selectedPackage);
            }
        } else {
            session.frames.pop();
        }
    }

    private static void runRandom(Session session, Frame frame, RandomNode random) {
        if (!frame.started) {
            frame.started = true;
            frame.selectedPackage = random.packages().get(
                    session.random.nextInt(random.packages().size())
            );
            pushPackage(session, frame.selectedPackage);
        } else {
            session.frames.pop();
        }
    }

    private static void runShuffle(Session session, Frame frame, ShuffleCycleNode shuffle) {
        if (!frame.started) {
            frame.started = true;
            ShuffleKey key = new ShuffleKey(frame.packageId, shuffle.id());
            Map<ShuffleKey, Deque<ResourceLocation>> playerHistory =
                    SHUFFLE_HISTORY.computeIfAbsent(session.player.getUUID(), ignored -> new HashMap<>());
            Deque<ResourceLocation> remaining = playerHistory.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            if (remaining.isEmpty()) {
                List<ResourceLocation> shuffled = new ArrayList<>(shuffle.packages());
                Collections.shuffle(shuffled, session.random);
                remaining.addAll(shuffled);
            }
            frame.selectedPackage = remaining.removeFirst();
            pushPackage(session, frame.selectedPackage);
        } else {
            session.frames.pop();
        }
    }

    private static void runStartBattle(Session session, StartBattleNode startBattle) {
        List<ServerPlayer> candidates;
        if (startBattle.participants() == BattleParticipantScope.SELF) {
            candidates = List.of(session.player);
        } else {
            double rangeSquared = startBattle.range() * startBattle.range();
            candidates = session.player.level().getServer().getPlayerList().getPlayers().stream()
                    .filter(candidate -> candidate.level() == session.player.level())
                    .filter(candidate -> candidate.distanceToSqr(session.player) <= rangeSquared)
                    .toList();
        }

        BattleStartCoordinator.StartResult result = BattleStartCoordinator.start(
                session.player,
                startBattle.battle(),
                candidates
        );
        if (result.started()) {
            complete(session);
            return;
        }
        MineTale.LOGGER.warn(
                "Dialogue could not start battle. session={}, definition={}, status={}",
                session.id,
                startBattle.battle(),
                result.status()
        );
        abort(session, "battle_start_failed", false);
    }

    private static void pushPackage(Session session, ResourceLocation packageId) {
        DialoguePackage dialoguePackage = NarrativeCatalog.current()
                .requireDialoguePackage(packageId, session.player.getLanguage());
        session.frames.push(new Frame(packageId, dialoguePackage.body().id()));
    }

    private static void showPage(Session session, PageNode page) {
        DialoguePackage dialoguePackage = NarrativeCatalog.current()
                .requireDialoguePackage(session.frames.peek().packageId, session.player.getLanguage());
        long now = session.player.level().getGameTime();
        long revealTicks = revealTicks(page);
        session.waitingForPage = true;
        session.earliestAdvanceTick = page.reveal() == RevealPolicy.UNSKIPPABLE
                ? now + revealTicks
                : now;
        session.lastProgressTick = now;
        PacketDistributor.sendToPlayer(session.player, new DialoguePagePayload(
                session.id,
                page.id(),
                dialoguePackage.placement() == DialoguePlacement.TOP,
                page.portrait(),
                dialoguePackage.cameraPolicy() == NarrativeModel.CameraPolicy.FREE,
                page.reveal() == RevealPolicy.SKIPPABLE,
                page.advance() == AdvancePolicy.AUTO,
                page.autoDelayTicks(),
                page.charactersPerSecond(),
                page.sound(),
                page.lines()
        ));
    }

    private static void showChoice(Session session, ChoiceNode choice) {
        DialoguePackage dialoguePackage = NarrativeCatalog.current()
                .requireDialoguePackage(session.frames.peek().packageId, session.player.getLanguage());
        long now = session.player.level().getGameTime();
        long revealTicks = revealTicks(choice.options().stream().map(ChoiceOption::literal).toList(), choice.charactersPerSecond());
        session.waitingForPage = true;
        session.earliestAdvanceTick = choice.reveal() == RevealPolicy.UNSKIPPABLE
                ? now + revealTicks
                : now;
        session.lastProgressTick = now;
        PacketDistributor.sendToPlayer(session.player, new DialogueChoicePayload(
                session.id,
                choice.id(),
                dialoguePackage.placement() == DialoguePlacement.TOP,
                choice.portrait(),
                dialoguePackage.cameraPolicy() == NarrativeModel.CameraPolicy.FREE,
                choice.reveal() == RevealPolicy.SKIPPABLE,
                choice.charactersPerSecond(),
                choice.sound(),
                choice.options().stream()
                        .map(option -> new DialogueChoicePayload.Option(option.id(), option.literal()))
                        .toList()
        ));
    }

    private static long revealTicks(PageNode page) {
        return revealTicks(page.lines(), page.charactersPerSecond());
    }

    private static long revealTicks(List<String> lines, double charactersPerSecond) {
        int graphemes = lines.stream().mapToInt(DialogueSessionManager::graphemeCount).sum();
        return (long) Math.ceil(graphemes / charactersPerSecond * 20.0D);
    }

    private static int graphemeCount(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(ULocale.ROOT);
        iterator.setText(text);
        int count = 0;
        for (int boundary = iterator.first(), next; (next = iterator.next()) != BreakIterator.DONE; boundary = next) {
            count++;
        }
        return count;
    }

    private static DialogueNode node(Session session, Frame frame) {
        return NarrativeCatalog.current()
                .requireDialoguePackage(frame.packageId, session.player.getLanguage())
                .requireNode(frame.nodeId);
    }

    private static void complete(Session session) {
        removePending(session.player, session.profileId);
        close(session, "completed");
    }

    private static void abort(Session session, String reason, boolean preserve) {
        if (preserve && !session.frames.isEmpty()) {
            savePending(session);
        } else {
            removePending(session.player, session.profileId);
        }
        close(session, reason);
    }

    private static void close(Session session, String reason) {
        SESSIONS_BY_PLAYER.remove(session.player.getUUID(), session);
        releaseReservation(session.target, session.id);
        if (session.player.connection != null) {
            PacketDistributor.sendToPlayer(session.player, new DialogueClosePayload(session.id, reason));
        }
    }

    private static void releaseReservation(UUID targetId, UUID sessionId) {
        TARGET_RESERVATIONS.remove(targetId, sessionId);
    }

    private static void releaseReservation(DialogueTargetContext target, UUID sessionId) {
        if (target.reservable()) {
            releaseReservation(target.id(), sessionId);
        }
    }

    private static void savePending(Session session) {
        CompoundTag pending = new CompoundTag();
        ListTag frames = new ListTag();
        Iterator<Frame> iterator = session.frames.descendingIterator();
        while (iterator.hasNext()) {
            Frame frame = iterator.next();
            CompoundTag tag = new CompoundTag();
            tag.putString("package", frame.packageId.toString());
            tag.putString("node", frame.nodeId);
            tag.putBoolean("started", frame.started);
            if (frame.selectedPackage != null) {
                tag.putString("selected_package", frame.selectedPackage.toString());
            }
            DialogueNode node = node(session, frame);
            if (node instanceof SequenceNode sequence) {
                if (frame.cursor >= sequence.children().size()) {
                    tag.putBoolean("sequence_done", true);
                } else {
                    tag.putString("next_child", sequence.children().get(frame.cursor).id());
                }
            }
            frames.add(tag);
        }
        pending.put("frames", frames);
        pendingContainer(session.player).put(session.profileId.toString(), pending);
    }

    private static Optional<List<Frame>> loadPending(
            ServerPlayer player,
            ResourceLocation profileId,
            NarrativeSnapshot snapshot
    ) {
        CompoundTag container = pendingContainer(player);
        if (!container.contains(profileId.toString())) {
            return Optional.empty();
        }
        CompoundTag pending = container.getCompoundOrEmpty(profileId.toString());
        ListTag tags = pending.getListOrEmpty("frames");
        if (tags.isEmpty()) {
            removePending(player, profileId);
            return Optional.empty();
        }

        List<Frame> frames = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            CompoundTag tag = tags.getCompoundOrEmpty(i);
            ResourceLocation packageId = ResourceLocation.tryParse(tag.getStringOr("package", ""));
            String nodeId = tag.getStringOr("node", "");
            if (packageId == null || nodeId.isBlank()) {
                throw new NarrativeValidationException("Pending dialogue contains an invalid frame.");
            }
            DialogueNode node = snapshot.requireDialoguePackage(packageId, player.getLanguage()).requireNode(nodeId);
            Frame frame = new Frame(packageId, nodeId);
            frame.started = tag.getBooleanOr("started", false);
            frame.selectedPackage = ResourceLocation.tryParse(tag.getStringOr("selected_package", ""));
            if (node instanceof SequenceNode sequence) {
                if (tag.getBooleanOr("sequence_done", false)) {
                    frame.cursor = sequence.children().size();
                } else {
                    String nextChild = tag.getStringOr("next_child", "");
                    frame.cursor = childIndex(sequence, nextChild);
                }
            }
            frames.add(frame);
        }
        return Optional.of(List.copyOf(frames));
    }

    private static int childIndex(SequenceNode sequence, String childId) {
        for (int index = 0; index < sequence.children().size(); index++) {
            if (sequence.children().get(index).id().equals(childId)) {
                return index;
            }
        }
        throw new NarrativeValidationException(
                "Sequence " + sequence.id() + " no longer contains next child " + childId + "."
        );
    }

    private static CompoundTag pendingContainer(ServerPlayer player) {
        CompoundTag root = player.getData(NarrativeAttachments.PLAYER_DATA.get());
        CompoundTag pending = root.getCompoundOrEmpty(PENDING_KEY);
        if (!root.contains(PENDING_KEY)) {
            root.put(PENDING_KEY, pending);
        }
        return pending;
    }

    private static void removePending(ServerPlayer player, ResourceLocation profileId) {
        pendingContainer(player).remove(profileId.toString());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (Session session : List.copyOf(SESSIONS_BY_PLAYER.values())) {
            if (!(session.player.level() instanceof ServerLevel level)
                    || !session.target.available(level)) {
                abort(session, "target_unavailable", true);
                continue;
            }
            long now = session.player.level().getGameTime();
            if (now - session.lastProgressTick > INACTIVE_SESSION_TIMEOUT_TICKS) {
                abort(session, "timeout", true);
                continue;
            }
            if (!session.waitingForPage) {
                run(session, STEP_BUDGET_PER_TICK);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDamaged(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getNewDamage() > 0.0F) {
            interrupt(player, "damaged");
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            interrupt(player, "death");
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            interrupt(player, "logout");
            SHUFFLE_HISTORY.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            interrupt(player, "dimension_changed");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        interruptAll("server_stopping");
        SHUFFLE_HISTORY.clear();
    }

    private static final class Session {
        private final UUID id;
        private final ServerPlayer player;
        private final DialogueTargetContext target;
        private final ResourceLocation profileId;
        private final Deque<Frame> frames = new ArrayDeque<>();
        private final Random random;
        private boolean waitingForPage;
        private long earliestAdvanceTick;
        private long lastProgressTick;

        private Session(
                UUID id,
                ServerPlayer player,
                DialogueTargetContext target,
                ResourceLocation profileId,
                List<Frame> bottomToTopFrames
        ) {
            this.id = id;
            this.player = player;
            this.target = target;
            this.profileId = profileId;
            for (Frame frame : bottomToTopFrames) {
                this.frames.addFirst(frame);
            }
            this.random = new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
            this.lastProgressTick = player.level().getGameTime();
        }
    }

    private static final class Frame {
        private final ResourceLocation packageId;
        private final String nodeId;
        private int cursor;
        private boolean started;
        private ResourceLocation selectedPackage;

        private Frame(ResourceLocation packageId, String nodeId) {
            this.packageId = packageId;
            this.nodeId = nodeId;
        }

        @Override
        public String toString() {
            return this.packageId + "#" + this.nodeId;
        }
    }

    private record ShuffleKey(ResourceLocation packageId, String stepId) {
    }
}
