package com.lazarus.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.NpcApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * A zombie nest: flesh pustules grown in a building room that keep producing infected while a player is near. Every
 * pustule broken drops an infected sample and slows the nest; when the last one goes, the nest dies and pays. Nests
 * are persistent encounters: they never expire on their own.
 */
final class ZombieNest {
    static final String TYPE = "zombie_nest";
    static final int MAX_ALIVE = 6, ACTIVE_RANGE = 48, REWARD_RANGE = 32;
    // One reason per faction: the API pays a reason only once per encounter.
    private static final ResourceLocation CLEARED_MILITARY = ApiSupport.id("nest_destroyed_military"),
            CLEARED_HAVEN = ApiSupport.id("nest_destroyed_haven");
    private static final String PUSTULES = "Pustules", INITIAL = "Initial", NEXT_SPAWN = "NextSpawn";

    private ZombieNest() {}

    /**
     * The pustule block. Not Dual Hitbox Zombies' flesh block: that one is zombie bait, eaten (and removed) by the very
     * infected the nest spawns. Nests grown with it are converted on their next tick.
     */
    static BlockState pustule() { return Blocks.NETHER_WART_BLOCK.defaultBlockState(); }

    private static final BlockState LEGACY = ApiSupport.state("dualhitboxzombies:flesh_block", Blocks.AIR.defaultBlockState());

    static boolean isPustule(BlockState state) {
        return state.is(pustule().getBlock()) || !LEGACY.isAir() && state.is(LEGACY.getBlock());
    }

    /** Grows 3-5 pustules and some webs against the walls of the room; null when the room is too small. */
    static List<BlockPos> place(ServerLevel level, BuildingHideout.Room room) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BuildingHideout.Cells cells = BuildingHideout.cells(level, room);
        List<BlockPos> walls = new ArrayList<>(cells.walls());
        if (walls.size() < 6) return null;
        Collections.shuffle(walls, random);
        List<BlockPos> pustules = new ArrayList<>();
        int count = 3 + random.nextInt(3);
        for (BlockPos cell : walls) {
            if (pustules.size() == count) break;
            if (pustules.stream().anyMatch(other -> other.closerThan(cell, 2.5))) continue; // spread them out
            if (level.setBlock(cell, pustule(), 3)) pustules.add(cell.immutable());
        }
        if (pustules.size() < 3) {
            pustules.forEach(pos -> level.removeBlock(pos, false));
            return null;
        }
        int webs = 0;
        for (BlockPos cell : walls) {
            if (webs == 3) break;
            if (pustules.contains(cell) || !BuildingHideout.passable(level, cell)) continue;
            BuildingHideout.put(level, cell.above(), Blocks.COBWEB.defaultBlockState());
            webs++;
        }
        return pustules;
    }

    static void record(EventData.Encounter encounter, List<BlockPos> pustules, long now) {
        encounter.data.putBoolean("Persistent", true);
        encounter.data.putLongArray(PUSTULES, pustules.stream().mapToLong(BlockPos::asLong).toArray());
        encounter.data.putInt(INITIAL, pustules.size());
        encounter.data.putLong(NEXT_SPAWN, now);
    }

    static List<BlockPos> pustules(EventData.Encounter encounter) {
        List<BlockPos> list = new ArrayList<>();
        for (long packed : encounter.data.getLongArray(PUSTULES)) list.add(BlockPos.of(packed));
        return list;
    }

    /** Ticks between spawns: about 20-30 s, slower as pustules are destroyed. */
    static int interval(int initial, int remaining) { return 400 + (initial - remaining) * 150; }

    /** The infected a nest produces, harder with world age. */
    static String spawnType(int day, ThreadLocalRandom random) {
        if (day >= 20 && random.nextFloat() < 0.1f) {
            String[] specials = {"undead_revamp2:theheavy", "undead_revamp2:bomber", "undead_revamp2:thespitter", "undead_revamp2:thehunter"};
            return specials[random.nextInt(specials.length)];
        }
        return switch (random.nextInt(3)) {
            case 0 -> "minecraft:zombie";
            case 1 -> "apocalypsenow:walker_" + (1 + random.nextInt(12));
            default -> "apocalypsenow:runner_" + (34 + random.nextInt(8));
        };
    }

    /** Every 100 ticks: drops pustules that are gone, kills the nest when none is left, and spawns near players. */
    static void tick(ServerLevel level, EventData data) {
        long now = level.getGameTime();
        for (EventData.Encounter encounter : List.copyOf(data.encounters)) {
            if (!TYPE.equals(encounter.type) || !level.isLoaded(encounter.pos)) continue;
            Vec3 center = Vec3.atCenterOf(encounter.pos);
            List<BlockPos> alive = new ArrayList<>();
            for (BlockPos pos : pustules(encounter)) {
                if (level.isLoaded(pos) && !isPustule(level.getBlockState(pos))) continue;
                if (level.isLoaded(pos) && !level.getBlockState(pos).is(pustule().getBlock())) level.setBlock(pos, pustule(), 3);
                alive.add(pos);
            }
            if (alive.size() != pustules(encounter).size()) {
                encounter.data.putLongArray(PUSTULES, alive.stream().mapToLong(BlockPos::asLong).toArray());
                data.setDirty();
            }
            if (alive.isEmpty()) {
                destroyed(level, data, encounter);
                continue;
            }
            List<ServerPlayer> near = level.players().stream()
                    .filter(player -> !player.isSpectator() && player.distanceToSqr(center) < ACTIVE_RANGE * ACTIVE_RANGE).toList();
            if (near.isEmpty()) continue;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockPos pos : alive)
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 6, 0.6, 0.4, 0.6, 0);
            if (random.nextInt(4) == 0) level.playSound(null, encounter.pos, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE, 0.8f, 0.6f);
            if (now < encounter.data.getLong(NEXT_SPAWN)) continue;
            encounter.data.putLong(NEXT_SPAWN, now + interval(encounter.data.getInt(INITIAL), alive.size()) + random.nextInt(200));
            data.setDirty();
            String tag = LazarusEvents.ID + ":" + encounter.id;
            if (level.getEntitiesOfClass(Mob.class, new AABB(encounter.pos).inflate(ACTIVE_RANGE),
                    mob -> mob.isAlive() && mob.getTags().contains(tag)).size() >= MAX_ALIVE) continue;
            BlockPos source = alive.get(random.nextInt(alive.size()));
            BlockPos at = null;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = source.relative(side);
                if (BuildingHideout.passable(level, next) && BuildingHideout.passable(level, next.above())) { at = next; break; }
            }
            if (at == null) continue;
            int day = (int) (level.getDayTime() / 24000);
            if (LazarusEvents.spawn(level, at, spawnType(day, random), encounter.id, false) == null)
                LazarusEvents.spawn(level, at, "minecraft:zombie", encounter.id, false);
        }
    }

    private static void destroyed(ServerLevel level, EventData data, EventData.Encounter encounter) {
        Vec3 center = Vec3.atCenterOf(encounter.pos);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.distanceToSqr(center) > REWARD_RANGE * REWARD_RANGE) continue;
            NpcApi.adjustReputation(player, "military", 2, CLEARED_MILITARY, encounter.id);
            NpcApi.adjustReputation(player, "haven", 2, CLEARED_HAVEN, encounter.id);
            player.sendSystemMessage(Component.literal("The nest is dead. The Military and the Haven will hear of it.")
                    .withStyle(style -> style.withColor(0xD4C068)));
        }
        data.remove(encounter.id);
    }

    static final class Listener {
        /** A broken pustule bursts into an infected sample. */
        @SubscribeEvent
        public void pustuleBroken(BlockEvent.BreakEvent event) {
            if (!(event.getLevel() instanceof ServerLevel level) || !isPustule(event.getState())) return;
            long packed = event.getPos().asLong();
            for (EventData.Encounter encounter : EventData.get(level).encounters) {
                if (!TYPE.equals(encounter.type)) continue;
                for (long pustule : encounter.data.getLongArray(PUSTULES)) {
                    if (pustule != packed) continue;
                    Block.popResource(level, event.getPos(), new ItemStack(VaccineProject.SAMPLE.get()));
                    level.playSound(null, event.getPos(), SoundEvents.SLIME_SQUISH, SoundSource.BLOCKS, 1f, 0.6f);
                    level.sendParticles(ParticleTypes.ITEM_SLIME, event.getPos().getX() + 0.5, event.getPos().getY() + 0.5,
                            event.getPos().getZ() + 0.5, 20, 0.4, 0.4, 0.4, 0.05);
                    return;
                }
            }
        }
    }
}
