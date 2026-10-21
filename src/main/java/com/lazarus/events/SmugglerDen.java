package com.lazarus.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * The local gang's smuggling den in a stretch of CityWorld sewer: the tunnel is cut off with iron bars on both sides
 * and a single door, and the gang lives in it. From the door inwards it runs guard post, workshop, kitchen, dorm and
 * storage (furniture kits), along a walkway kept clear against one wall, under wooden beams with hanging lanterns,
 * over a plank floor, with plank bridges across the water channels. The best goods (weapons and ammunition, medicine,
 * drink and smokes) sit deepest in, guarded by a few members. Cleared like any hideout.
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

    /** The uses along the stretch from the door inwards, with where each one ends (as a share of the length). */
    private static final String[][] USES = {{"guard"}, {"workshop"}, {"kitchen", "camp"}, {"dorm"}, {"storage"}};
    private static final double[] USE_ENDS = {0.15, 0.35, 0.6, 0.8, 1.01};

    /** Gates the stretch, furnishes it as a place to live and stations the gang; false when nothing could be placed. */
    static boolean place(ServerLevel level, BuildingHideout.Room room, int tier, UUID id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (BuildingHideout.cells(level, room).walls().size() < 5) return false;
        Quarantine.clear(level, room); // old crates and junk give way to the den
        BlockPos door = gate(level, room);
        if (door == null) return false;
        Set<BlockPos> cells = new HashSet<>(room.cells());
        BlockPos inside = room.cells().stream().min(Comparator.comparingDouble(door::distSqr)).orElse(room.center());
        List<BlockPos> walkway = HideoutLayout.walkway(level, room.cells(), inside, null);
        Set<BlockPos> taken = new HashSet<>();
        for (BlockPos step : walkway) { taken.add(step); taken.add(step.above()); }
        List<List<BlockPos>> zones = zones(room, door);
        for (int i = 0; i < USES.length; i++)
            for (String use : USES[i]) HideoutLayout.furnishOne(level, use, zones.get(i), taken, tier);
        HideoutLayout.furnishOne(level, "decor", room.cells(), taken, tier);
        stock(level, HideoutLayout.freeWall(level, taken, zones.get(4), cells), "weaponairdrop" + tier, null);
        stock(level, HideoutLayout.freeWall(level, taken, zones.get(3), cells), "medicalairdrop" + tier,
                ApiSupport.stack("apocalypsenow:cigarettes", 1 + random.nextInt(3)));
        stock(level, HideoutLayout.freeWall(level, taken, zones.get(2), cells), "smallairdrop" + tier, random.nextBoolean()
                ? ApiSupport.stack("apocalypsenow:alcohol", 1 + random.nextInt(2)) : ApiSupport.stack("apocalypsenow:beer", 2 + random.nextInt(3)));
        HideoutLayout.planks(level, zones.get(2));
        HideoutLayout.planks(level, zones.get(3));
        beams(level, room, taken);
        HideoutLayout.cobwebs(level, room, taken);
        bridges(level, room);
        List<BlockPos> spawns = new ArrayList<>(room.cells());
        spawns.removeIf(pos -> taken.contains(pos) && !walkway.contains(pos)
                || !BuildingHideout.passable(level, pos) || !BuildingHideout.passable(level, pos.above()));
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
     * Where the tunnel runs on past the stretch, bars go up two high; one gap gets a door instead, which is returned.
     * Openings to the street (manholes, stairs) are left as they are. Null, touching nothing, when the space is no
     * tunnel: an open hall would need a fence of bars across it.
     */
    static BlockPos gate(ServerLevel level, BuildingHideout.Room room) {
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
        if (gaps.isEmpty() || gaps.size() > MAX_GAPS) return null;
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
        return gaps.get(0);
    }

    /** The cells of each use, cut along the stretch's long axis from the door's end. */
    static List<List<BlockPos>> zones(BuildingHideout.Room room, BlockPos door) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos cell : room.cells()) {
            minX = Math.min(minX, cell.getX()); maxX = Math.max(maxX, cell.getX());
            minZ = Math.min(minZ, cell.getZ()); maxZ = Math.max(maxZ, cell.getZ());
        }
        boolean alongX = maxX - minX >= maxZ - minZ;
        int min = alongX ? minX : minZ, max = alongX ? maxX : maxZ, doorAt = alongX ? door.getX() : door.getZ();
        boolean fromMax = Math.abs(doorAt - max) < Math.abs(doorAt - min);
        List<List<BlockPos>> zones = new ArrayList<>();
        for (int i = 0; i < USES.length; i++) zones.add(new ArrayList<>());
        for (BlockPos cell : room.cells()) {
            double t = max == min ? 0 : ((alongX ? cell.getX() : cell.getZ()) - min) / (double) (max - min);
            if (fromMax) t = 1 - t;
            int use = 0;
            while (t >= USE_ENDS[use]) use++;
            zones.get(use).add(cell);
        }
        return zones;
    }

    /**
     * A beam of stripped spruce across the tunnel every fourth block along it, right under the ceiling, with a lantern
     * hanging from every other beam over a cell off the walkway.
     */
    private static void beams(ServerLevel level, BuildingHideout.Room room, Set<BlockPos> taken) {
        int minX = room.cells().stream().mapToInt(BlockPos::getX).min().orElse(0), maxX = room.cells().stream().mapToInt(BlockPos::getX).max().orElse(0);
        int minZ = room.cells().stream().mapToInt(BlockPos::getZ).min().orElse(0), maxZ = room.cells().stream().mapToInt(BlockPos::getZ).max().orElse(0);
        boolean alongX = maxX - minX >= maxZ - minZ;
        BlockState beam = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,
                alongX ? Direction.Axis.Z : Direction.Axis.X);
        Set<Integer> lit = new HashSet<>();
        for (BlockPos cell : room.cells()) {
            int along = alongX ? cell.getX() : cell.getZ();
            if (Math.floorMod(along, 4) != 0) continue;
            BlockPos top = cell.above(2);
            if (!level.getBlockState(top).isAir() || taken.contains(top) || BuildingHideout.passable(level, cell.above(3))) continue;
            level.setBlock(top, beam, 3);
            if (Math.floorMod(along, 8) == 0 && !taken.contains(cell) && level.getBlockState(cell.above()).isAir() && lit.add(along)) {
                level.setBlock(cell.above(), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 3);
                taken.add(cell.above());
            }
        }
    }

    /**
     * Plank bridges over the water channels beside the den: where a cell looks across 1 to 3 blocks of water to dry
     * floor, the water gets a waterlogged spruce top slab, flush with the floor. At most two bridges, two wide when
     * the cell beside also crosses.
     */
    static int bridges(ServerLevel level, BuildingHideout.Room room) {
        Set<BlockPos> cells = new HashSet<>(room.cells()), decked = new HashSet<>();
        BlockState slab = ApiSupport.state("minecraft:spruce_slab", Blocks.SPRUCE_SLAB.defaultBlockState())
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP)
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
        int built = 0;
        List<BlockPos> order = new ArrayList<>(room.cells());
        Collections.shuffle(order, ThreadLocalRandom.current());
        for (BlockPos cell : order) {
            if (built >= 2) break;
            for (Direction out : Direction.Plane.HORIZONTAL) {
                List<BlockPos> span = crossing(level, cell, out, cells);
                if (span.isEmpty() || span.stream().anyMatch(decked::contains)) continue;
                List<BlockPos> beside = crossing(level, cell.relative(out.getClockWise()), out, cells);
                if (beside.size() == span.size() && cells.contains(cell.relative(out.getClockWise()))) span.addAll(beside);
                for (BlockPos water : span) level.setBlock(water, slab, 3);
                decked.addAll(span);
                built++;
                break;
            }
        }
        return built;
    }

    /** The water blocks a bridge from {@code cell} going {@code out} would cover, or empty when there is no crossing. */
    private static List<BlockPos> crossing(ServerLevel level, BlockPos cell, Direction out, Set<BlockPos> cells) {
        List<BlockPos> water = new ArrayList<>();
        for (int k = 1; k <= 4; k++) {
            BlockPos at = cell.relative(out, k);
            if (!BuildingHideout.passable(level, at) || !BuildingHideout.passable(level, at.above())) return List.of();
            BlockState under = level.getBlockState(at.below());
            if (under.is(Blocks.WATER)) { water.add(at.below()); continue; }
            boolean dry = under.isFaceSturdy(level, at.below(), Direction.UP);
            return dry && !water.isEmpty() ? water : List.of(); // four blocks of water is a river, not a channel
        }
        return List.of();
    }

    private static void door(ServerLevel level, BlockPos pos, Direction out) {
        BlockState door = ApiSupport.state("apocalypsenow:military_door", Blocks.SPRUCE_DOOR.defaultBlockState());
        if (!door.hasProperty(DoorBlock.HALF) || !door.hasProperty(DoorBlock.FACING)) door = Blocks.SPRUCE_DOOR.defaultBlockState();
        door = door.setValue(DoorBlock.FACING, out);
        level.setBlock(pos, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(pos.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
    }

    private static void stock(ServerLevel level, BlockPos pos, String loot, ItemStack extra) {
        if (pos == null || !BuildingHideout.passable(level, pos) || !level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 3)) return;
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity barrel)) return;
        ResourceLocation table = new ResourceLocation("dyairdrop", "chests/" + loot);
        if (level.getServer().getLootData().getLootTable(table) != LootTable.EMPTY) barrel.setLootTable(table, ThreadLocalRandom.current().nextLong());
        ItemStack topping = extra != null ? extra : HostileLore.patch(level, pos); // the weapon stash carries the gang's patch
        if (!topping.isEmpty()) barrel.setItem(barrel.getContainerSize() - 1, topping);
    }
}
