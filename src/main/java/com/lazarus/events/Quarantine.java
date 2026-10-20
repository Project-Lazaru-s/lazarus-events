package com.lazarus.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.NpcApi;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * A military quarantine that failed. Outside the main doorway: a wire-fence perimeter with a sandbag gate, lit posts,
 * warning signs and a breach where it gave way; a decontamination post (shower, toxic barrels, stretcher, a box of
 * spent masks, a field radio still calling out); body bags, ransacked crates and infected soldiers in gas masks.
 * Inside: the gas from a leaking toxic barrel, an improvised infirmary, hazmat and civilian infected, a duty log and a
 * military stash at the back. Clearing every infected pays +2 Military; the gas runs its two days.
 */
final class Quarantine {
    static final String TYPE = "military_quarantine";
    private static final ResourceLocation CLEARED = ApiSupport.id("quarantine_cleared");
    static final String LEAK = "Leak", RADIO = "Radio";
    private static final String SEEN = "SeenAlive", DONE = "Cleared", CHATTER = "Chatter", LAST_CHATTER = "LastChatter";
    private static final int FENCE = 7, HALF = 6;

    private Quarantine() {}

    /** Furnishes the quarantine; returns what the encounter must remember (leak, radio), or null on failure. */
    static CompoundTag place(ServerLevel level, BuildingHideout.Room room, int tier, UUID id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockPos stash = GasZone.barrel(level, room, "weaponairdrop" + tier);
        if (stash == null) return null;
        if (level.getBlockEntity(stash) instanceof Container barrel && random.nextFloat() < 0.4f)
            barrel.setItem(barrel.getContainerSize() - 1, random.nextBoolean()
                    ? ApiSupport.stack("apocalypsenow:gas_mask_helmet_helmet", 1) : ApiSupport.stack(GasZone.FILTER.toString(), 2));
        CompoundTag memory = new CompoundTag();
        Set<BlockPos> cells = new HashSet<>(room.cells());
        List<Mob> infected = new ArrayList<>();
        infirmary(level, room, cells, stash, memory, infected, id);

        // The first street doorway gets the staging area; any other street opening is wired shut.
        Door main = streetDoor(level, room);
        BlockState wire = ApiSupport.state("apocalypsenow:barbed_wire", Blocks.COBWEB.defaultBlockState());
        BlockState barrier = ApiSupport.state("apocalypsenow:concrete_barrier", Blocks.STONE_BRICK_WALL.defaultBlockState());
        for (BlockPos opening : room.openings()) {
            Direction out = outward(opening, cells);
            if (out == null || main != null && opening.equals(main.at()) || !outdoors(level, opening)) continue;
            BuildingHideout.put(level, opening, wire);
            for (Direction side : new Direction[] {out.getClockWise(), out.getCounterClockWise()})
                if (!cells.contains(opening.relative(side))) BuildingHideout.put(level, opening.relative(side), facing(barrier, out));
        }
        if (main != null) staging(level, new Frame(main.at(), main.out(), cells), tier, memory, infected, id);
        return infected.isEmpty() ? null : memory;
    }

    /** Under open sky, or at least 24 clear blocks of it: the street, not a storey of the building. */
    static boolean outdoors(ServerLevel level, BlockPos pos) {
        if (pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())) return true;
        for (int dy = 1; dy <= 24; dy++) if (!level.getBlockState(pos.above(dy)).isAir()) return false;
        return true;
    }

    /** A way onto the street: the door block (or open doorway) at {@code at}, the street lying {@code out} of it. */
    record Door(BlockPos at, Direction out) {}

    /**
     * The room's way onto the street, or null when it has none. Building doors stop the room's flood fill, so door
     * blocks on its edge are checked first; then doorways with no door. Either way the two spots past it must be under
     * open sky: a stairwell or atrium is open above its first spot, but not a step further.
     */
    static Door streetDoor(ServerLevel level, BuildingHideout.Room room) {
        Set<BlockPos> cells = new HashSet<>(room.cells());
        for (BlockPos cell : room.cells())
            for (Direction out : Direction.Plane.HORIZONTAL) {
                BlockPos door = cell.relative(out);
                if (level.getBlockState(door).getBlock() instanceof DoorBlock && outdoors(level, door.relative(out))
                        && outdoors(level, door.relative(out, 2))) return new Door(door, out);
            }
        for (BlockPos opening : room.openings()) {
            Direction out = outward(opening, cells);
            if (out != null && outdoors(level, opening) && outdoors(level, opening.relative(out))) return new Door(opening, out);
        }
        return null;
    }

    /**
     * Empties the space for the scene: furniture, loot and clutter on and beside the floor cells go; walls, doors,
     * windows, stairs, slabs, fences, ladders and lights stay. Containers are emptied first so nothing spills.
     */
    static void clear(ServerLevel level, BuildingHideout.Room room) {
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos cell : room.cells())
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = cell.offset(dx, dy, dz);
                if (!seen.add(pos) || room.openings().contains(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || !level.getFluidState(pos).isEmpty() || keep(level, pos, state)) continue;
                if (level.getBlockEntity(pos) instanceof Container container) container.clearContent();
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
    }

    private static boolean keep(ServerLevel level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (level.getBlockEntity(pos) != null) return false; // chests, furnaces, barrels, signs...
        return state.isCollisionShapeFullBlock(level, pos) || state.getLightEmission(level, pos) > 0
                || block instanceof DoorBlock || block instanceof IronBarsBlock || block instanceof StairBlock || block instanceof SlabBlock
                || block instanceof TrapDoorBlock || block instanceof LadderBlock || block instanceof FenceBlock || block instanceof WallBlock;
    }

    static BlockState facing(BlockState state, Direction direction) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.setValue(BlockStateProperties.HORIZONTAL_FACING, direction) : state;
    }

    /** Beds against the walls, paperwork and shrouds on the floor, the leaking barrel, and the infected inside. */
    private static void infirmary(ServerLevel level, BuildingHideout.Room room, Set<BlockPos> cells, BlockPos stash,
                                  CompoundTag memory, List<Mob> infected, UUID id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BuildingHideout.Cells layout = BuildingHideout.cells(level, room);
        List<BlockPos> walls = new ArrayList<>(layout.walls());
        walls.remove(stash);
        Collections.shuffle(walls, random);
        if (!walls.isEmpty()) log(level, walls.remove(0));
        int beds = 0;
        for (BlockPos head : walls) if (beds < 3 && bed(level, head, cells)) beds++;
        List<BlockPos> floor = new ArrayList<>(layout.free().isEmpty() ? room.cells() : layout.free());
        floor.removeIf(pos -> !BuildingHideout.passable(level, pos));
        Collections.shuffle(floor, random);
        if (!floor.isEmpty()) {
            BlockPos leak = floor.remove(0);
            level.setBlock(leak, ApiSupport.state("apocalypsenow:toxic_waste_barrel", Blocks.GREEN_CONCRETE.defaultBlockState()), 3);
            memory.putLong(LEAK, leak.asLong());
        }
        BlockState paper = ApiSupport.state("apocalypsenow:paperstrash", Blocks.WHITE_CARPET.defaultBlockState());
        BlockState shroud = ApiSupport.state("apocalypsenow:corpsecloth", Blocks.WHITE_CARPET.defaultBlockState());
        for (int i = 0; i < 5 && !floor.isEmpty(); i++) BuildingHideout.put(level, floor.remove(0), i < 3 ? paper : shroud);
        List<BlockPos> spawns = new ArrayList<>(layout.free().isEmpty() ? room.cells() : layout.free());
        spawns.removeIf(pos -> !BuildingHideout.passable(level, pos) || !BuildingHideout.passable(level, pos.above()));
        Collections.shuffle(spawns, random);
        int hazmat = 2 + random.nextInt(2), patients = 1 + random.nextInt(2);
        for (BlockPos at : spawns) {
            if (hazmat + patients == 0) break;
            if (!(LazarusEvents.spawn(level, at, "minecraft:zombie", id, true) instanceof Mob zombie)) continue;
            if (hazmat-- > 0) {
                MilitaryCollapse.equip(zombie, EquipmentSlot.HEAD, "apocalypsenow:hazmat_suit_helmet");
                MilitaryCollapse.equip(zombie, EquipmentSlot.CHEST, "apocalypsenow:hazmat_suit_chestplate");
                MilitaryCollapse.equip(zombie, EquipmentSlot.LEGS, "apocalypsenow:hazmat_suit_leggings");
                MilitaryCollapse.equip(zombie, EquipmentSlot.FEET, "apocalypsenow:hazmat_suit_boots");
            } else {
                hazmat = 0;
                patients--; // a patient in whatever they were wearing
            }
            infected.add(zombie);
        }
    }

    /** Positions in front of a doorway: {@code k} blocks out, {@code j} blocks to the side. */
    private record Frame(BlockPos door, Direction out, Set<BlockPos> cells) {
        /** The ground spot near (k, j) outside the room, or null when there is none. */
        BlockPos at(ServerLevel level, int k, int j) {
            if (k <= 0) return null;
            BlockPos base = door.relative(out, k).relative(out.getClockWise(), j);
            for (int dy : new int[] {0, 1, -1, 2, -2}) {
                BlockPos pos = base.above(dy);
                if (!cells.contains(pos) && level.isLoaded(pos) && outdoors(level, pos) && BuildingHideout.passable(level, pos) && BuildingHideout.passable(level, pos.above())
                        && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) return pos;
            }
            return null;
        }

        BlockPos put(ServerLevel level, int k, int j, BlockState state) {
            BlockPos pos = at(level, k, j);
            if (pos != null) level.setBlock(pos, facing(state, out), 3);
            return pos;
        }

        /** Stacks {@code states} from the ground up at (k, j), stopping at the first blocked space; returns the placed spots. */
        List<BlockPos> stack(ServerLevel level, int k, int j, BlockState... states) {
            List<BlockPos> placed = new ArrayList<>();
            BlockPos pos = at(level, k, j);
            for (int i = 0; pos != null && i < states.length; i++, pos = pos.above()) {
                if (i > 0 && !BuildingHideout.passable(level, pos)) break;
                level.setBlock(pos, facing(states[i], out), 3);
                placed.add(pos);
            }
            return placed;
        }
    }

    /** Perimeter, decontamination tent, the dead and the infected guards in front of the main doorway. */
    private static void staging(ServerLevel level, Frame f, int tier, CompoundTag memory, List<Mob> infected, UUID id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockState fence = ApiSupport.state("apocalypsenow:wire_fence", Blocks.IRON_BARS.defaultBlockState());
        BlockState sandbagWall = ApiSupport.state("apocalypsenow:sandbagbarrier", Blocks.SANDSTONE_WALL.defaultBlockState());
        BlockState sandbags = ApiSupport.state("apocalypsenow:sandbags", Blocks.SANDSTONE_SLAB.defaultBlockState());
        BlockState wire = ApiSupport.state("apocalypsenow:barbed_wire", Blocks.COBWEB.defaultBlockState());
        BlockState toxic = ApiSupport.state("apocalypsenow:toxic_waste_barrel", Blocks.GREEN_CONCRETE.defaultBlockState());
        BlockState jersey = ApiSupport.state("apocalypsenow:concrete_barrier", Blocks.STONE_BRICK_WALL.defaultBlockState());
        BlockState lined = ApiSupport.state("apocalypsenow:lined_concrete_barrier", jersey);
        BlockState crate = ApiSupport.state("apocalypsenow:military_crate", Blocks.BARREL.defaultBlockState());
        BlockState lantern = Blocks.LANTERN.defaultBlockState();
        BlockState pole = Blocks.SPRUCE_FENCE.defaultBlockState();
        List<BlockPos> fences = new ArrayList<>(), roof = new ArrayList<>();

        // 1. Perimeter: a U of two-high wire fence topped with barbed wire, lit posts, a sandbag gate behind a
        // chicane of jersey barriers, warning signs, and a breach on one side where the fence was pushed down.
        int breachSide = random.nextBoolean() ? HALF : -HALF, breachAt = 2 + random.nextInt(FENCE - 4);
        List<int[]> line = new ArrayList<>();
        for (int k = 1; k < FENCE; k++) line.add(new int[] {k, -HALF});
        for (int j = -HALF; j <= HALF; j++) line.add(new int[] {FENCE, j});
        for (int k = FENCE - 1; k >= 1; k--) line.add(new int[] {k, HALF});
        for (int[] spot : line) {
            int k = spot[0], j = spot[1];
            if (k == FENCE && Math.abs(j) <= 2) {
                if (j != 0) f.stack(level, k, j, sandbagWall, sandbagWall);
                continue;
            }
            if (j == breachSide && (k == breachAt || k == breachAt + 1)) {
                f.put(level, k, j, sandbags);
                f.put(level, k, j + Integer.signum(j), wire);
                continue;
            }
            boolean post = (k == FENCE && (Math.abs(j) == HALF || Math.abs(j) == 3)) || (k == 1 && Math.abs(j) == HALF);
            List<BlockPos> placed = f.stack(level, k, j, fence, fence, post ? lantern : wire);
            fences.addAll(placed.subList(0, Math.min(2, placed.size())));
        }
        for (int j = -1; j <= 1; j++) f.put(level, FENCE + 2, j, j == 0 ? lined : jersey);
        int facing = RotationSegment.convertToSegment(f.out());
        signAt(level, f, FENCE + 1, -4, facing, "QUARANTINE", "AUTHORIZED", "PERSONNEL ONLY", "- US ARMY -");
        signAt(level, f, FENCE + 1, 4, facing, "BIOHAZARD", "LETHAL FORCE", "AUTHORIZED", "ZONE 4");
        signAt(level, f, 4, -1, facing, "DECON", "REQUIRED", "BEYOND", "THIS POINT");

        // 2. Decontamination tent against the facade: posts, a white roof, a concrete floor, two showers, toxic
        // barrels, a stretcher and a box of spent masks, with the way to the door left open.
        for (int k = 1; k <= 3; k++) for (int j = -3; j <= 3; j++) {
            BlockPos ground = f.at(level, k, j);
            if (ground == null) continue;
            if (level.getBlockState(ground.below()).isCollisionShapeFullBlock(level, ground.below()))
                level.setBlock(ground.below(), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 3);
            if (Math.abs(j) == 3 && k != 2) f.stack(level, k, j, pole, pole, pole);
            roof.add(ground.above(3)); // laid last: a roof would make the ground below read as indoors
        }
        f.put(level, 1, -2, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        f.put(level, 1, 2, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 2));
        f.put(level, 2, -2, toxic);
        BlockPos head = f.at(level, 2, 2), foot = f.at(level, 3, 2);
        if (foot != null && head != null && head.equals(foot.relative(f.out().getOpposite()))) {
            BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, f.out().getOpposite());
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
        }
        BlockPos masks = f.put(level, 3, -2, Blocks.BARREL.defaultBlockState());
        if (masks != null && level.getBlockEntity(masks) instanceof Container box)
            for (int i = 0; i < 2 + random.nextInt(2); i++) {
                ItemStack spent = ApiSupport.stack("apocalypsenow:gas_mask_helmet_helmet", 1);
                if (spent.isDamageableItem()) spent.setDamageValue(spent.getMaxDamage() - 4 - random.nextInt(8));
                box.setItem(i * 3, spent);
            }

        // 3. The failure: a row of body bags, the radio post on a crate, ransacked crates and a fallen soldier's gear.
        BlockState bag = ApiSupport.state("apocalypsenow:corpsesack", Blocks.BLACK_WOOL.defaultBlockState());
        int[][] bags = {{5, 2}, {5, 3}, {5, 4}, {6, 2}, {6, 3}};
        for (int i = 0; i < 3 + random.nextInt(3); i++) {
            BlockPos pos = f.at(level, bags[i][0], bags[i][1]);
            if (pos != null) level.setBlock(pos, facing(bag, f.out().getClockWise()), 3);
        }
        List<BlockPos> radioPost = f.stack(level, 4, -4, crate,
                ApiSupport.state("apocalypsenow:military_communication_radio", Blocks.NOTE_BLOCK.defaultBlockState()));
        if (!radioPost.isEmpty()) memory.putLong(RADIO, radioPost.get(radioPost.size() - 1).asLong());
        f.put(level, 6, -4, crate);
        f.stack(level, 6, -5, crate, crate);
        f.put(level, 5, -5, sandbags);
        BlockPos gear = f.at(level, 5, -4);
        if (gear != null) fallenSoldier(level, gear, tier);

        // 4. The quarantine soldiers, still at their posts in their masks.
        int[][] posts = {{5, 0}, {6, -2}, {FENCE - 1, 1}};
        for (int i = 0; i < 2 + random.nextInt(2); i++) {
            BlockPos at = f.at(level, posts[i][0], posts[i][1]);
            if (at == null || !(LazarusEvents.spawn(level, at, "minecraft:zombie", id, true) instanceof Mob soldier)) continue;
            MilitaryCollapse.equip(soldier, EquipmentSlot.HEAD, "apocalypsenow:gas_mask_helmet_helmet");
            MilitaryCollapse.equip(soldier, EquipmentSlot.CHEST, "apocalypsenow:military_bulletprooft_vest_chestplate");
            MilitaryCollapse.equip(soldier, EquipmentSlot.LEGS, "apocalypsenow:usa_armor_leggings");
            infected.add(soldier);
        }

        for (BlockPos pos : roof) if (level.getBlockState(pos).isAir()) level.setBlock(pos, Blocks.WHITE_WOOL.defaultBlockState(), 3);
        // The wire fence joins like iron bars: connect it once every piece is down.
        for (BlockPos pos : fences) level.setBlock(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos), 3);
    }

    private static void signAt(ServerLevel level, Frame f, int k, int j, int rotation, String... lines) {
        BlockPos pos = f.at(level, k, j);
        if (pos != null) BuildingHideout.sign(level, pos, rotation, lines);
    }

    /** A white bed with its head at {@code head} against a wall and its foot in the room; false when it does not fit. */
    private static boolean bed(ServerLevel level, BlockPos head, Set<BlockPos> cells) {
        if (!BuildingHideout.passable(level, head)) return false;
        for (Direction in : Direction.Plane.HORIZONTAL) {
            BlockPos foot = head.relative(in);
            if (!cells.contains(foot) || !BuildingHideout.passable(level, foot) || BuildingHideout.passable(level, head.relative(in.getOpposite())))
                continue;
            BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, in.getOpposite());
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
            return true;
        }
        return false;
    }

    /** The way from the room out through {@code opening}, or null when it touches no room cell. */
    private static Direction outward(BlockPos opening, Set<BlockPos> cells) {
        for (Direction direction : Direction.Plane.HORIZONTAL)
            if (cells.contains(opening.relative(direction.getOpposite()))) return direction;
        return null;
    }

    /** What is left of a quarantine soldier: a crate of his gear by the barricade. */
    private static void fallenSoldier(ServerLevel level, BlockPos pos, int tier) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest)) return;
        ResourceLocation table = new ResourceLocation("dyairdrop", "chests/smallairdrop" + tier);
        if (level.getServer().getLootData().getLootTable(table) != LootTable.EMPTY)
            chest.setLootTable(table, ThreadLocalRandom.current().nextLong());
        chest.setItem(chest.getContainerSize() - 1, ApiSupport.stack("apocalypsenow:usa_armor_helmet", 1));
    }

    private static void log(ServerLevel level, BlockPos pos) {
        if (!BuildingHideout.passable(level, pos)) return;
        BlockState lectern = Blocks.LECTERN.defaultBlockState();
        if (level.setBlock(pos, lectern, 3))
            LecternBlock.tryPlaceBook(null, level, pos, lectern, MilitaryCollapse.written(LOGS.get(ThreadLocalRandom.current().nextInt(LOGS.size()))));
    }

    /**
     * Every 100 ticks: the field radio calls out to players close to it, and the players present are paid once
     * the infected someone saw are all dead. The gas stays.
     */
    static void tick(ServerLevel level, EventData data) {
        long now = level.getGameTime();
        for (EventData.Encounter encounter : data.encounters) {
            if (!TYPE.equals(encounter.type) || !level.isLoaded(encounter.pos)) continue;
            chatter(level, encounter, now);
            if (encounter.data.getBoolean(DONE)) continue;
            Vec3 center = Vec3.atCenterOf(encounter.pos);
            List<ServerPlayer> present = level.players().stream()
                    .filter(player -> !player.isSpectator() && player.distanceToSqr(center) < 32 * 32).toList();
            if (present.isEmpty()) continue;
            if (!ApiSupport.actors(level, encounter.id, center, 40).isEmpty()) {
                encounter.data.putBoolean(SEEN, true);
                continue;
            }
            if (!encounter.data.getBoolean(SEEN)) continue;
            encounter.data.putBoolean(DONE, true);
            data.setDirty();
            for (ServerPlayer player : present) {
                NpcApi.adjustReputation(player, "military", 2, CLEARED, encounter.id);
                player.sendSystemMessage(Component.literal("Quarantine cleared. The military will hear of it.")
                        .withStyle(style -> style.withColor(0xD4C068)));
            }
        }
    }

    /** The next looped call from the field radio, at most every 15 s, for players within 12 blocks of it. */
    static boolean chatter(ServerLevel level, EventData.Encounter encounter, long now) {
        if (!encounter.data.contains(RADIO) || now - encounter.data.getLong(LAST_CHATTER) < 300) return false;
        BlockPos radio = BlockPos.of(encounter.data.getLong(RADIO));
        List<ServerPlayer> near = level.players().stream()
                .filter(player -> !player.isSpectator() && player.distanceToSqr(Vec3.atCenterOf(radio)) < 12 * 12).toList();
        if (near.isEmpty()) return false;
        int index = encounter.data.getInt(CHATTER);
        encounter.data.putInt(CHATTER, index + 1);
        encounter.data.putLong(LAST_CHATTER, now);
        level.playSound(null, radio, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 0.6f, 0.5f);
        for (ServerPlayer player : near)
            player.sendSystemMessage(LazarusEvents.radioText(CHATTER_LINES.get(index % CHATTER_LINES.size()), radio));
        return true;
    }

    static final List<String> CHATTER_LINES = List.of(
            "...Containment Unit 4, respond. Do not open the doors. Repeat, do not open the doors...",
            "...decon is down, I say again, decon is down. Anyone outside the wire, fall back...",
            "...perimeter breached, the fence is down. All units, fall back to the evac point...",
            "...if anyone hears this, the patients are not sick anymore. They are hungry...",
            "...Zone 4 command to any station. Quarantine has failed. Requesting... (static)");

    /** Title, author, pages. */
    static final List<String[]> LOGS = List.of(
            new String[] {"Quarantine Log, Day 19", "Cpl. Reyes",
                    "Sealed the block at 0600. Forty civilians inside, hazmat team of six. Orders: nobody in, nobody out.",
                    "The vents started hissing at noon. Two of the team tore their masks off. They weren't coughing. They were laughing."},
            new String[] {"Containment Notes", "Dr. Amsel, attached",
                    "The agent is airborne in enclosed spaces and settles low. Filters last minutes, not hours. Request more cartridges.",
                    "Cartridges denied. Command says the quarantine is a success. I am writing this through a cracked visor."},
            new String[] {"Last Entry", "Pvt. Okafor",
                    "Sarge locked the doors from outside and strung the wire. We're still in here. The suits don't stop the bites.",
                    "If you read this, the good gear is in the back. Bring a mask. Bring two."});
}
