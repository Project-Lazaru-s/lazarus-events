package com.lazarus.events;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.dialogue.DialogueContext;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjectives;
import com.lazarus.npcs.api.task.TaskState;
import com.lazarus.npcs.api.task.TaskView;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * "Pedido de socorro": a wounded survivor waits by a flare marker. They ask for bandages, then to be walked to a
 * safe point. Everything NPC-side goes through the Lazarus NPCs API; nothing reads its private data.
 */
final class RescueEncounter {
    static final String TYPE = "rescue";
    static final ResourceLocation OWNER = id("rescue");
    static final ResourceLocation TASK = id("rescue_survivor");
    private static final ResourceLocation PLEA = id("rescue_plea");
    private static final ResourceLocation THANKS = id("rescue_thanks");
    private static final ResourceLocation REWARD_REASON = id("rescue_success");
    private static final long WAIT_TICKS = 168_000L;
    private static final long FOLLOW_TICKS = 24_000L;
    private static final int SAFE_DISTANCE = 40;
    private static final Logger LOGGER = LogUtils.getLogger();

    private RescueEncounter() {}

    private static ResourceLocation id(String path) { return ApiSupport.id(path); }

    private static ItemStack stack(String id, int count) { return ApiSupport.stack(id, count); }

    /** Registered once during common setup. */
    static void register() {
        log(NpcApi.registerTask(new TaskDefinition(TASK, Component.literal("A call for help"),
                Component.literal("A wounded survivor needs two bandages, then an escort to the safe point."),
                TaskObjectives.sequence(TaskObjectives.deliver(List.of(stack("apocalypsenow:bandage", 2))), TaskObjectives.escort(6)),
                24_000, TaskDefinition.DeathPolicy.PERSIST, true, false,
                List.of(stack("apocalypsenow:canned_food", 2), stack("apocalypsenow:canned_water", 1),
                        stack("apocalypsenow:antibiotics", 1)).stream().filter(s -> !s.isEmpty()).toList(), null, 0)));
        log(NpcApi.registerDialogue(new DialogueDefinition(PLEA, Set.of(DialogueTrigger.INTERACT), 50, RescueEncounter::plea)));
        log(NpcApi.registerDialogue(new DialogueDefinition(THANKS, Set.of(DialogueTrigger.TASK_COMPLETE), 50, RescueEncounter::thanks)));
    }

    private static void log(ApiResult result) { ApiSupport.log("rescue registration", result); }

    /** Spawns and assigns the survivor and infected. False leaves cleanup to the caller (actors carry the tag). */
    static boolean place(ServerLevel level, BlockPos pos, int rotation, UUID encounterId) {
        BlockPos actors = EncounterScenery.offset(pos, rotation, EncounterScenery.RADIUS + 1, 1);
        int infected = 0;
        for (int i = 0; i < 3; i++)
            if (LazarusEvents.spawn(level, actors, "apocalypsenow:walker_" + (i + 4), encounterId, false) != null) infected++;
        if (infected == 0 || !EncounterScenery.build(level, pos, "flare", rotation)) return false;
        BlockPos spot = EncounterScenery.offset(pos, rotation, 1, 1);
        Entity entity = LazarusEvents.spawn(level, spot, "apocalypsenow:survivor_" + (1 + ThreadLocalRandom.current().nextInt(8)),
                encounterId, true);
        if (!(entity instanceof Mob survivor)) return false;
        survivor.setHealth(survivor.getMaxHealth() * 0.4f);
        survivor.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) WAIT_TICKS, 2, false, false));
        ApiResult order = NpcApi.assign(survivor, new ScenarioAssignment(OWNER, encounterId, ScenarioOrder.WAIT,
                GlobalPos.of(level.dimension(), spot), null, 2, WAIT_TICKS, 5));
        if (!order.ok()) {
            LOGGER.warn("Rescue survivor could not be assigned: {}", order.message());
            return false;
        }
        return true;
    }

    /** The rescue survivor of this encounter, waiting or walking. */
    private static boolean isRescueActor(LivingEntity npc, UUID encounterId) {
        return encounterId != null && NpcApi.assignment(npc)
                .filter(a -> a.owner().equals(OWNER) && a.encounterId().equals(encounterId)).isPresent();
    }

    private static Optional<DialoguePage> plea(DialogueContext c) {
        if (!isRescueActor(c.npc(), c.encounterId())) return Optional.empty();
        ScenarioOrder order = NpcApi.assignment(c.npc()).get().order();
        if (order != ScenarioOrder.WAIT) return Optional.empty(); // already being helped
        Optional<TaskView> mine = NpcApi.encounterTask(c.player());
        if (mine.filter(t -> t.state().open() && t.giverId().equals(c.npc().getUUID())).isPresent())
            return Optional.empty(); // Lazarus NPCs' own hand-in dialogue takes it from here
        UUID encounterId = c.encounterId();
        return Optional.of(new DialoguePage(Component.literal(
                "Please... they got my leg. I need bandages, and someone to get me out of here."), List.of(
                new DialogueOption("help", Component.literal("I'll help you"), ctx -> offer(ctx, encounterId)),
                new DialogueOption("leave", Component.literal("Not now"), ctx -> {}))));
    }

    private static void offer(DialogueContext c, UUID encounterId) {
        ApiResult result = NpcApi.offerTask(c.player(), c.npc(), TASK, encounterId, safePoint(c.player(), c.npc()));
        if (!result.ok()) c.player().displayClientMessage(Component.literal(switch (result.status()) {
            case BUSY -> result.message().contains("taken") ? "Someone else is already helping them."
                    : "Finish or abandon your current encounter task first (journal).";
            default -> "They can't go with you right now.";
        }), true);
    }

    /** 40 blocks back toward where the player came from; the player's own position if that ground is not loaded. */
    private static GlobalPos safePoint(ServerPlayer player, LivingEntity npc) {
        ServerLevel level = player.serverLevel();
        Vec3 away = player.position().subtract(npc.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1) away = new Vec3(1, 0, 0);
        Vec3 point = npc.position().add(away.normalize().scale(SAFE_DISTANCE));
        int x = (int) Math.floor(point.x), z = (int) Math.floor(point.z);
        BlockPos pos = level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null ? player.blockPosition()
                : new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        return GlobalPos.of(level.dimension(), pos);
    }

    private static Optional<DialoguePage> thanks(DialogueContext c) {
        if (!isRescueActor(c.npc(), c.encounterId())) return Optional.empty();
        return Optional.of(new DialoguePage(Component.literal("We made it. Thank you... I won't forget this."),
                List.of(new DialogueOption("bye", Component.literal("Stay safe"), ctx -> {}))));
    }

    private static Mob survivor(ServerPlayer player, UUID id) {
        Entity entity = player.serverLevel().getEntity(id);
        return entity instanceof Mob mob && mob.isAlive() ? mob : null;
    }

    /**
     * Lazarus NPCs events drive the rest of the scenario. Method names must be unique across this mod's listener
     * classes: Forge's event bus mixes up generated handlers for same-named methods on the same event.
     */
    static final class Listener {
        @SubscribeEvent
        public void rescueProgress(NpcApiEvents.TaskProgress event) {
            if (!event.getTaskId().equals(TASK) || !"stage".equals(event.getKey()) || event.getValue() != 1) return;
            ServerPlayer player = event.getPlayer();
            Optional<TaskView> task = NpcApi.encounterTask(player);
            if (task.isEmpty()) return;
            Mob survivor = survivor(player, task.get().giverId());
            if (survivor == null) return;
            survivor.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            survivor.setHealth(Math.max(survivor.getHealth(), survivor.getMaxHealth() * 0.7f));
            ApiResult follow = NpcApi.assign(survivor, new ScenarioAssignment(OWNER, event.getEncounterId(),
                    ScenarioOrder.FOLLOW_PLAYER, GlobalPos.of(survivor.level().dimension(), survivor.blockPosition()),
                    player.getUUID(), 4, FOLLOW_TICKS, 5));
            if (follow.ok()) player.sendSystemMessage(Component.literal("The bandages hold. Lead them to the safe point; the journal has the coordinates."));
            else LOGGER.warn("Rescue follow order refused: {}", follow.message());
        }

        @SubscribeEvent
        public void rescueState(NpcApiEvents.TaskStateChanged event) {
            if (!event.getTaskId().equals(TASK)) return;
            ServerPlayer player = event.getPlayer();
            Optional<TaskView> task = NpcApi.encounterTask(player);
            Mob survivor = task.map(t -> survivor(player, t.giverId())).orElse(null);
            UUID encounterId = event.getEncounterId();
            switch (event.getTo()) {
                case COMPLETED -> {
                    if (survivor == null) return;
                    NpcApi.assign(survivor, new ScenarioAssignment(OWNER, encounterId, ScenarioOrder.WAIT,
                            GlobalPos.of(survivor.level().dimension(), survivor.blockPosition()), null, 3, 12_000, 5));
                    NpcApi.identity(survivor).map(view -> view.faction()).filter(f -> !"independent".equals(f))
                            .ifPresent(faction -> NpcApi.adjustReputation(player, faction, 5, REWARD_REASON, encounterId));
                    NpcApi.openDialogue(player, survivor, DialogueTrigger.TASK_COMPLETE);
                    HavenRefuge.rescued(player, survivor);
                    LOGGER.info("Lazarus Events: rescue {} completed by {}", encounterId, player.getGameProfile().getName());
                }
                case ABANDONED, EXPIRED -> {
                    // Still alive and the encounter goes on: wait again so someone can help.
                    if (survivor != null && event.getFrom() != TaskState.OFFERED)
                        NpcApi.assign(survivor, new ScenarioAssignment(OWNER, encounterId, ScenarioOrder.WAIT,
                                GlobalPos.of(survivor.level().dimension(), survivor.blockPosition()), null, 2, WAIT_TICKS, 5));
                }
                case FAILED -> LOGGER.info("Lazarus Events: rescue {} failed ({})", encounterId, event.getReason());
                default -> { }
            }
        }
    }
}
