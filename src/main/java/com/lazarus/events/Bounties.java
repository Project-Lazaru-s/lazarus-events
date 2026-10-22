package com.lazarus.events;

import com.lazarus.npcs.api.HostileGroups;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wanted players (bounty, B1). A faction (Rovers, Wardens, Military) or a gang puts a price on a player whose standing
 * with it sinks to {@link #WANTED}, and sends a small squad to hunt them: spawned out of sight 56-80 blocks away, it
 * follows the player's trail until it is killed, loses them or a day passes. One hunt per player at a time, then a
 * cooldown per hunter group. The Haven never hunts.
 *
 * Faction standing is Lazarus NPCs' reputation. Gangs have their own standing here, kept with the player through death:
 * killing a looter costs standing with the gang that holds those streets (or the gang the looter hunts for), and it
 * recovers a little every day, like personal faction penalties.
 */
final class Bounties {
    /** Same threshold as Lazarus NPCs' enemy standing (NpcHostility.ENEMY): factions attack on sight from here. */
    static final int WANTED = -40;
    static final List<String> FACTIONS = List.of("rovers", "wardens", "military");
    static final int GANG_KILL = -8, GANG_RECOVERY_PER_DAY = 5;
    static final long HUNT_TICKS = 24_000, CHECK_TICKS = 200, LOST_DISTANCE = 160;
    static final int MIN_DISTANCE = 56, SPREAD = 25;
    /** Persistent tag on a gang hunter with the gang it hunts for (Lazarus NPCs shows it in the looter's name). */
    static final String GANG_TAG = "LazarusGang";
    /** Lazarus NPCs' hostile-human state: a provoked looter attacks without the toll warning. */
    private static final String HUMAN_STATE = "LazarusHumanEncounter";
    private static final String ROOT = "LazarusEventsBounty";
    static final ResourceLocation OWNER = ApiSupport.id("bounty_hunt");

    private Bounties() {}

    // --- standing ---

    /** Squad size by how deep the standing is. */
    static int squadSize(int standing) { return standing <= -90 ? 4 : standing <= -70 ? 3 : 2; }

    /** Gang standing after some days: losses recover {@link #GANG_RECOVERY_PER_DAY} a day, never past 0. */
    static int recovered(int standing, long days) {
        return standing >= 0 || days <= 0 ? standing : (int) Math.min(0, standing + days * GANG_RECOVERY_PER_DAY);
    }

    static String gangTarget(String gang) { return "gang:" + gang; }
    static String factionTarget(String faction) { return "faction:" + faction; }
    static boolean isGang(String target) { return target.startsWith("gang:"); }
    static String name(String target) { return target.substring(target.indexOf(':') + 1); }

    static String label(String target) {
        if (isGang(target)) return "the " + name(target);
        NpcFactions.Definition definition = NpcFactions.get(name(target));
        return "the " + (definition == null ? name(target) : definition.label());
    }

    /** The bounty data lives in the player's persisted tag, which Forge keeps through death. */
    private static CompoundTag root(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getCompound(ROOT);
    }

    private static void save(ServerPlayer player, CompoundTag root) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.put(ROOT, root);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private static long day(ServerPlayer player) { return player.server.overworld().getGameTime() / 24_000; }

    /** Standing with a gang, recovered up to today. */
    static int gangStanding(ServerPlayer player, String gang) {
        CompoundTag root = root(player);
        CompoundTag gangs = root.getCompound("Gangs");
        long today = day(player), last = root.getLong("RecoverDay");
        if (today > last && !gangs.isEmpty()) {
            for (String key : gangs.getAllKeys()) gangs.putInt(key, recovered(gangs.getInt(key), today - last));
            root.put("Gangs", gangs);
        }
        root.putLong("RecoverDay", today);
        save(player, root);
        return gangs.getInt(gang);
    }

    static void setGangStanding(ServerPlayer player, String gang, int standing) {
        gangStanding(player, gang); // brings the others up to date first
        CompoundTag root = root(player);
        CompoundTag gangs = root.getCompound("Gangs");
        gangs.putInt(gang, Math.max(-100, Math.min(100, standing)));
        root.put("Gangs", gangs);
        save(player, root);
    }

    static int standing(ServerPlayer player, String target) {
        return isGang(target) ? gangStanding(player, name(target)) : NpcApi.reputation(player, name(target));
    }

    /** Every group that wants this player, with its standing. */
    static Map<String, Integer> wanted(ServerPlayer player) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (String faction : FACTIONS) {
            int rep = NpcApi.reputation(player, faction);
            if (rep <= WANTED) wanted.put(factionTarget(faction), rep);
        }
        gangStanding(player, ""); // recover first
        CompoundTag gangs = root(player).getCompound("Gangs");
        for (String gang : gangs.getAllKeys())
            if (gangs.getInt(gang) <= WANTED) wanted.put(gangTarget(gang), gangs.getInt(gang));
        return wanted;
    }

    /** The gang a looter belongs to: the one it hunts for, or the one that holds the streets it stands on. */
    static String gangOf(ServerLevel level, LivingEntity looter) {
        String tagged = looter.getPersistentData().getString(GANG_TAG);
        return tagged.isEmpty() ? HostileGroups.gang(level, looter.blockPosition()) : tagged;
    }

    private static boolean looter(Entity entity) {
        var id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && "apocalypsenow".equals(id.getNamespace()) && id.getPath().startsWith("looter_");
    }

    // --- hunts ---

    /** Squad spawn spot: loaded open ground 56-80 blocks away and out of the player's view, or null. */
    static BlockPos huntSite(ServerLevel level, ServerPlayer player) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            BlockPos at = ApiSupport.ring(level, player.blockPosition(), MIN_DISTANCE + level.random.nextInt(SPREAD), angle);
            if (at == null || !LazarusEvents.safe(level, at)) continue;
            Vec3 toward = Vec3.atCenterOf(at).subtract(player.getEyePosition()).normalize();
            if (player.getLookAngle().dot(toward) > 0.25) continue;
            return at;
        }
        return null;
    }

    /** Spawns the squad at {@code center} and sends it after the player; empty when any member failed. */
    static List<Mob> spawnSquad(ServerLevel level, BlockPos center, ServerPlayer player, String target, int size, UUID id) {
        List<Mob> squad = new ArrayList<>();
        String group = name(target);
        NpcFactions.Definition faction = isGang(target) ? null : NpcFactions.get(group);
        for (int i = 0; i < size; i++) {
            BlockPos at = center.offset(i % 2, 0, i / 2);
            if (!LazarusEvents.safe(level, at)) at = center;
            String type = isGang(target) ? "apocalypsenow:looter_" + (1 + level.random.nextInt(5))
                    : "military".equals(group) ? "apocalypsenow:military_0" + (1 + i % 2)
                    : "apocalypsenow:survivor_" + faction.variant(i);
            if (!(LazarusEvents.spawn(level, at, type, id, true) instanceof Mob mob)) {
                ApiSupport.LOGGER.warn("bounty hunter {} could not spawn at {}", type, at);
                break;
            }
            squad.add(mob);
            // Apocalypse Now soldiers already belong to the Military; survivors are assigned to their faction
            if (faction != null && !"military".equals(group) && !ok("bounty hunter faction", NpcApi.assignFaction(mob, group))) break;
            if (isGang(target)) {
                mob.getPersistentData().putString(GANG_TAG, group);
                provoke(mob, player, level.getGameTime());
            }
            if (!ok("bounty hunter order", NpcApi.assign(mob, new ScenarioAssignment(OWNER, id, ScenarioOrder.TRAVEL,
                    GlobalPos.of(level.dimension(), center), null, 6, HUNT_TICKS, 3)))
                    || !ok("bounty hunter trail", NpcApi.setTravelRoute(mob, OWNER, id, trail(level, center, player.blockPosition()))))
                break;
            // faction survivors and looters get a firearm; Apocalypse Now soldiers keep their own kit
            if (!"military".equals(group)) ApiSupport.log("bounty hunter weapon", NpcApi.armScenarioActor(mob, OWNER, id));
        }
        if (squad.size() < size) {
            squad.forEach(Mob::discard);
            return List.of();
        }
        return squad;
    }

    private static boolean ok(String what, com.lazarus.npcs.api.ApiResult result) {
        if (!result.ok()) ApiSupport.LOGGER.warn("{} refused: {}", what, result.message());
        return result.ok();
    }

    /** A provoked looter skips Lazarus NPCs' toll warning and attacks this player on sight. */
    private static void provoke(Mob mob, ServerPlayer player, long now) {
        CompoundTag root = mob.getPersistentData().getCompound(HUMAN_STATE);
        CompoundTag state = root.getCompound(player.getUUID().toString());
        state.putBoolean("Provoked", true);
        state.putLong("WarnedAt", now);
        root.put(player.getUUID().toString(), state);
        mob.getPersistentData().put(HUMAN_STATE, root);
    }

    /**
     * Waypoints on the ground from {@code from} to the player, about 16 blocks apart, 4 to 8 of them as Lazarus NPCs'
     * travel routes require, ending at the player. Where the surface is far from the straight line between the two
     * heights (a roof, a cave), the point keeps the interpolated height instead.
     */
    static List<BlockPos> trail(ServerLevel level, BlockPos from, BlockPos to) {
        List<BlockPos> points = new ArrayList<>();
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        int steps = Math.max(4, Math.min(8, (int) Math.round(Math.sqrt(dx * dx + dz * dz) / 16)));
        for (int i = 1; i < steps; i++) {
            int x = from.getX() + (int) Math.round(dx * i / steps), z = from.getZ() + (int) Math.round(dz * i / steps);
            int line = from.getY() + (int) Math.round(dy * i / steps);
            int ground = level.isLoaded(new BlockPos(x, line, z))
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : line;
            points.add(new BlockPos(x, Math.abs(ground - line) <= 6 ? ground : line, z));
        }
        points.add(to);
        return points;
    }

    /** Starts a hunt now when a site is found; returns a message for the operator command. */
    static String start(ServerLevel level, ServerPlayer player, String target) {
        int standing = standing(player, target);
        BlockPos center = huntSite(level, player);
        if (center == null) return "No open, loaded ground 56-80 blocks away out of sight";
        UUID id = UUID.randomUUID();
        List<Mob> squad = spawnSquad(level, center, player, target, squadSize(standing), id);
        if (squad.isEmpty()) return "Could not place the squad at " + center.toShortString();
        CompoundTag root = root(player);
        CompoundTag hunt = new CompoundTag();
        hunt.putUUID("Id", id);
        hunt.putString("Target", target);
        hunt.putLong("Expires", level.getGameTime() + HUNT_TICKS);
        long[] members = new long[squad.size() * 2];
        for (int i = 0; i < squad.size(); i++) {
            members[i * 2] = squad.get(i).getUUID().getMostSignificantBits();
            members[i * 2 + 1] = squad.get(i).getUUID().getLeastSignificantBits();
        }
        hunt.putLongArray("Members", members);
        root.put("Hunt", hunt);
        root.putLong("Next_" + target, level.getGameTime() + LazarusEvents.bountyCooldown());
        save(player, root);
        player.sendSystemMessage(Component.literal("You are being hunted: " + squad.size() + " of " + label(target)
                + " are on your trail.").withStyle(style -> style.withColor(0xD46868)));
        LazarusEvents.radio(level, center, "Hunters from " + label(target) + " are tracking someone. Stay off the roads");
        return "Hunt " + id + " (" + squad.size() + " from " + label(target) + ") at " + center.toShortString();
    }

    private static List<Mob> members(ServerLevel level, CompoundTag hunt) {
        List<Mob> alive = new ArrayList<>();
        long[] ids = hunt.getLongArray("Members");
        for (int i = 0; i + 1 < ids.length; i += 2)
            if (level.getEntity(new UUID(ids[i], ids[i + 1])) instanceof Mob mob && mob.isAlive()) alive.add(mob);
        return alive;
    }

    /** Ends the player's hunt: survivors leave (discarded), with an optional message. */
    static void end(ServerLevel level, ServerPlayer player, String message) {
        CompoundTag root = root(player);
        if (!root.contains("Hunt")) return;
        for (Mob mob : members(level, root.getCompound("Hunt"))) mob.discard();
        root.remove("Hunt");
        save(player, root);
        if (message != null) player.sendSystemMessage(Component.literal(message).withStyle(style -> style.withColor(0xD4A185)));
    }

    /** Every few seconds: wanted notices, hunt upkeep (trail, end) and new hunts when the cooldown allows. */
    static void update(ServerLevel level, ServerPlayer player) {
        CompoundTag root = root(player);
        Map<String, Integer> wanted = wanted(player);
        CompoundTag flagged = root.getCompound("Wanted");
        for (String target : wanted.keySet())
            if (!flagged.getBoolean(target))
                player.sendSystemMessage(Component.literal(capitalize(label(target)) + " have put a price on your head. Expect hunters.")
                        .withStyle(style -> style.withColor(0xD46868)));
        for (String target : flagged.getAllKeys())
            if (!wanted.containsKey(target))
                player.sendSystemMessage(Component.literal(capitalize(label(target)) + " are no longer hunting you.")
                        .withStyle(style -> style.withColor(0xA9C79A)));
        CompoundTag now = new CompoundTag();
        wanted.keySet().forEach(target -> now.putBoolean(target, true));
        root = root(player);
        root.put("Wanted", now);
        save(player, root);

        if (root.contains("Hunt")) {
            CompoundTag hunt = root.getCompound("Hunt");
            List<Mob> squad = members(level, hunt);
            String target = hunt.getString("Target");
            if (squad.isEmpty()) {
                end(level, player, "The hunters from " + label(target) + " are down. More will come while you are wanted.");
                return;
            }
            boolean lost = squad.stream().allMatch(mob -> mob.distanceToSqr(player) > LOST_DISTANCE * LOST_DISTANCE);
            if (level.getGameTime() >= hunt.getLong("Expires") || lost) {
                end(level, player, "You lost the hunters from " + label(target) + ", for now.");
                return;
            }
            UUID id = hunt.getUUID("Id");
            for (Mob mob : squad)
                if (mob.getTarget() == null)
                    NpcApi.setTravelRoute(mob, OWNER, id, trail(level, mob.blockPosition(), player.blockPosition()));
            return;
        }
        if (!LazarusEvents.bountiesEnabled() || player.isCreative() || player.isSpectator()) return;
        String next = null;
        for (Map.Entry<String, Integer> entry : wanted.entrySet())
            if (level.getGameTime() >= root.getLong("Next_" + entry.getKey())
                    && (next == null || entry.getValue() < wanted.get(next))) next = entry.getKey();
        if (next != null) start(level, player, next);
    }

    private static String capitalize(String text) { return Character.toUpperCase(text.charAt(0)) + text.substring(1); }

    /** Status lines for the operator command. */
    static List<String> status(ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        Map<String, Integer> wanted = wanted(player);
        lines.add(wanted.isEmpty() ? "Not wanted." : "Wanted by: " + wanted);
        lines.add("Gang standing: " + root(player).getCompound("Gangs"));
        CompoundTag hunt = root(player).getCompound("Hunt");
        if (!hunt.isEmpty()) lines.add("Active hunt by " + label(hunt.getString("Target")) + ", "
                + members(player.serverLevel(), hunt).size() + " alive");
        return lines;
    }

    static final class Listener {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CHECK_TICKS != 71) return;
            ServerLevel level = event.getServer().overworld();
            for (ServerPlayer player : level.players())
                if (player.isAlive()) update(level, player);
        }

        /** Killing a looter costs standing with its gang; the hunted player dying ends the hunt. */
        @SubscribeEvent
        public void death(LivingDeathEvent event) {
            if (!(event.getEntity().level() instanceof ServerLevel level)) return;
            if (event.getEntity() instanceof ServerPlayer player) {
                end(level, player, null);
                return;
            }
            if (looter(event.getEntity()) && event.getSource().getEntity() instanceof ServerPlayer killer) {
                String gang = gangOf(level, event.getEntity());
                setGangStanding(killer, gang, gangStanding(killer, gang) + GANG_KILL);
            }
        }

        @SubscribeEvent
        public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer player && player.level().dimension() == Level.OVERWORLD)
                end(player.serverLevel(), player, null);
        }
    }
}
