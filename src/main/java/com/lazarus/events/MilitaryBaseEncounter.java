package com.lazarus.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The military outpost, now run through the Lazarus NPCs API: soldiers hold the base, the gate answers according to
 * Military standing, and a perimeter task brings waves of infected against the fence.
 */
final class MilitaryBaseEncounter {
    static final ResourceLocation OWNER = ApiSupport.id("military_base");
    static final ResourceLocation TASK = ApiSupport.id("military_perimeter");
    private static final ResourceLocation GATE = ApiSupport.id("military_gate");
    static final int KILLS = 12;
    static final int FRIENDLY = 10;
    static final int DROP_FOOD = 3, DROP_COST = 4;
    static final long DROP_COOLDOWN = 72_000L; // three days
    private static final String DROP_KEY = "LazarusEventsSupplyDrop";
    private static final ResourceLocation DROP_REASON = ApiSupport.id("supply_drop");
    private static final long ORDER_TICKS = 168_000L;

    private MilitaryBaseEncounter() {}

    static void register() {
        ApiSupport.log("military base registration", NpcApi.registerTask(new TaskDefinition(TASK, Component.literal("Secure the perimeter"),
                Component.literal("Infected keep hitting the outpost fence. Kill twelve of the attackers, then report to the soldier who sent you."),
                TaskObjectives.kill(KILLS, (victim, encounterId) -> victim.getTags().contains(LazarusEvents.ID + ":" + encounterId)
                        && NpcApi.identity(victim).isEmpty(), Component.literal("Attackers killed")),
                12_000, TaskDefinition.DeathPolicy.PERSIST, false, true,
                ApiSupport.items(ammo("tacz:556x45", 30), ApiSupport.stack("apocalypsenow:bandage", 2)), "military", 3)));
        ApiSupport.log("military base registration",
                NpcApi.registerDialogue(new DialogueDefinition(GATE, Set.of(DialogueTrigger.INTERACT), 50, MilitaryBaseEncounter::gate)));
    }

    private static ItemStack ammo(String ammoId, int count) {
        ItemStack stack = ApiSupport.stack("tacz:ammo", count);
        if (!stack.isEmpty()) {
            CompoundTag tag = stack.getOrCreateTag();
            tag.putString("AmmoId", ammoId);
        }
        return stack;
    }

    /** The middle of the checkpoint template, where the soldiers hold. */
    static BlockPos center(BlockPos origin, int rotation) { return EncounterScenery.militaryPosition(origin, rotation, 11, 1, 6); }

    /** Gives the stationed NPCs their orders; called after the outpost is built and staffed. */
    static int station(ServerLevel level, BlockPos center, UUID encounterId, List<Mob> staff) {
        int assigned = 0;
        for (Mob npc : staff) {
            boolean medic = NpcApi.identity(npc).map(view -> view.role().equals("medic")).orElse(false);
            ApiResult order = NpcApi.assign(npc, new ScenarioAssignment(OWNER, encounterId,
                    medic ? ScenarioOrder.WAIT : ScenarioOrder.GUARD_AREA,
                    GlobalPos.of(level.dimension(), medic ? npc.blockPosition() : center), null, medic ? 3 : 12, ORDER_TICKS, 5));
            if (order.ok()) assigned++;
            else ApiSupport.LOGGER.warn("Military base: {} not assigned: {}", npc.getUUID(), order.message());
        }
        return assigned;
    }

    private static Optional<ScenarioAssignment> order(LivingEntity npc, UUID encounterId) {
        return encounterId == null ? Optional.empty()
                : NpcApi.assignment(npc).filter(a -> a.owner().equals(OWNER) && a.encounterId().equals(encounterId));
    }

    private static Optional<DialoguePage> gate(DialogueContext c) {
        Optional<ScenarioAssignment> order = order(c.npc(), c.encounterId());
        if (order.isEmpty()) return Optional.empty();
        UUID encounterId = c.encounterId();
        Optional<TaskView> mine = NpcApi.encounterTask(c.player()).filter(t -> t.state().open() && t.encounterId().equals(encounterId));
        if (mine.isPresent()) {
            if (mine.get().state() == TaskState.READY && mine.get().giverId().equals(c.npc().getUUID())) return Optional.empty(); // report
            return Optional.of(new DialoguePage(Component.literal("Keep them off the fence! Report back when it's done."),
                    List.of(new DialogueOption("ok", Component.literal("On it"), ctx -> {}))));
        }
        int standing = NpcApi.reputation(c.player(), "military");
        List<DialogueOption> options = new ArrayList<>();
        Component line;
        if (standing <= -20) {
            line = Component.literal("Turn around. You're not welcome at this outpost.");
        } else {
            line = Component.literal(standing >= FRIENDLY
                    ? "Good to see a friendly face. The infected keep hitting our fence; we could use another rifle."
                    : "Restricted area. This outpost doesn't open for drifters. You want in, you earn it.");
            BlockPos center = order.get().anchor().pos();
            options.add(new DialogueOption("perimeter", Component.literal(standing >= FRIENDLY ? "I'll clear the perimeter" : "I can help with the perimeter"),
                    ctx -> {
                        ApiResult offer = NpcApi.offerTask(ctx.player(), ctx.npc(), TASK, encounterId, GlobalPos.of(ctx.player().level().dimension(), center));
                        if (offer.status() == ApiResult.Status.BUSY)
                            ctx.player().displayClientMessage(Component.literal(offer.message().contains("taken")
                                    ? "Someone is already on it." : "Finish or abandon your current encounter task first (journal)."), true);
                    }));
            if (standing >= FRIENDLY) options.add(new DialogueOption("supply",
                    Component.literal("Request a supply drop (" + DROP_FOOD + " canned food)"),
                    ctx -> ctx.player().displayClientMessage(requestDrop(ctx.player(), encounterId), false)));
        }
        options.add(new DialogueOption("leave", Component.literal(standing <= -20 ? "Leave" : "Carry on"), ctx -> {}));
        return Optional.of(new DialoguePage(line, options));
    }

    /**
     * Trades canned food and some Military standing for a dyairdrop flare gun; the airdrop itself (plane, crate,
     * infected drawn to it) is dyairdrop's own. Nothing is taken unless the flare gun can be handed over.
     */
    static Component requestDrop(ServerPlayer player, UUID encounterId) {
        CompoundTag persisted = player.getPersistentData().getCompound(ServerPlayer.PERSISTED_NBT_TAG);
        long now = player.serverLevel().getGameTime(), ready = persisted.getLong(DROP_KEY);
        if (now < ready)
            return Component.literal("\"Command won't authorize another drop yet. Try again in "
                    + Math.max(1, (ready - now + 23_999) / 24_000) + " day(s).\"");
        if (NpcApi.reputation(player, "military") < FRIENDLY) return Component.literal("\"Drops are for friends of the outpost.\"");
        ItemStack flare = ApiSupport.stack("dyairdrop:flaregun0", 1);
        ItemStack food = ApiSupport.stack("apocalypsenow:canned_food", 1);
        if (flare.isEmpty() || food.isEmpty()) return Component.literal("\"Radio's dead. No drops today.\"");
        if (player.getInventory().countItem(food.getItem()) < DROP_FOOD)
            return Component.literal("\"Bring " + DROP_FOOD + " canned food for the pilots and we'll talk.\"");
        int owed = DROP_FOOD;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && owed > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(food.getItem())) continue;
            int taken = Math.min(owed, stack.getCount());
            stack.shrink(taken);
            owed -= taken;
        }
        NpcApi.adjustReputation(player, "military", -DROP_COST, DROP_REASON, encounterId);
        persisted.putLong(DROP_KEY, now + DROP_COOLDOWN);
        player.getPersistentData().put(ServerPlayer.PERSISTED_NBT_TAG, persisted);
        if (!player.getInventory().add(flare)) player.drop(flare, false);
        return Component.literal("\"Fire this somewhere open. The crate draws the infected, so be quick.\"");
    }

    /** The base center from the giver's order, for waves. */
    private static Optional<BlockPos> center(ServerPlayer player, UUID encounterId) {
        return NpcApi.encounterTask(player).map(t -> player.serverLevel().getEntity(t.giverId()))
                .filter(entity -> entity instanceof LivingEntity).map(entity -> (LivingEntity) entity)
                .flatMap(npc -> order(npc, encounterId)).map(a -> a.anchor().pos())
                .or(() -> EventData.get(player.getServer().overworld()).encounters.stream()
                        .filter(e -> e.id.equals(encounterId)).map(e -> e.pos).findFirst());
    }

    static final class Listener {
        @SubscribeEvent
        public void baseState(NpcApiEvents.TaskStateChanged event) {
            if (!event.getTaskId().equals(TASK)) return;
            ServerPlayer player = event.getPlayer();
            if (event.getTo() == TaskState.ACTIVE) {
                center(player, event.getEncounterId()).ifPresent(center ->
                        LastDefenseEncounter.wave(player.serverLevel(), center, event.getEncounterId(), 1));
                player.sendSystemMessage(Component.literal("Contact on the perimeter! Infected incoming.").withStyle(style -> style.withColor(0xD48968)));
            } else if (event.getTo() == TaskState.READY) {
                player.sendSystemMessage(Component.literal("Perimeter clear. Report to the soldier who sent you."));
            } else if (event.getTo() == TaskState.COMPLETED) {
                player.sendSystemMessage(Component.literal("\"Good work. The outpost owes you one.\"").withStyle(style -> style.withColor(0xB5C48B)));
            }
        }

        @SubscribeEvent
        public void baseProgress(NpcApiEvents.TaskProgress event) {
            if (!event.getTaskId().equals(TASK) || !"kills".equals(event.getKey())) return;
            int wave = event.getValue() == 4 ? 2 : event.getValue() == 8 ? 3 : 0;
            if (wave == 0) return;
            ServerPlayer player = event.getPlayer();
            center(player, event.getEncounterId()).ifPresent(center ->
                    LastDefenseEncounter.wave(player.serverLevel(), center, event.getEncounterId(), wave));
            player.sendSystemMessage(Component.literal(wave == 2 ? "More of them on the north fence!" : "Last push, hold the line!")
                    .withStyle(style -> style.withColor(0xD48968)));
        }
    }
}
