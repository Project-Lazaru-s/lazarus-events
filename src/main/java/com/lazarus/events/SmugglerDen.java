package com.lazarus.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * The local gang's smuggling den in a stretch of CityWorld sewer: the tunnel is cut off with iron bars on both sides
 * and a single door, and holds the gang's best goods (weapons and ammunition, medicine, drink and smokes) under the
 * guard of a few members. Cleared like any hideout.
 */
final class SmugglerDen {
    static final String TYPE = "smuggler_den";
    /** The stretch of sewer the den takes. */
    static final int MAX_CELLS = 80, REACH = 10;
    /** More ways on than this and the space is a hall, not a tunnel (a 3-wide tunnel cut at both ends has 6). */
    private static final int MAX_GAPS = 10;

    private SmugglerDen() {}

    /** The sewer stretch around {@code start}, or null when there is no enclosed space. */
    static BuildingHideout.Room space(ServerLevel level, BlockPos start) { return BuildingHideout.room(level, start, MAX_CELLS, REACH); }

    /** Gates the stretch, stocks it and stations the gang; false when nothing could be placed. */
    static boolean place(ServerLevel level, BuildingHideout.Room room, int tier, UUID id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BuildingHideout.Cells layout = BuildingHideout.cells(level, room);
        List<BlockPos> walls = new ArrayList<>(layout.walls());
        if (walls.size() < 5 || !gate(level, room)) return false;
        Collections.shuffle(walls, random);
        stock(level, walls.get(0), "weaponairdrop" + tier, HostileLore.patch(level, walls.get(0)));
        stock(level, walls.get(1), "medicalairdrop" + tier, ApiSupport.stack("apocalypsenow:cigarettes", 1 + random.nextInt(3)));
        stock(level, walls.get(2), "smallairdrop" + tier, random.nextBoolean()
                ? ApiSupport.stack("apocalypsenow:alcohol", 1 + random.nextInt(2)) : ApiSupport.stack("apocalypsenow:beer", 2 + random.nextInt(3)));
        BuildingHideout.put(level, walls.get(3), ApiSupport.state("apocalypsenow:military_crate", Blocks.BARREL.defaultBlockState()));
        BuildingHideout.put(level, walls.get(4), Blocks.LANTERN.defaultBlockState());
        for (int i = 5; i < Math.min(walls.size(), 8); i++)
            BuildingHideout.put(level, walls.get(i), ApiSupport.state(i % 2 == 0 ? "apocalypsenow:wooden_crate" : "apocalypsenow:cardboard_box",
                    Blocks.BARREL.defaultBlockState()));
        List<BlockPos> spawns = new ArrayList<>(layout.free().isEmpty() ? walls.subList(Math.min(8, walls.size()), walls.size()) : layout.free());
        spawns.removeIf(pos -> !BuildingHideout.passable(level, pos));
        Collections.shuffle(spawns, random);
        List<Mob> guards = new ArrayList<>();
        for (int i = 0, count = 3 + random.nextInt(2); i < Math.min(count, spawns.size()); i++)
            if (LazarusEvents.spawn(level, spawns.get(i), "apocalypsenow:looter_" + (1 + random.nextInt(5)), id, true) instanceof Mob mob)
                guards.add(mob);
        for (Mob mob : guards)
            ApiSupport.log("smuggler guard", NpcApi.assign(mob, new ScenarioAssignment(HostileHideouts.OWNER, id,
                    ScenarioOrder.GUARD_AREA, GlobalPos.of(level.dimension(), room.center()), null, 8, 168_000L, 4)));
        return !guards.isEmpty();
    }

    /**
     * Where the tunnel runs on past the stretch, bars go up two high; one gap gets a door instead. Openings to the
     * street (manholes, stairs) are left as they are. False, touching nothing, when the space is no tunnel: an open
     * hall would need a fence of bars across it.
     */
    static boolean gate(ServerLevel level, BuildingHideout.Room room) {
        Set<BlockPos> cells = new HashSet<>(room.cells());
        List<BlockPos> gaps = new ArrayList<>();
        List<Direction> ways = new ArrayList<>();
        for (BlockPos cell : room.cells())
            for (Direction out : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(out);
                if (cells.contains(next) || room.openings().contains(next) || gaps.contains(next)) continue;
                if (BuildingHideout.passable(level, next) && BuildingHideout.passable(level, next.above())
                        && level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP)) {
                    gaps.add(next);
                    ways.add(out);
                }
            }
        if (gaps.isEmpty() || gaps.size() > MAX_GAPS) return false;
        BlockState bars = Blocks.IRON_BARS.defaultBlockState();
        List<BlockPos> placed = new ArrayList<>();
        for (int i = 0; i < gaps.size(); i++) {
            BlockPos gap = gaps.get(i);
            if (i == 0) { door(level, gap, ways.get(i)); continue; }
            level.setBlock(gap, bars, 3);
            level.setBlock(gap.above(), bars, 3);
            placed.add(gap);
            placed.add(gap.above());
        }
        for (BlockPos pos : placed) level.setBlock(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos), 3);
        return true;
    }

    private static void door(ServerLevel level, BlockPos pos, Direction out) {
        BlockState door = ApiSupport.state("apocalypsenow:military_door", Blocks.SPRUCE_DOOR.defaultBlockState());
        if (!door.hasProperty(DoorBlock.HALF) || !door.hasProperty(DoorBlock.FACING)) door = Blocks.SPRUCE_DOOR.defaultBlockState();
        door = door.setValue(DoorBlock.FACING, out);
        level.setBlock(pos, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }

    private static void stock(ServerLevel level, BlockPos pos, String loot, ItemStack extra) {
        if (!BuildingHideout.passable(level, pos) || !level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 3)) return;
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity barrel)) return;
        ResourceLocation table = new ResourceLocation("dyairdrop", "chests/" + loot);
        if (level.getServer().getLootData().getLootTable(table) != LootTable.EMPTY) barrel.setLootTable(table, ThreadLocalRandom.current().nextLong());
        if (!extra.isEmpty()) barrel.setItem(barrel.getContainerSize() - 1, extra);
    }
}
