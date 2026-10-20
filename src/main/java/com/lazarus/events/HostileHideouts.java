package com.lazarus.events;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.HostileGroups;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where the hostile groups live: deserters hold an old checkpoint, a gang holds a fortified camp with its stash.
 * Occupants guard the place; once a player has seen them and none is left, the hideout counts as cleared.
 */
final class HostileHideouts {
    static final String DESERTER_CAMP = "deserter_camp", GANG_HIDEOUT = "gang_hideout", GANG_BUILDING = BuildingHideout.TYPE;
    static final ResourceLocation OWNER = ApiSupport.id("hostile_hideout");
    private static final ResourceLocation CLEARED = ApiSupport.id("hideout_cleared");
    private static final long GUARD_TICKS = 168_000L;
    /** Hideouts whose occupants a nearby player has seen alive. ponytail: transient, a restart re-arms on the next visit. */
    private static final Set<UUID> SEEN_ALIVE = new HashSet<>();

    private HostileHideouts() {}

    static boolean is(String type) { return DESERTER_CAMP.equals(type) || GANG_HIDEOUT.equals(type) || GANG_BUILDING.equals(type); }

    /** Draw weights of {deserter camp, gang hideout, gang building} for a world day (an ordinary encounter weighs 1). */
    static double[] weights(int day, int declineDay, int collapseDay) {
        double camp = day < declineDay ? 0 : day < collapseDay ? 1 : 1.5;
        return new double[] {camp, day >= 6 ? 2 : 0, day >= 6 ? 2 : 0};
    }

    /** Builds the hideout around the barrel already placed at {@code pos}; false leaves cleanup to the caller. */
    static boolean place(ServerLevel level, BlockPos pos, String type, int rotation, int tier, UUID id) {
        List<Mob> occupants = new ArrayList<>();
        int count = 3 + ThreadLocalRandom.current().nextInt(2);
        if (DESERTER_CAMP.equals(type)) {
            if (!EncounterScenery.build(level, pos, "military", rotation)) return false;
            LazarusEvents.stockCheckpoint(level, pos, rotation, tier, true);
            int[][] spots = {{5, 9}, {10, 9}, {15, 4}, {17, 4}};
            for (int i = 0; i < count; i++) {
                BlockPos at = EncounterScenery.militaryPosition(pos, rotation, spots[i][0], 1, spots[i][1]);
                if (LazarusEvents.spawn(level, at, i % 2 == 0 ? "apocalypsenow:soldiers" : "apocalypsenow:soldiers_02", id, true) instanceof Mob mob)
                    occupants.add(mob);
            }
            MilitaryCollapse.log(level, pos, rotation, HostileLore.note(HostileLore.DESERTER, ThreadLocalRandom.current().nextInt(5)));
        } else {
            if (!EncounterScenery.build(level, pos, "cache", rotation)) return false;
            // The gang's stash always holds one of their patches on top of the loot.
            if (level.getBlockEntity(pos) instanceof Container stash && stash.getContainerSize() > 0) {
                stash.setItem(stash.getContainerSize() - 1, HostileLore.patch(level, pos));
            }
            BlockPos actors = EncounterScenery.offset(pos, rotation, EncounterScenery.RADIUS + 1, 1);
            for (int i = 0; i < count; i++)
                if (LazarusEvents.spawn(level, actors.offset(i % 2, 0, i / 2), "apocalypsenow:looter_" + (1 + ThreadLocalRandom.current().nextInt(5)),
                        id, true) instanceof Mob mob) occupants.add(mob);
        }
        if (occupants.isEmpty()) return false;
        for (Mob mob : occupants) {
            ApiSupport.log("hideout guard", NpcApi.assign(mob, new ScenarioAssignment(OWNER, id, ScenarioOrder.GUARD_AREA,
                    GlobalPos.of(level.dimension(), pos), null, 10, GUARD_TICKS, 4)));
            if (DESERTER_CAMP.equals(type)) NpcApi.armScenarioActor(mob, OWNER, id); // gang members keep their own odds
        }
        return true;
    }

    /** Intercepted radio chatter that gives the hideout away. */
    static String radio(ServerLevel level, BlockPos pos, String type) {
        if (DESERTER_CAMP.equals(type)) return "Intercepted on an old military channel: deserters arguing over rations at a camp";
        return "Intercepted chatter: the " + HostileGroups.gang(level, pos) + " are holed up "
                + (GANG_BUILDING.equals(type) ? "inside a building" : "at their stash");
    }

    static String label(String type) { return DESERTER_CAMP.equals(type) ? "deserter camp" : "gang hideout"; }

    /** Every 100 ticks: pays the players present when a seen hideout has no occupant left, and ends it. */
    static void tick(ServerLevel level, EventData data) {
        for (EventData.Encounter encounter : List.copyOf(data.encounters)) {
            if (!is(encounter.type) || !level.isLoaded(encounter.pos)) continue;
            Vec3 center = Vec3.atCenterOf(encounter.pos);
            List<ServerPlayer> present = level.players().stream()
                    .filter(player -> !player.isSpectator() && player.distanceToSqr(center) < 32 * 32).toList();
            if (present.isEmpty()) continue;
            String tag = LazarusEvents.ID + ":" + encounter.id;
            int alive = level.getEntitiesOfClass(Mob.class, new AABB(encounter.pos).inflate(40),
                    mob -> mob.isAlive() && mob.getTags().contains(tag)).size();
            if (alive > 0) {
                SEEN_ALIVE.add(encounter.id);
                continue;
            }
            if (!SEEN_ALIVE.remove(encounter.id)) continue;
            boolean camp = DESERTER_CAMP.equals(encounter.type);
            for (ServerPlayer player : present) {
                if (camp) NpcApi.adjustReputation(player, "military", 2, CLEARED, encounter.id);
                else NpcApi.adjustPoliticalReputation(player, "wardens", 2, CLEARED, encounter.id);
                player.sendSystemMessage(Component.literal((camp ? "Deserter camp" : "Gang hideout") + " cleared. "
                        + (camp ? "The military" : "The Wardens") + " will hear of it.").withStyle(style -> style.withColor(0xD4C068)));
            }
            data.remove(encounter.id);
        }
    }
}
