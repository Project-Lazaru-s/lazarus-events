package com.lazarus.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * A gang hideout inside an existing building (CityWorld or any roofed room): the room is found by a bounded flood
 * fill over roofed floor cells, lightly furnished as gang turf (stash, crates, sleeping mats, a lantern and a turf
 * sign) without touching walls, and occupied by members of the local gang.
 */
final class BuildingHideout {
    static final String TYPE = "gang_building_hideout";
    private static final int MIN_CELLS = 12, MAX_CELLS = 160, REACH = 12, MAX_OPENINGS = 12, CEILING = 6;

    /** Roofed floor cells of one room (feet level) and the passable cells that lead outside. */
    record Room(BlockPos center, List<BlockPos> cells, Set<BlockPos> openings) {}

    private BuildingHideout() {}

    static boolean passable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    /** Two blocks of head room, a sturdy floor and a roof somewhere above. */
    private static boolean floorCell(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && passable(level, pos) && passable(level, pos.above())
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /** Indoors: no open sky and a ceiling within a few blocks of the head, as in a building storey. */
    private static boolean roofed(ServerLevel level, BlockPos pos) {
        if (level.canSeeSky(pos)) return false;
        for (int dy = 2; dy <= CEILING; dy++) if (!passable(level, pos.above(dy))) return true;
        return false;
    }

    /** The room containing {@code start}, or null when it is not inside a building. */
    static Room room(ServerLevel level, BlockPos start) { return room(level, start, MAX_CELLS, REACH); }

    static Room room(ServerLevel level, BlockPos start, int maxCells, int reach) {
        if (!floorCell(level, start) || !roofed(level, start)) return null;
        List<BlockPos> cells = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(), openings = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && cells.size() < maxCells) {
            BlockPos cell = queue.poll();
            cells.add(cell);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (!seen.add(next) || Math.abs(next.getX() - start.getX()) > reach || Math.abs(next.getZ() - start.getZ()) > reach)
                    continue;
                if (!floorCell(level, next)) continue;
                if (!roofed(level, next)) openings.add(next); // a doorway or window to the street
                else queue.add(next);
            }
        }
        if (cells.size() < MIN_CELLS || openings.size() > MAX_OPENINGS) return null;
        long x = 0, z = 0;
        for (BlockPos cell : cells) { x += cell.getX(); z += cell.getZ(); }
        BlockPos middle = new BlockPos((int) (x / cells.size()), start.getY(), (int) (z / cells.size()));
        BlockPos center = cells.stream().min(java.util.Comparator.comparingDouble(c -> c.distSqr(middle))).orElse(start);
        return new Room(center, cells, openings);
    }

    /** A random roofed room 30-80 blocks from the player, away from recent encounters and out of their reach. */
    static Room find(ServerLevel level, ServerPlayer player, EventData data) { return find(level, player, data, 30, 80); }

    static Room find(ServerLevel level, ServerPlayer player, EventData data, int minDistance, int maxDistance) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 96; attempt++) {
            double angle = random.nextDouble(Math.PI * 2), distance = random.nextDouble(minDistance, maxDistance);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            // Walk down from the roof to the first floor with head room under a ceiling: an upper storey or the ground floor.
            for (int y = top - 2; y > Math.max(level.getMinBuildHeight() + 1, top - 40); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!floorCell(level, pos) || !roofed(level, pos)) continue;
                if (Math.abs(y - player.getBlockY()) > 24 || data.near(pos)) break;
                Room room = room(level, pos);
                if (room != null && player.distanceToSqr(Vec3.atCenterOf(room.center())) > 24 * 24) return room;
                break;
            }
        }
        return null;
    }

    /** Furnishes the room and stations the gang; false when nothing could be placed. */
    /** Room cells against a wall (for furniture) and in the open, leaving every doorway clear. */
    record Cells(List<BlockPos> walls, List<BlockPos> free) {}

    static Cells cells(ServerLevel level, Room room) {
        List<BlockPos> walls = new ArrayList<>(), free = new ArrayList<>();
        for (BlockPos cell : room.cells()) {
            boolean doorway = false, wall = false;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (room.openings().contains(next)) doorway = true;
                if (!passable(level, next)) wall = true;
            }
            if (doorway) continue;
            (wall ? walls : free).add(cell);
        }
        return new Cells(walls, free);
    }

    /**
     * Turns the room, and up to three more behind its doors, into the gang's home: cleared, furnished by use with
     * the hideout kits around clear walkways, the stash (with a gang patch) in the storage room, a turf sign in the
     * main room, and the members on guard.
     */
    static boolean place(ServerLevel level, Room room, int tier, UUID id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String gang = HostileGroups.gang(level, room.center());
        if (cells(level, room).walls().size() < 4) return false;
        List<Room> rooms = HideoutLayout.rooms(level, room, 4);
        HideoutLayout.Furnished home = HideoutLayout.furnish(level, rooms, tier, new java.util.HashSet<>(), "gang");
        BlockPos stash = HideoutLayout.freeWall(level, home.taken(), home.storage().cells(), room.cells());
        if (stash == null || !level.setBlock(stash, Blocks.BARREL.defaultBlockState(), 3)) return false;
        ResourceLocation table = new ResourceLocation("dyairdrop", "chests/smallairdrop" + tier);
        if (level.getBlockEntity(stash) instanceof RandomizableContainerBlockEntity barrel) {
            if (level.getServer().getLootData().getLootTable(table) != LootTable.EMPTY)
                barrel.setLootTable(table, random.nextLong());
            barrel.setItem(barrel.getContainerSize() - 1, HostileLore.patch(level, stash));
        }
        room.cells().stream().filter(cell -> !home.taken().contains(cell) && passable(level, cell))
                .min(java.util.Comparator.comparingDouble(cell -> cell.distSqr(room.center())))
                .ifPresent(at -> {
                    sign(level, at, gang.toUpperCase(), "TURF", "", "KEEP OUT");
                    home.taken().add(at);
                });
        for (Room part : rooms) HideoutLayout.cobwebs(level, part, home.taken());
        List<BlockPos> spawns = new ArrayList<>(home.standing());
        spawns.removeIf(cell -> !passable(level, cell));
        Collections.shuffle(spawns, random);
        List<Mob> occupants = new ArrayList<>();
        int count = 3 + random.nextInt(2);
        for (int i = 0; i < Math.min(count, spawns.size()); i++)
            if (LazarusEvents.spawn(level, spawns.get(i), "apocalypsenow:looter_" + (1 + random.nextInt(5)), id, true) instanceof Mob mob)
                occupants.add(mob);
        if (occupants.isEmpty()) return false;
        for (Mob mob : occupants)
            ApiSupport.log("building hideout guard", NpcApi.assign(mob, new ScenarioAssignment(HostileHideouts.OWNER, id,
                    ScenarioOrder.GUARD_AREA, GlobalPos.of(level.dimension(), room.center()), null, 12, 168_000L, 4)));
        return true;
    }

    static void put(ServerLevel level, BlockPos pos, BlockState state) {
        if (pos != null && passable(level, pos)) level.setBlock(pos, state, 3);
    }

    /** A waxed standing sign with up to four lines. */
    static void sign(ServerLevel level, BlockPos pos, String... lines) { sign(level, pos, ThreadLocalRandom.current().nextInt(16), lines); }

    static void sign(ServerLevel level, BlockPos pos, int rotation, String... lines) {
        if (!passable(level, pos)) return;
        level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i]));
            sign.setText(text, true);
            sign.setWaxed(true);
            sign.setChanged();
        }
    }
}
