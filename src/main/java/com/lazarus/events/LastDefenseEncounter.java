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
import com.lazarus.npcs.api.task.TaskContext;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjective;
import com.lazarus.npcs.api.task.TaskState;
import com.lazarus.npcs.api.task.TaskView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * "Última defesa": three survivors of one faction hold a barricade and ask for help. Holding means staying within
 * 24 blocks for two minutes while three waves come in; the reward grows with the defenders still standing.
 */
final class LastDefenseEncounter {
    static final String TYPE = "lastdefense";
    static final ResourceLocation OWNER = ApiSupport.id("lastdefense");
    static final ResourceLocation TASK = ApiSupport.id("lastdefense_hold");
    static final ResourceLocation OVERRUN = ApiSupport.id("overrun");
    private static final ResourceLocation CALL = ApiSupport.id("lastdefense_call");
    private static final ResourceLocation HELD = ApiSupport.id("lastdefense_held");
    static final int HOLD_SECONDS = 120;
    private static final int PRESENCE = 24;
    private static final long ORDER_TICKS = 24_000L;
    private static final String[][] FACTIONS = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8"}};

    private LastDefenseEncounter() {}

    static void register() {
        ApiSupport.log("last defense registration", NpcApi.registerTask(new TaskDefinition(TASK, Component.literal("Hold the line"),
                Component.literal("Stay at the barricade with the defenders for two minutes while the infected come in waves."),
                HOLD, 6_000, TaskDefinition.DeathPolicy.FAIL, false, false,
                ApiSupport.items(ApiSupport.stack("apocalypsenow:canned_food", 3), ApiSupport.stack("apocalypsenow:bandage", 2)),
                null, 0)));
        ApiSupport.log("last defense registration",
                NpcApi.registerDialogue(new DialogueDefinition(CALL, Set.of(DialogueTrigger.INTERACT), 50, LastDefenseEncounter::call)));
    }

    /** Seconds count only while the player stays near the barricade. */
    private static final TaskObjective HOLD = new TaskObjective() {
        @Override public void tick(TaskContext c) {
            if (c.counter("held") < HOLD_SECONDS && c.destination().filter(d -> d.dimension() == c.player().level().dimension()
                    && c.player().blockPosition().closerThan(d.pos(), PRESENCE)).isPresent()) c.addCounter("held", 1);
        }

        @Override public boolean complete(TaskContext c) { return c.counter("held") >= HOLD_SECONDS; }

        @Override public Component progress(TaskContext c) {
            return Component.literal("Hold the barricade: " + Math.min(HOLD_SECONDS, c.counter("held")) + "/" + HOLD_SECONDS
                    + " s (stay within " + PRESENCE + " blocks)");
        }
    };

    static boolean place(ServerLevel level, BlockPos pos, int rotation, UUID encounterId) {
        if (!EncounterScenery.build(level, pos, "gunfire", rotation)) return false;
        String[] variants = FACTIONS[ThreadLocalRandom.current().nextInt(FACTIONS.length)];
        int defenders = 0;
        for (BlockPos spot : new BlockPos[]{pos, EncounterScenery.offset(pos, rotation, 2, 0), EncounterScenery.offset(pos, rotation, -2, 0)}) {
            BlockPos at = level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir() ? spot : pos;
            String variant = variants[ThreadLocalRandom.current().nextInt(variants.length)];
            Entity entity = LazarusEvents.spawn(level, at, "apocalypsenow:survivor_" + variant, encounterId, true);
            if (!(entity instanceof Mob defender)) continue;
            ApiResult order = NpcApi.assign(defender, new ScenarioAssignment(OWNER, encounterId, ScenarioOrder.GUARD_AREA,
                    GlobalPos.of(level.dimension(), pos), null, 6, ORDER_TICKS, 5));
            if (order.ok()) defenders++;
            else ApiSupport.LOGGER.warn("Last defense: defender not assigned: {}", order.message());
        }
        return defenders >= 2;
    }

    private static boolean isDefender(LivingEntity npc, UUID encounterId) {
        return encounterId != null && NpcApi.assignment(npc)
                .filter(a -> a.owner().equals(OWNER) && a.encounterId().equals(encounterId)).isPresent();
    }

    private static Optional<DialoguePage> call(DialogueContext c) {
        if (!isDefender(c.npc(), c.encounterId())) return Optional.empty();
        UUID encounterId = c.encounterId();
        Optional<TaskView> mine = NpcApi.encounterTask(c.player()).filter(t -> t.state().open() && t.encounterId().equals(encounterId));
        if (mine.isPresent())
            return Optional.of(new DialoguePage(Component.literal("Don't stand there talking, keep them off the barricade!"),
                    List.of(new DialogueOption("ok", Component.literal("On it"), ctx -> {}))));
        BlockPos post = NpcApi.assignment(c.npc()).get().anchor().pos();
        return Optional.of(new DialoguePage(Component.literal(
                "They're coming, a whole pack of them. We can hold this barricade, but not with three guns. Stay with us?"), List.of(
                new DialogueOption("hold", Component.literal("I'll hold with you"), ctx -> {
                    ApiResult offer = NpcApi.offerTask(ctx.player(), ctx.npc(), TASK, encounterId,
                            GlobalPos.of(ctx.player().level().dimension(), post));
                    if (offer.status() == ApiResult.Status.BUSY)
                        ctx.player().displayClientMessage(Component.literal("Finish or abandon your current encounter task first (journal)."), true);
                }),
                new DialogueOption("leave", Component.literal("You're on your own"), ctx -> {}))));
    }

    /** The barricade: from the giver's order through the API, or the encounter record if the giver is gone. */
    private static Optional<BlockPos> post(ServerPlayer player, UUID encounterId) {
        Optional<BlockPos> fromGiver = NpcApi.encounterTask(player)
                .map(t -> player.serverLevel().getEntity(t.giverId()))
                .filter(entity -> entity instanceof LivingEntity).map(entity -> (LivingEntity) entity)
                .flatMap(NpcApi::assignment).filter(a -> a.encounterId().equals(encounterId)).map(a -> a.anchor().pos());
        if (fromGiver.isPresent()) return fromGiver;
        return EventData.get(player.getServer().overworld()).encounters.stream()
                .filter(e -> e.id.equals(encounterId)).map(e -> e.pos).findFirst();
    }

    /** Infected in a wave: 6, 9 and 12, plus one every ten world days (at most four more). */
    static int waveSize(int number, long day) { return 3 + 3 * number + (int) Math.min(4, day / 10); }

    /**
     * Spawns one wave around a point, tagged so cancelling the encounter removes it. Each infected gets up to three
     * spots on the ring, so unloaded or uneven ground no longer thins the wave out.
     */
    static int wave(ServerLevel level, BlockPos post, UUID encounterId, int number) {
        int wanted = waveSize(number, level.getDayTime() / 24_000L);
        int spawned = 0;
        double base = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        for (int i = 0; i < wanted * 3 && spawned < wanted; i++) {
            BlockPos at = ApiSupport.ring(level, post, 14 + ThreadLocalRandom.current().nextDouble(10),
                    base + spawned * 0.45 + ThreadLocalRandom.current().nextDouble(-0.2, 0.2));
            String type = spawned % 3 == 2 ? "apocalypsenow:runner_" + (34 + ThreadLocalRandom.current().nextInt(10))
                    : "apocalypsenow:walker_" + (1 + ThreadLocalRandom.current().nextInt(20));
            if (at != null && LazarusEvents.spawn(level, at, type, encounterId, false) != null) spawned++;
        }
        if (spawned < wanted) ApiSupport.LOGGER.debug("Wave {} at {}: {}/{} infected placed", number, post, spawned, wanted);
        return spawned;
    }

    static final class Listener {
        @SubscribeEvent
        public void defenseProgress(NpcApiEvents.TaskProgress event) {
            if (!event.getTaskId().equals(TASK) || !"held".equals(event.getKey())) return;
            int number = switch (event.getValue()) {
                case 1 -> 1;
                case 40 -> 2;
                case 80 -> 3;
                default -> 0;
            };
            if (number == 0) return;
            ServerLevel level = event.getPlayer().serverLevel();
            post(event.getPlayer(), event.getEncounterId()).ifPresent(post -> {
                wave(level, post, event.getEncounterId(), number);
                event.getPlayer().sendSystemMessage(Component.literal(number == 1 ? "Here they come!"
                        : number == 2 ? "Another wave, from the street!" : "This is the last of them. Hold!")
                        .withStyle(style -> style.withColor(0xD48968)));
            });
        }

        @SubscribeEvent
        public void defenseDied(NpcApiEvents.ScenarioActorDied event) {
            if (!event.getAssignment().owner().equals(OWNER) || !(event.getNpc().level() instanceof ServerLevel level)) return;
            UUID encounterId = event.getAssignment().encounterId();
            BlockPos post = event.getAssignment().anchor().pos();
            String name = NpcApi.identity(event.getNpc()).map(view -> view.name()).orElse("A defender");
            int left = (int) ApiSupport.actors(level, encounterId, Vec3.atCenterOf(post), 32).stream()
                    .filter(mob -> mob != event.getNpc() && isDefender(mob, encounterId)).count();
            for (ServerPlayer player : level.players())
                if (player.blockPosition().closerThan(post, 48))
                    player.sendSystemMessage(Component.literal(name + " is down!" + (left > 0 ? " " + left + " left at the barricade." : ""))
                            .withStyle(style -> style.withColor(0xD46868)));
            if (left == 0) ApiSupport.failTasks(level, encounterId, OVERRUN); // nobody left to defend
        }

        @SubscribeEvent
        public void defenseState(NpcApiEvents.TaskStateChanged event) {
            if (!event.getTaskId().equals(TASK) || event.getTo() != TaskState.COMPLETED) return;
            ServerPlayer player = event.getPlayer();
            ServerLevel level = player.serverLevel();
            UUID encounterId = event.getEncounterId();
            post(player, encounterId).ifPresent(post -> {
                List<Mob> defenders = ApiSupport.actors(level, encounterId, Vec3.atCenterOf(post), 32).stream()
                        .filter(mob -> isDefender(mob, encounterId)).toList();
                int alive = defenders.size();
                defenders.stream().findFirst().flatMap(NpcApi::identity).map(view -> view.faction())
                        .filter(faction -> !"independent".equals(faction))
                        .ifPresent(faction -> NpcApi.adjustReputation(player, faction, Math.min(6, 2 * alive), HELD, encounterId));
                player.sendSystemMessage(Component.literal("The line held. " + alive + " of the defenders made it.")
                        .withStyle(style -> style.withColor(0x8FCB91)));
                ApiSupport.LOGGER.info("Lazarus Events: last defense {} held by {} with {} defenders", encounterId,
                        player.getGameProfile().getName(), alive);
            });
        }
    }
}
