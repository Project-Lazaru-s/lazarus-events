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
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * "Falso sinal de rádio": a distress call that is really a looter ambush around a supply camp. The player can pay,
 * bluff once, or fight for the cargo (an optional task). There is never friendly trade; Lazarus NPCs' own warning
 * and toll negotiation keep working when the player just walks up.
 */
final class FalseRadioEncounter {
    static final String TYPE = "falseradio";
    static final ResourceLocation OWNER = ApiSupport.id("falseradio");
    static final ResourceLocation TASK = ApiSupport.id("falseradio_cargo");
    private static final ResourceLocation DEMAND = ApiSupport.id("falseradio_demand");
    private static final ResourceLocation BRIBE = ApiSupport.id("falseradio_bribe");
    private static final ResourceLocation BLUFF = ApiSupport.id("falseradio_bluff");
    private static final ResourceLocation CONFRONT = ApiSupport.id("falseradio_confront");
    static final int APPROACH_RADIUS = 20;
    private static final long TALK_TICKS = 600L;
    private static final int BRIBE_FOOD = 3;
    private static final int CARGO_RADIUS = 32;
    private static final long ORDER_TICKS = 24_000L;

    private FalseRadioEncounter() {}

    static void register() {
        ApiSupport.log("false radio registration", NpcApi.registerTask(new TaskDefinition(TASK, Component.literal("Take back the cargo"),
                Component.literal("The distress call was bait. Drive the looters away from the supply camp: kill them, rout them or make them surrender."),
                DRIVE_OFF, 6_000, TaskDefinition.DeathPolicy.PERSIST, false, false,
                ApiSupport.items(ApiSupport.stack("apocalypsenow:canned_food", 2)), "wardens", 2)));
        ApiSupport.log("false radio registration",
                NpcApi.registerDialogue(new DialogueDefinition(DEMAND, Set.of(DialogueTrigger.INTERACT, DialogueTrigger.THREAT), 50,
                        FalseRadioEncounter::demand)));
    }

    /** Done when no looter of the encounter is left near the cargo (dead, routed far away, or gone after surrendering). */
    private static final TaskObjective DRIVE_OFF = new TaskObjective() {
        private long left(TaskContext c) {
            return c.destination().filter(d -> d.dimension() == c.player().level().dimension())
                    .map(d -> looters(c.player().serverLevel(), c.encounterId(), d.pos()).size()).orElse(0);
        }

        @Override public boolean complete(TaskContext c) { return c.destination().isPresent() && left(c) == 0; }

        @Override public Component progress(TaskContext c) {
            return Component.literal("Drive the looters off the cargo: " + left(c) + " left");
        }
    };

    static List<Mob> looters(ServerLevel level, UUID encounterId, BlockPos cargo) {
        return ApiSupport.actors(level, encounterId, Vec3.atCenterOf(cargo), CARGO_RADIUS).stream()
                .filter(mob -> NpcApi.identity(mob).map(view -> view.role().equals("looter")).orElse(false)).toList();
    }

    /** Called after the supply barrel and the camp are in place. */
    static boolean place(ServerLevel level, BlockPos pos, int rotation, UUID encounterId) {
        if (!EncounterScenery.build(level, pos, "cache", rotation)) return false;
        int guards = 0;
        BlockPos[] spots = {EncounterScenery.offset(pos, rotation, EncounterScenery.RADIUS + 1, 1),
                EncounterScenery.offset(pos, rotation, -EncounterScenery.RADIUS - 1, 2),
                EncounterScenery.offset(pos, rotation, 1, -EncounterScenery.RADIUS - 1)};
        for (BlockPos spot : spots) {
            Mob looter = null;
            // Keep the guard in the camp's loaded chunk: Forge may accept an entity in the adjacent
            // partially loaded chunk yet omit it from area queries until that chunk starts ticking.
            int minX = (pos.getX() >> 4) << 4, minZ = (pos.getZ() >> 4) << 4;
            BlockPos inside = new BlockPos(Math.max(minX, Math.min(minX + 15, spot.getX())), spot.getY(),
                    Math.max(minZ, Math.min(minZ + 15, spot.getZ())));
            for (int step = 0; step < 5 && looter == null; step++) {
                BlockPos at = step == 0 ? inside : inside.offset(step % 2 == 0 ? step / 2 : -step / 2,
                        0, step % 2 == 0 ? 0 : 1);
                if ((at.getX() >> 4) != (pos.getX() >> 4) || (at.getZ() >> 4) != (pos.getZ() >> 4)) continue;
                Entity entity = LazarusEvents.spawn(level, at,
                        "apocalypsenow:looter_" + (1 + ThreadLocalRandom.current().nextInt(5)), encounterId, true);
                if (entity instanceof Mob mob) looter = mob;
            }
            if (looter == null) {
                ApiSupport.LOGGER.warn("False radio could not spawn guard at {} near {} (loaded={})", spot, pos,
                        level.isLoaded(spot));
                continue;
            }
            ApiResult order = guard(looter, encounterId, pos);
            if (order.ok()) guards++;
            else ApiSupport.LOGGER.warn("False radio: looter not assigned: {}", order.message());
        }
        return guards >= 2;
    }

    private static ApiResult guard(Mob looter, UUID encounterId, BlockPos cargo) {
        return NpcApi.assign(looter, new ScenarioAssignment(OWNER, encounterId, ScenarioOrder.GUARD_AREA,
                GlobalPos.of(looter.level().dimension(), cargo), null, 10, ORDER_TICKS, 5));
    }

    private static String confrontingTag(ServerPlayer player) { return LazarusEvents.ID + ":confronting:" + player.getUUID(); }

    private static String confrontedTag(ServerPlayer player) { return LazarusEvents.ID + ":confronted:" + player.getUUID(); }

    /**
     * Once per second per ambush: the looter closest to a newcomer walks up and makes the demand in person. The group
     * holds fire for the 30-second talk (attacking them ends it at once). Each player is confronted once.
     */
    static void confront(ServerLevel level, UUID encounterId, BlockPos cargo) {
        List<Mob> group = looters(level, encounterId, cargo);
        if (group.isEmpty()) return;
        for (Mob looter : group)
            if (NpcApi.assignment(looter).filter(a -> a.owner().equals(OWNER)).isEmpty()) guard(looter, encounterId, cargo);
        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator() || !player.isAlive()) continue;
            String confronted = confrontedTag(player), confronting = confrontingTag(player);
            if (group.stream().anyMatch(looter -> looter.getTags().contains(confronted))) continue;
            Optional<Mob> speaker = group.stream().filter(looter -> looter.getTags().contains(confronting)).findFirst();
            if (speaker.isPresent()) {
                Mob looter = speaker.get();
                boolean stillWalking = NpcApi.assignment(looter).filter(a -> a.order() == ScenarioOrder.FOLLOW_PLAYER).isPresent();
                if (looter.distanceTo(player) <= 5 || !stillWalking) {
                    if (looter.distanceTo(player) > 5) {
                        looter.removeTag(confronting);
                        guard(looter, encounterId, cargo);
                        continue;
                    }
                    NpcApi.openDialogue(player, looter, DialogueTrigger.THREAT);
                    group.forEach(member -> member.addTag(confronted));
                    looter.removeTag(confronting);
                    guard(looter, encounterId, cargo);
                }
                continue;
            }
            if (!player.blockPosition().closerThan(cargo, APPROACH_RADIUS)
                    || NpcApi.encounterTask(player).filter(t -> t.state().open() && t.encounterId().equals(encounterId)).isPresent())
                continue;
            Mob looter = group.stream().min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(player))).get();
            looter.addTag(confronting);
            NpcApi.grantPassage(player, looter, TALK_TICKS, CONFRONT);
            NpcApi.assign(looter, new ScenarioAssignment(OWNER, encounterId, ScenarioOrder.FOLLOW_PLAYER,
                    GlobalPos.of(level.dimension(), cargo), player.getUUID(), 4, TALK_TICKS, 6));
            player.displayClientMessage(Component.literal("Someone steps out from behind the tents and walks toward you.")
                    .withStyle(style -> style.withColor(0xD48968)), true);
        }
    }

    private static boolean isAmbusher(LivingEntity npc, UUID encounterId) {
        return encounterId != null && NpcApi.assignment(npc)
                .filter(a -> a.owner().equals(OWNER) && a.encounterId().equals(encounterId)).isPresent();
    }

    private static Optional<DialoguePage> demand(DialogueContext c) {
        if (!isAmbusher(c.npc(), c.encounterId())) return Optional.empty();
        UUID encounterId = c.encounterId();
        if (NpcApi.encounterTask(c.player()).filter(t -> t.state().open() && t.encounterId().equals(encounterId)).isPresent())
            return Optional.empty(); // already fighting for the cargo: no more talking
        BlockPos cargo = NpcApi.assignment(c.npc()).get().anchor().pos();
        String bluffTag = LazarusEvents.ID + ":bluffed:" + c.player().getUUID();
        List<DialogueOption> options = new java.util.ArrayList<>();
        options.add(new DialogueOption("pay", Component.literal("Pay (" + BRIBE_FOOD + " canned food)"), ctx -> pay(ctx, encounterId, cargo)));
        if (!c.npc().getTags().contains(bluffTag))
            options.add(new DialogueOption("bluff", Component.literal("\"A patrol is right behind me\""), ctx -> bluff(ctx, encounterId, cargo, bluffTag)));
        options.add(new DialogueOption("fight", Component.literal("The cargo is mine"), ctx -> {
            ApiResult offer = NpcApi.offerTask(ctx.player(), ctx.npc(), TASK, encounterId, GlobalPos.of(ctx.player().level().dimension(), cargo));
            if (offer.status() == ApiResult.Status.BUSY)
                ctx.player().displayClientMessage(Component.literal("Finish or abandon your current encounter task first (journal)."), true);
        }));
        options.add(new DialogueOption("leave", Component.literal("Walk away"), ctx -> {}));
        return Optional.of(new DialoguePage(Component.literal(
                "Heard our little distress call, huh? Nobody's hurt here. Empty your pockets: " + BRIBE_FOOD + " cans of food."), options));
    }

    private static void pay(DialogueContext c, UUID encounterId, BlockPos cargo) {
        Item food = ApiSupport.stack("apocalypsenow:canned_food", 1).getItem();
        var inventory = c.player().getInventory();
        if (inventory.countItem(food) < BRIBE_FOOD) {
            c.player().displayClientMessage(Component.literal("You don't have " + BRIBE_FOOD + " cans of food."), true);
            return; // nothing taken
        }
        int remaining = BRIBE_FOOD;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.is(food)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
        }
        c.player().inventoryMenu.broadcastChanges();
        NpcApi.grantPassage(c.player(), c.npc(), 6_000, BRIBE);
        c.player().sendSystemMessage(Component.literal("They take the food and wave you off. The cargo stays with them."));
    }

    /** One try per player and looter group: half the time they believe it and leave the cargo. */
    private static void bluff(DialogueContext c, UUID encounterId, BlockPos cargo, String bluffTag) {
        ServerLevel level = c.player().serverLevel();
        List<Mob> group = looters(level, encounterId, cargo);
        group.forEach(mob -> mob.addTag(bluffTag));
        if (ThreadLocalRandom.current().nextBoolean()) {
            NpcApi.grantPassage(c.player(), c.npc(), 2_400, BLUFF);
            Vec3 away = Vec3.atCenterOf(cargo).subtract(c.player().position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 1) away = new Vec3(1, 0, 0);
            Vec3 fallback = Vec3.atCenterOf(cargo).add(away.normalize().scale(28));
            BlockPos retreat = ApiSupport.ring(level, cargo, 28, Math.atan2(fallback.z - cargo.getZ(), fallback.x - cargo.getX()));
            for (Mob looter : group)
                NpcApi.assign(looter, new ScenarioAssignment(OWNER, encounterId, ScenarioOrder.RETREAT,
                        GlobalPos.of(level.dimension(), retreat == null ? looter.blockPosition() : retreat), null, 3, 2_400, 5));
            c.player().sendSystemMessage(Component.literal("They exchange looks, grab their gear and back off. The cargo is unguarded."));
        } else c.player().sendSystemMessage(Component.literal("\"Nice try.\" Nobody moves.").withStyle(style -> style.withColor(0xD48968)));
    }

    static final class Listener {
        @SubscribeEvent
        public void radioTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 7) return;
            ServerLevel level = event.getServer().overworld();
            for (EventData.Encounter encounter : List.copyOf(EventData.get(level).encounters))
                if (TYPE.equals(encounter.type) && level.isLoaded(encounter.pos)) confront(level, encounter.id, encounter.pos);
        }

        @SubscribeEvent
        public void radioState(NpcApiEvents.TaskStateChanged event) {
            if (event.getTaskId().equals(TASK) && event.getTo() == TaskState.COMPLETED)
                event.getPlayer().sendSystemMessage(Component.literal("The looters are gone. The cargo is yours."));
        }
    }
}
