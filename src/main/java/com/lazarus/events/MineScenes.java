package com.lazarus.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/** Furnishes an existing gallery without requiring a five-by-three rectangle through its supports. */
final class MineScenes {
    private MineScenes() {}

    static TerritoryEvents.Scene find(ServerLevel level, BlockPos anchor, int below, java.util.function.Predicate<BlockPos> allowed) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -below; dy <= 8; dy++) for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) {
            BlockPos at = anchor.offset(dx, dy, dz);
            if (GasVolumes.floor(level, at) && GasVolumes.roof(level, at)) candidates.add(at);
        }
        candidates.sort(Comparator.comparingDouble(anchor::distSqr));
        Set<String> searched = new HashSet<>();
        for (BlockPos at : candidates) {
            if (!allowed.test(at)) continue;
            for (boolean x : new boolean[]{true, false}) {
                var scene = TerritoryEvents.miners(at, x);
                if (TerritoryEvents.fits(level, scene)) return scene;
            }
            String bucket = (at.getX() >> 3) + ":" + at.getY() + ":" + (at.getZ() >> 3);
            if (!searched.add(bucket)) continue;
            var adapted = gallery(level, at);
            if (adapted != null) return adapted;
        }
        return null;
    }

    static TerritoryEvents.Scene gallery(ServerLevel level, BlockPos start) {
        if (!GasVolumes.floor(level, start) || !GasVolumes.roof(level, start)) return null;
        Set<BlockPos> cells = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        cells.add(start); queue.add(start);
        while (!queue.isEmpty() && cells.size() < 192) {
            BlockPos p = queue.remove();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = p.relative(d);
                if (Math.abs(next.getX() - start.getX()) > 8 || Math.abs(next.getZ() - start.getZ()) > 8
                        || cells.contains(next) || !GasVolumes.floor(level, next) || !GasVolumes.roof(level, next)) continue;
                cells.add(next); queue.add(next);
            }
        }
        if (cells.size() < 10) return null;
        List<BlockPos> ordered = cells.stream().sorted(Comparator.<BlockPos>comparingDouble(start::distSqr).thenComparingLong(BlockPos::asLong)).toList();
        for (BlockPos rail : ordered.stream().limit(24).toList()) for (Direction along : new Direction[]{Direction.EAST, Direction.SOUTH}) {
            List<BlockPos> rails = List.of(rail, rail.relative(along), rail.relative(along, 2));
            if (!cells.containsAll(rails)) continue;
            Set<BlockPos> walking = new HashSet<>(cells);
            List<BlockPos> furniture = new ArrayList<>();
            for (BlockPos candidate : ordered) {
                if (rails.contains(candidate) || candidate.equals(start)) continue;
                // Containers and solid lamps must not cut the only route through a narrow gallery.
                walking.remove(candidate);
                if (!connected(walking)) { walking.add(candidate); continue; }
                furniture.add(candidate);
                if (furniture.size() == 3) break;
            }
            if (furniture.size() != 3) continue;
            List<BlockPos> actors = ordered.stream().filter(p -> !furniture.contains(p) && !rails.contains(p)).limit(3).toList();
            if (actors.size() < 3) continue;
            var blocks = new LinkedHashMap<BlockPos, BlockState>();
            for (BlockPos p : rails) blocks.put(p, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE,
                    along == Direction.EAST ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH));
            blocks.put(furniture.get(0), Blocks.CHEST.defaultBlockState());
            blocks.put(furniture.get(1), Blocks.LECTERN.defaultBlockState());
            blocks.put(furniture.get(2), Blocks.REDSTONE_LAMP.defaultBlockState());
            var scene = new TerritoryEvents.Scene(start, along == Direction.EAST, blocks, actors,
                    List.of(furniture.get(0)), furniture.get(1), rails.get(1), 0, start);
            if (TerritoryEvents.fits(level, scene)) return scene;
        }
        return null;
    }

    private static boolean connected(Set<BlockPos> cells) {
        if (cells.isEmpty()) return false;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = cells.iterator().next(); seen.add(start); queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.remove();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = p.relative(d);
                if (cells.contains(next) && seen.add(next)) queue.add(next);
            }
        }
        return seen.size() == cells.size();
    }
}
