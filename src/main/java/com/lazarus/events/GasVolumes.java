package com.lazarus.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** Bounded searches only read loaded cells. Walls, fluids and station boundaries stop expansion. */
final class GasVolumes {
    private GasVolumes() {}

    static boolean air(ServerLevel level, BlockPos p) {
        return level.hasChunkAt(p) && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getFluidState(p).isEmpty();
    }

    static boolean floor(ServerLevel level, BlockPos p) {
        return air(level, p) && air(level, p.above()) && level.hasChunkAt(p.below())
                && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
    }

    static boolean roof(ServerLevel level, BlockPos p) {
        if (level.canSeeSky(p)) return false;
        for (int y = 2; y <= 6; y++) if (!air(level, p.above(y))) return true;
        return false;
    }

    /** A connected floor slice, at most 59 blocks long; explicit cut faces remain valid exits under a roof. */
    static BuildingHideout.Room tunnel(ServerLevel level, BlockPos start, Predicate<BlockPos> allowed) {
        if (!floor(level, start) || !roof(level, start) || !allowed.test(start)) return null;
        Set<BlockPos> seen = new HashSet<>(), exits = new HashSet<>();
        List<BlockPos> cells = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start); seen.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.remove();
            cells.add(p);
            if (cells.size() > 800) return null; // never truncate an open hall into a falsely enclosed zone
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (!seen.add(n) || !floor(level, n)) continue;
                if (Math.abs(n.getX() - start.getX()) + Math.abs(n.getZ() - start.getZ()) > 29
                        || !roof(level, n) || !allowed.test(n)) { exits.add(n); continue; }
                queue.add(n);
            }
        }
        if (cells.size() < 12 || exits.size() < 2) return null;
        return new BuildingHideout.Room(start.immutable(), List.copyOf(cells), Set.copyOf(exits));
    }

    /** Natural gas uses real air voxels, so it follows irregular galleries and never crosses a wall. */
    static Set<Long> pocket(ServerLevel level, BlockPos start) {
        if (!air(level, start) || level.canSeeSky(start)) return Set.of();
        Set<BlockPos> seen = new HashSet<>();
        Set<Long> cells = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start); seen.add(start);
        while (!queue.isEmpty() && cells.size() < 512) {
            BlockPos p = queue.remove(); cells.add(p.asLong());
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (!seen.add(n) || Math.abs(n.getX() - start.getX()) > 8
                        || Math.abs(n.getZ() - start.getZ()) > 8 || Math.abs(n.getY() - start.getY()) > 3
                        || !air(level, n) || level.canSeeSky(n)) continue;
                queue.add(n);
            }
        }
        return cells.size() < 4 ? Set.of() : Set.copyOf(cells);
    }

    static AABB bounds(Set<Long> cells) {
        double x = Double.MAX_VALUE, y = x, z = x, xx = -x, yy = -x, zz = -x;
        for (long v : cells) {
            BlockPos p = BlockPos.of(v);
            x = Math.min(x, p.getX()); y = Math.min(y, p.getY()); z = Math.min(z, p.getZ());
            xx = Math.max(xx, p.getX() + 1); yy = Math.max(yy, p.getY() + 2); zz = Math.max(zz, p.getZ() + 1);
        }
        return new AABB(x, y, z, xx, yy, zz);
    }
}
