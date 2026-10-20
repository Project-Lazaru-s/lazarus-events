package com.lazarus.events;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.dialogue.DialogueContext;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjectives;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Rare, bounded roaming groups. These actors never enter a permanent base roster. */
final class FactionExpeditions {
    private static final ResourceLocation OWNER = ApiSupport.id("faction_expedition");
    private static final String EXPIRES = "LazarusEventsExpeditionExpires";
    private static final String EXPEDITION = "LazarusEventsExpeditionId";
    private static final long DURATION = 12_000;
    private static final ResourceLocation LEAD_DIALOGUE = ApiSupport.id("expedition_lead");

    static void register() {
        for (String faction : BaseRoster.factions()) {
            ApiSupport.log("outpost lead task " + faction, NpcApi.registerTask(new TaskDefinition(lead(faction),
                    Component.literal("Find the " + faction + " outpost"),
                    Component.literal("An expedition soldier pointed you to their faction's outpost. Reach it to introduce yourself."),
                    TaskObjectives.reach(24), 72_000, TaskDefinition.DeathPolicy.PERSIST, false, false,
                    List.of(), faction, 2)));
        }
        ApiSupport.log("expedition lead dialogue", NpcApi.registerDialogue(new DialogueDefinition(
                LEAD_DIALOGUE, Set.of(DialogueTrigger.INTERACT), 43, FactionExpeditions::leadDialogue)));
    }

    private static ResourceLocation lead(String faction) { return ApiSupport.id(faction + "_outpost_lead"); }

    /** Roaming soldiers point players who have not found their faction's outpost towards it. */
    static Optional<DialoguePage> leadDialogue(DialogueContext c) {
        if (c.encounterId() == null || !(c.npc().level() instanceof ServerLevel level)) return Optional.empty();
        FactionExpeditionData data = FactionExpeditionData.get(level);
        if (!c.encounterId().equals(data.activeId) || !data.members.contains(c.npc().getUUID())) return Optional.empty();
        String faction = NpcApi.identity(c.npc()).map(identity -> identity.faction()).orElse("");
        if (NpcApi.reputation(c.player(), faction) <= -20)
            return Optional.of(new DialoguePage(Component.literal("Keep walking. We have nothing to say to you."),
                    List.of(new DialogueOption("leave", Component.literal("Leave"), ctx -> {}))));
        FactionBaseData.Base base = FactionBaseData.get(level).faction(faction);
        if (base == null)
            return Optional.of(new DialoguePage(Component.literal("We're scouting for a place to dig in. No outpost yet; ask again in a few days."),
                    List.of(new DialogueOption("leave", Component.literal("Good luck"), ctx -> {}))));
        String where = FactionMissions.direction(c.npc().blockPosition(), base.pos) + ", about "
                + Math.max(50, Math.round(Math.sqrt(c.npc().blockPosition().distSqr(base.pos)) / 50) * 50) + " blocks from here";
        if (c.player().getPersistentData().getBoolean(FactionBases.seenKey(base)))
            return Optional.of(new DialoguePage(Component.literal("You've been to our outpost already. It's still " + where + "."),
                    List.of(new DialogueOption("leave", Component.literal("See you there"), ctx -> {}))));
        return Optional.of(new DialoguePage(Component.literal("Looking for the " + faction + "? Our outpost lies " + where
                + ". Tell them the patrol sent you."), List.of(
                new DialogueOption("mark", Component.literal("Mark it in my journal"), ctx -> offerLead(ctx, base)),
                new DialogueOption("leave", Component.literal("Thanks"), ctx -> {}))));
    }

    private static void offerLead(DialogueContext c, FactionBaseData.Base base) {
        if (!(c.npc().level() instanceof ServerLevel level)) return;
        ApiResult result = NpcApi.offerTask(c.player(), c.npc(), lead(base.faction), base.id,
                GlobalPos.of(level.dimension(), base.pos));
        if (!result.ok()) c.player().sendSystemMessage(Component.literal("Outpost near " + base.pos.getX() + ", "
                + base.pos.getZ() + ". (" + result.message() + ")"));
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !LazarusEvents.expeditionsEnabled()
                || event.getServer().getTickCount() % 200 != 37) return;
        ServerLevel level = event.getServer().overworld();
        long now = level.getGameTime();
        FactionExpeditionData data = FactionExpeditionData.get(level);
        if (data.activeId != null) {
            boolean loadedRoute = data.route.length == 0 || java.util.Arrays.stream(data.route)
                    .allMatch(packed -> level.isLoaded(BlockPos.of(packed)));
            if (now < data.expires && loadedRoute) return;
            for (UUID id : data.members) if (level.getEntity(id) instanceof Mob mob) mob.discard();
            data.retire();
        }
        if (data.nextAttempt == 0) {
            data.nextAttempt = now + LazarusEvents.expeditionCooldown();
            data.setDirty();
            return;
        }
        if (now < data.nextAttempt) return;
        data.nextAttempt = now + LazarusEvents.expeditionCooldown();
        data.setDirty();
        for (ServerPlayer player : level.players()) {
            if (tryStart(level, data, player)) return;
        }
    }

    static boolean force(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != Level.OVERWORLD) {
            player.sendSystemMessage(Component.literal("Expeditions only spawn in the Overworld."));
            return false;
        }
        FactionExpeditionData data = FactionExpeditionData.get(level);
        if (data.activeId != null) {
            player.sendSystemMessage(Component.literal("Expedition already active: " + data.activeId));
            return false;
        }
        boolean started = tryStart(level, data, player);
        if (started) {
            data.nextAttempt = level.getGameTime() + LazarusEvents.expeditionCooldown();
            data.setDirty();
            player.sendSystemMessage(Component.literal("Expedition " + data.activeId + " spawned nearby."));
        } else player.sendSystemMessage(Component.literal("No safe, loaded expedition site found 56-80 blocks away."));
        return started;
    }

    private static boolean tryStart(ServerLevel level, FactionExpeditionData data, ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) return false;
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            BlockPos center = ApiSupport.ring(level, player.blockPosition(), 56 + level.random.nextInt(25), angle);
            if (center == null || FactionBaseData.get(level).near(center, 48) != null
                    || !LazarusEvents.safe(level, center) || !LazarusEvents.safe(level, center.east())) continue;
            List<BlockPos> route = route(level, player.blockPosition(), center);
            if (route.size() >= 4 && spawn(level, data, center, route)) return true;
        }
        return false;
    }

    private static List<BlockPos> route(ServerLevel level, BlockPos player, BlockPos center) {
        List<BlockPos> points = new ArrayList<>();
        double angle = Math.atan2(center.getZ() - player.getZ(), center.getX() - player.getX()) + 0.55;
        points.add(center);
        for (int i = 1; i <= 5; i++) {
            BlockPos point = ApiSupport.ring(level, center, i * 16.0, angle);
            if (point == null || !LazarusEvents.safe(level, point)
                    || FactionBaseData.get(level).near(point, 40) != null) break;
            points.add(point);
        }
        return points;
    }

    private static boolean spawn(ServerLevel level, FactionExpeditionData data, BlockPos center, List<BlockPos> route) {
        UUID id = UUID.randomUUID();
        String faction = BaseRoster.factions().get(level.random.nextInt(BaseRoster.factions().size()));
        NpcFactions.Definition definition = NpcFactions.get(faction);
        if (definition == null || definition.survivorVariants().isEmpty()) return false;
        List<Mob> actors = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BlockPos at = center.offset(i == 2 ? 0 : i, 0, i == 2 ? 1 : 0);
            if (!(LazarusEvents.spawn(level, at, "apocalypsenow:survivor_" + definition.variant(i), id, false) instanceof Mob mob)) {
                actors.forEach(Mob::discard);
                return false;
            }
            if (!NpcApi.assignFaction(mob, faction).ok()) {
                mob.discard();
                actors.forEach(Mob::discard);
                return false;
            }
            actors.add(mob);
        }
        for (Mob mob : actors) {
            mob.getPersistentData().putLong(EXPIRES, level.getGameTime() + DURATION);
            mob.getPersistentData().putUUID(EXPEDITION, id);
            boolean assigned = NpcApi.assignProfession(mob, new ResourceLocation("lazarusnpcs", "expedition_soldier"), null).ok()
                    && NpcApi.assign(mob, new ScenarioAssignment(OWNER, id, ScenarioOrder.TRAVEL,
                    GlobalPos.of(Level.OVERWORLD, center), null, 14, DURATION, 3)).ok()
                    && NpcApi.setTravelRoute(mob, OWNER, id, route).ok();
            if (!assigned) {
                actors.forEach(Mob::discard);
                data.members.clear();
                return false;
            }
            ApiSupport.log("expedition weapon", NpcApi.armScenarioActor(mob, OWNER, id));
            data.members.add(mob.getUUID());
        }
        data.activeId = id;
        data.expires = level.getGameTime() + DURATION;
        data.route = route.stream().mapToLong(BlockPos::asLong).toArray();
        data.setDirty();
        ApiSupport.LOGGER.info("{} expedition {} spawned near {}", faction, id, center);
        return true;
    }

    @SubscribeEvent
    public void loaded(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) return;
        long expires = mob.getPersistentData().getLong(EXPIRES);
        if (expires > 0 && (level.getGameTime() >= expires || mob.getPersistentData().hasUUID(EXPEDITION)
                && FactionExpeditionData.get(level).retired.contains(mob.getPersistentData().getUUID(EXPEDITION))))
            mob.discard();
    }
}
