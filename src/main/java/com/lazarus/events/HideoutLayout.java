package com.lazarus.events;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How a hideout is laid out as a place to live: which rooms it takes (through the building's doors), what each room
 * is for, the walkways kept clear between its doors, and the furniture kits set down around them.
 */
final class HideoutLayout {
    /** What each room is for, by how many rooms the hideout has; rooms are sorted largest first. */
    private static final String[][][] USES = {
            {{"kitchen", "dorm", "storage", "workshop", "decor"}},
            {{"kitchen", "workshop", "guard", "decor"}, {"dorm", "storage"}},
            {{"kitchen", "guard", "decor"}, {"dorm", "workshop"}, {"storage", "decor"}},
            {{"kitchen", "guard", "decor"}, {"dorm", "decor"}, {"workshop"}, {"storage"}}};

    /** The furnished hideout: cells kept clear or used, the room meant for storage, and where occupants can stand. */
    record Furnished(Set<BlockPos> taken, BuildingHideout.Room storage, List<BlockPos> standing) {}

    private HideoutLayout() {}

    /**
     * The room around {@code start} and up to {@code max - 1} more reached through its doors (doors stop the room's
     * flood fill), each a room of its own; the first is the one the hideout was found in.
     */
    static List<BuildingHideout.Room> rooms(ServerLevel level, BuildingHideout.Room first, int max) {
        List<BuildingHideout.Room> rooms = new ArrayList<>(List.of(first));
        Set<BlockPos> known = new HashSet<>(first.cells());
        for (int i = 0; i < rooms.size() && rooms.size() < max; i++)
            for (BlockPos cell : rooms.get(i).cells())
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos door = cell.relative(side), beyond = door.relative(side);
                    if (rooms.size() >= max || !(level.getBlockState(door).getBlock() instanceof DoorBlock) || known.contains(beyond)) continue;
                    BuildingHideout.Room next = BuildingHideout.room(level, beyond);
                    if (next == null || next.cells().stream().anyMatch(known::contains)) continue;
                    rooms.add(next);
                    known.addAll(next.cells());
                }
        return rooms;
    }

    /**
     * Clears the rooms and furnishes them by use: the largest is the common room (kitchen, guard post), then dorm,
     * workshop and storage; one room alone holds a bit of everything. Walkways between each room's doors stay clear.
     */
    static Furnished furnish(ServerLevel level, List<BuildingHideout.Room> rooms, int tier, Set<BlockPos> taken) {
        List<BuildingHideout.Room> bySize = new ArrayList<>(rooms);
        bySize.sort(Comparator.comparingInt((BuildingHideout.Room room) -> room.cells().size()).reversed());
        for (BuildingHideout.Room room : bySize) {
            Quarantine.clear(level, room);
            List<BlockPos> entries = entries(level, room);
            if (entries.isEmpty()) entries = List.of(room.center());
            reserve(walkway(level, room.cells(), entries.get(0), entries.size() > 1 ? entries.get(1) : null), taken);
            for (int i = 2; i < entries.size(); i++) reserve(walkway(level, room.cells(), entries.get(0), entries.get(i)), taken);
            for (BlockPos entry : entries) reserve(List.of(entry), taken);
        }
        String[][] uses = USES[Math.min(bySize.size(), USES.length) - 1];
        for (int i = 0; i < uses.length; i++)
            for (String use : uses[i]) furnishOne(level, use, bySize.get(i).cells(), taken, tier);
        List<BlockPos> standing = new ArrayList<>();
        for (BuildingHideout.Room room : rooms)
            for (BlockPos cell : room.cells())
                if (BuildingHideout.passable(level, cell) && BuildingHideout.passable(level, cell.above())) standing.add(cell);
        return new Furnished(taken, bySize.get(Math.min(bySize.size(), USES.length) - 1), standing);
    }

    /** Cells just inside a door or a doorway to the street. */
    static List<BlockPos> entries(ServerLevel level, BuildingHideout.Room room) {
        List<BlockPos> entries = new ArrayList<>();
        for (BlockPos cell : room.cells())
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (room.openings().contains(next) || level.getBlockState(next).getBlock() instanceof DoorBlock) {
                    entries.add(cell);
                    break;
                }
            }
        return entries;
    }

    private static void reserve(List<BlockPos> path, Set<BlockPos> taken) {
        for (BlockPos step : path) {
            taken.add(step);
            taken.add(step.above());
        }
    }

    /**
     * The walkway from {@code from} to {@code to} (or to the farthest cell when null): the cheapest path where cells
     * against a wall cost less, so it hugs one side and leaves the other side's depth to furniture.
     */
    static List<BlockPos> walkway(ServerLevel level, Collection<BlockPos> floor, BlockPos from, BlockPos to) {
        Set<BlockPos> cells = new HashSet<>(floor);
        Map<BlockPos, Integer> cost = new HashMap<>();
        Map<BlockPos, BlockPos> back = new HashMap<>();
        PriorityQueue<BlockPos> queue = new PriorityQueue<>(Comparator.comparingInt(cost::get));
        cost.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (cell.equals(to)) break;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (!cells.contains(next) || !BuildingHideout.passable(level, next)) continue;
                boolean byWall = false;
                for (Direction around : Direction.Plane.HORIZONTAL) byWall |= !BuildingHideout.passable(level, next.relative(around));
                int total = cost.get(cell) + (byWall ? 1 : 3);
                if (total < cost.getOrDefault(next, Integer.MAX_VALUE)) {
                    cost.put(next, total);
                    back.put(next, cell);
                    queue.remove(next);
                    queue.add(next);
                }
            }
        }
        BlockPos end = to != null && cost.containsKey(to) ? to : cells.stream().max(Comparator.comparingDouble(from::distSqr)).orElse(from);
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos step = end; step != null; step = back.get(step)) path.add(step);
        return path;
    }

    /** Sets down one random kit of the use that fits on the floor; false when none fits. */
    static boolean furnishOne(ServerLevel level, String use, List<BlockPos> floor, Set<BlockPos> taken, int tier) {
        if (floor.isEmpty()) return false;
        List<HideoutKits.Kit> kits = new ArrayList<>(HideoutKits.tagged(level.getServer(), use));
        Collections.shuffle(kits, ThreadLocalRandom.current());
        for (HideoutKits.Kit kit : kits) if (HideoutKits.furnish(level, kit, floor, taken, tier)) return true;
        return false;
    }

    /** A free cell against a wall, from the first list that has one, now taken; null when none is left. */
    @SafeVarargs
    static BlockPos freeWall(ServerLevel level, Set<BlockPos> taken, Collection<BlockPos>... where) {
        for (Collection<BlockPos> cells : where)
            for (BlockPos cell : cells) {
                if (taken.contains(cell) || !BuildingHideout.passable(level, cell)) continue;
                for (Direction side : Direction.Plane.HORIZONTAL)
                    if (!BuildingHideout.passable(level, cell.relative(side))) {
                        taken.add(cell);
                        return cell;
                    }
            }
        return null;
    }

    /** Spruce planks over most of a stone floor. */
    static void planks(ServerLevel level, Collection<BlockPos> floor) {
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (BlockPos cell : floor)
            if (level.getBlockState(cell.below()).isCollisionShapeFullBlock(level, cell.below()) && ThreadLocalRandom.current().nextFloat() < 0.8f)
                level.setBlock(cell.below(), plank, 3);
    }

    /** Cobwebs high in a few spots against the walls, never over a walkway. */
    static void cobwebs(ServerLevel level, BuildingHideout.Room room, Set<BlockPos> taken) {
        for (BlockPos cell : BuildingHideout.cells(level, room).walls()) {
            BlockPos high = cell.above(2);
            if (ThreadLocalRandom.current().nextFloat() < 0.15f && !taken.contains(cell) && level.getBlockState(high).isAir())
                level.setBlock(high, Blocks.COBWEB.defaultBlockState(), 3);
        }
    }
}
