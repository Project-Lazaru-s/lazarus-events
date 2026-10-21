package com.lazarus.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Territory discovery stays separate from arena-testable construction and gas geometry. */
final class TerritoryEvents {
    static final String METRO_GAS = "metro_gas", SHELTER = "metro_shelter", MINERS = "lost_miners";
    private TerritoryEvents() {}

    static boolean handles(String type) { return Set.of(METRO_GAS, SHELTER, MINERS).contains(type); }

    static void weights(Map<String, Double> weights, ServerLevel level, ServerPlayer player) {
        if (!CityWorldSites.available()) return;
        CityWorldSites.Territory territory = CityWorldSites.territory(level, player.blockPosition());
        if (territory == CityWorldSites.Territory.METRO_TUNNEL || territory == CityWorldSites.Territory.METRO_STATION) {
            if (TerritoryConfig.METRO_GAS.get()) weights.put(METRO_GAS, 6.0);
            if (TerritoryConfig.SHELTER.get()) weights.put(SHELTER, 6.0);
        }
        if ((territory == CityWorldSites.Territory.MINE || territory == CityWorldSites.Territory.CAVE) && TerritoryConfig.MINERS.get())
            weights.put(MINERS, 6.0);
    }

    static String attempt(ServerLevel level, ServerPlayer player, String type, boolean near, EventData data) {
        if (!CityWorldSites.available()) return "CityWorld is required for this territory event";
        if (METRO_GAS.equals(type)) {
            BuildingHideout.Room room = tunnel(level, player, near, data);
            if (room == null) return "No loaded metro tunnel with two exits found; stand on the tunnel floor";
            UUID id = UUID.randomUUID();
            if (!GasZone.placeMetro(level, room)) return "No safe position for the medical barrel";
            EventData.Encounter e = data.add(id, type, room.center(), level.getGameTime());
            GasZone.record(e, room, level.getGameTime()); data.setDirty();
            announce(level, e);
            return null;
        }
        Scene site = SHELTER.equals(type) ? station(level, player, near, data) : mine(level, player, near, data);
        if (site == null) {
            if (!SHELTER.equals(type)) return "No untouched, loaded CityWorld mine gallery has room for the miners";
            if (!CityWorldSites.inSubway(level, player.blockPosition())) return "Stand inside the metro before testing the shelter; check /lazarusevents where";
            List<BlockPos> nearest = CityWorldSites.findLots(level, player.blockPosition(), "SubwayStationLot", 8, 1);
            return "No free, loaded platform for the shelter" + (nearest.isEmpty() ? "; no station within 8 chunks"
                    : "; try the station near X " + nearest.get(0).getX() + ", Z " + nearest.get(0).getZ());
        }
        UUID id = UUID.randomUUID();
        if (!build(level, site, type, id)) return "The scene could not be placed safely";
        EventData.Encounter e = data.add(id, type, site.origin(), level.getGameTime());
        e.data.putBoolean("Persistent", true);
        e.data.putLongArray("Loot", site.loot().stream().mapToLong(BlockPos::asLong).toArray());
        data.sites.add(siteKey(type, site.site()));
        data.setDirty(); announce(level, e);
        return null;
    }

    private static void announce(ServerLevel level, EventData.Encounter e) {
        LazarusEvents.radio(level, e.pos, switch (e.type) {
            case METRO_GAS -> "Military band: chemical leak in the metro, line closed";
            case SHELTER -> "An old shelter on the platform has gone silent";
            default -> "The mining crew never returned; their supplies may still be below";
        });
    }

    private static BuildingHideout.Room tunnel(ServerLevel level, ServerPlayer player, boolean near, EventData data) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < (near ? 1 : 48); i++) {
            BlockPos p = player.blockPosition();
            if (!near) {
                double angle = r.nextDouble(Math.PI * 2), distance = r.nextDouble(30, 80);
                p = p.offset((int) (Math.cos(angle) * distance), 0, (int) (Math.sin(angle) * distance));
            }
            if (!level.hasChunkAt(p)) continue;
            Optional<CityWorldSites.Subway> info = CityWorldSites.subway(level, p);
            if (info.isEmpty() || info.get().station()) continue;
            CityWorldSites.Subway s = info.get();
            int[] ys = near ? new int[]{p.getY()} : new int[]{s.eastWestFloorY() + 1, s.northSouthFloorY() + 1};
            for (int y : ys) {
                BlockPos start = new BlockPos(p.getX(), y, p.getZ());
                if (!CityWorldSites.inSubway(level, start) || !near && (data.near(start) || !unseen(level, start))) continue;
                // Cache one reflection result per chunk for the entire flood fill.
                Map<Long, Optional<CityWorldSites.Subway>> chunks = new HashMap<>();
                Predicate<BlockPos> allowed = cell -> chunks.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(cell),
                        key -> CityWorldSites.subway(level, cell)).filter(v -> !v.station() && CityWorldSites.contains(v, cell.getY())).isPresent();
                BuildingHideout.Room room = GasVolumes.tunnel(level, start, allowed);
                if (room != null) return room;
            }
        }
        return null;
    }

    /** Floor origin is the first walkable platform cell (one block above the yellow edge/andesite). */
    private static Scene station(ServerLevel level, ServerPlayer player, boolean near, EventData data) {
        if (near && !CityWorldSites.inSubway(level, player.blockPosition())) return null;
        List<BlockPos> stations = new ArrayList<>();
        stations.add(player.blockPosition());
        // The station's platform extends into adjacent chunks that the API labels as tunnel.
        // A near test must resolve the station lot instead of requiring the player to stand in its central chunk.
        stations.addAll(CityWorldSites.findLots(level, player.blockPosition(), "SubwayStationLot", 8, 8));
        for (BlockPos at : stations) {
            if (!level.hasChunkAt(at)) continue;
            Optional<CityWorldSites.Subway> optional = CityWorldSites.subway(level, at);
            if (optional.isEmpty() || !optional.get().station()
                    || player.distanceToSqr(Vec3.atCenterOf(at)) > 128 * 128) continue;
            CityWorldSites.Subway s = optional.get();
            for (boolean x : new boolean[]{true, false}) {
                if (x ? !s.eastWest() : !s.northSouth()) continue;
                BlockPos origin = new BlockPos((at.getX() >> 4) << 4, (x ? s.eastWestFloorY() : s.northSouthFloorY()) + 2, (at.getZ() >> 4) << 4);
                if (data.sites.contains(siteKey(SHELTER, origin)) || !near && (data.near(origin)
                        || player.distanceToSqr(Vec3.atCenterOf(origin)) > 128 * 128 || !unseen(level, origin.offset(3, 0, 3)))) continue;
                // CityWorld stairs occupy the central chunk. Adjacent wide platform wings have no stair shaft.
                for (int shift : new int[]{-16, 16, 0}) {
                    BlockPos wing = x ? origin.offset(shift, 0, 0) : origin.offset(0, 0, shift);
                    if (!level.hasChunkAt(wing)) continue;
                    Scene scene = shelterOnPlatform(level, wing, x, Math.min(5, 1 + (int) (level.getDayTime() / 24000) / 10));
                    if (scene == null) continue;
                    scene = scene.reserve(origin);
                    if (!near && scene.spawns().stream().anyMatch(p -> !unseen(level, p))) continue;
                    if (fits(level, scene)) return scene;
                }
            }
        }
        return null;
    }

    /** Ruined platforms contain moss/cobwebs. Move actors along the same walkway instead of rejecting the whole shelter. */
    static Scene shelterOnPlatform(ServerLevel level, BlockPos origin, boolean x, int tier) {
        Scene scene = shelter(origin, x, tier);
        List<BlockPos> spawns = new ArrayList<>();
        for (int preferred : new int[]{2, 5, 8, 11}) {
            BlockPos chosen = null;
            for (int delta : new int[]{0, -1, 1, -2, 2}) {
                int along = preferred + delta;
                if (along < 1 || along > 14) continue;
                BlockPos p = at(origin, x, along, 0, 4);
                if (!spawns.contains(p) && GasVolumes.floor(level, p)) { chosen = p; break; }
            }
            if (chosen == null) return null;
            spawns.add(chosen);
        }
        Scene adapted = new Scene(origin, x, scene.blocks, List.copyOf(spawns), scene.loot,
                scene.diary, scene.cart, tier, scene.site);
        return fits(level, adapted) ? adapted : null;
    }

    private static Scene mine(ServerLevel level, ServerPlayer player, boolean near, EventData data) {
        List<BlockPos> lots = new ArrayList<>();
        if (CityWorldSites.territory(level, player.blockPosition()) == CityWorldSites.Territory.MINE) lots.add(player.blockPosition());
        if (!near) lots.addAll(CityWorldSites.findLots(level, player.blockPosition(), "Mine", 6, 8));
        for (BlockPos lot : lots) {
            // The entrance itself is often a slab staircase. Look for a loaded landing/gallery above or below it.
            Scene found = MineScenes.find(level, lot, 48, at -> !data.sites.contains(siteKey(MINERS, at))
                    && (near || !data.near(at) && player.distanceToSqr(Vec3.atCenterOf(at)) >= 24 * 24 && unseen(level, at)));
            if (found != null) return found;
        }
        return null;
    }

    private static String siteKey(String type, BlockPos p) {
        // Whole station/mine chunk and floor are reserved, even after the encounter loot is exhausted.
        if (SHELTER.equals(type)) return type + ":" + (p.getX() >> 4) + ":" + (p.getZ() >> 4);
        return type + ":" + (p.getX() >> 4) + ":" + p.getY() + ":" + (p.getZ() >> 4);
    }

    static boolean unseen(ServerLevel level, BlockPos p) {
        Vec3 target = Vec3.atCenterOf(p).add(0, 1, 0);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            if (player.distanceToSqr(target) < 24 * 24) return false;
            if (player.distanceToSqr(target) < 96 * 96 && level.clip(new ClipContext(player.getEyePosition(), target,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS) return false;
        }
        return true;
    }

    record Scene(BlockPos origin, boolean alongX, Map<BlockPos, BlockState> blocks, List<BlockPos> spawns,
                 List<BlockPos> loot, BlockPos diary, BlockPos cart, int tier, BlockPos site) {
        Scene reserve(BlockPos at) { return new Scene(origin, alongX, blocks, spawns, loot, diary, cart, tier, at); }
    }

    static BlockPos at(BlockPos origin, boolean x, int along, int y, int across) {
        return x ? origin.offset(along, y, across) : origin.offset(across, y, along);
    }

    static Scene shelter(BlockPos origin, boolean x, int tier) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Direction facing = x ? Direction.EAST : Direction.SOUTH;
        List<BlockPos> spawns = new ArrayList<>();
        for (int a : new int[]{1, 4, 7, 10}) {
            for (int along = a; along < a + 3; along++) for (int across = 1; across <= 3; across++)
                blocks.put(at(origin, x, along, 2, across), Blocks.GRAY_WOOL.defaultBlockState());
            for (int across : new int[]{1, 3}) {
                blocks.put(at(origin, x, a, 0, across), Blocks.OAK_FENCE.defaultBlockState());
                blocks.put(at(origin, x, a, 1, across), Blocks.OAK_FENCE.defaultBlockState());
            }
            BlockState bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, facing);
            blocks.put(at(origin, x, a, 0, 2), bed.setValue(BedBlock.PART, BedPart.FOOT));
            blocks.put(at(origin, x, a + 1, 0, 2), bed.setValue(BedBlock.PART, BedPart.HEAD));
            spawns.add(at(origin, x, a + 1, 0, 4));
        }
        List<BlockPos> loot = List.of(at(origin, x, 13, 0, 1), at(origin, x, 13, 0, 3));
        loot.forEach(p -> blocks.put(p, Blocks.CHEST.defaultBlockState()));
        BlockPos diary = at(origin, x, 14, 0, 2);
        blocks.put(diary, Blocks.LECTERN.defaultBlockState());
        blocks.put(at(origin, x, 14, 0, 4), Blocks.OAK_SIGN.defaultBlockState());
        blocks.put(at(origin, x, 3, 0, 1), ApiSupport.state("apocalypsenow:cardboard_box", Blocks.BROWN_CARPET.defaultBlockState()));
        blocks.put(at(origin, x, 6, 0, 3), Blocks.BROWN_CARPET.defaultBlockState());
        return new Scene(origin, x, blocks, spawns, loot, diary, null, tier, origin);
    }

    static Scene miners(BlockPos origin, boolean x) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int a = 0; a < 5; a++) blocks.put(at(origin, x, a, 0, 0), Blocks.RAIL.defaultBlockState()
                .setValue(RailBlock.SHAPE, x ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH));
        List<BlockPos> loot = List.of(at(origin, x, 0, 0, 2));
        blocks.put(loot.get(0), Blocks.CHEST.defaultBlockState());
        BlockPos diary = at(origin, x, 4, 0, 2);
        blocks.put(diary, Blocks.LECTERN.defaultBlockState());
        // An extinguished mining lamp, rather than a lit vanilla lantern that would erase the darkness.
        blocks.put(at(origin, x, 2, 0, 2), Blocks.REDSTONE_LAMP.defaultBlockState());
        List<BlockPos> spawns = List.of(at(origin, x, 1, 0, 1), at(origin, x, 2, 0, 1), at(origin, x, 3, 0, 1));
        return new Scene(origin, x, blocks, spawns, loot, diary, at(origin, x, 2, 0, 0), 0, origin);
    }

    static boolean fits(ServerLevel level, Scene scene) {
        for (Map.Entry<BlockPos, BlockState> e : scene.blocks.entrySet()) {
            BlockPos p = e.getKey();
            if (!level.hasChunkAt(p) || level.getBlockEntity(p) != null || !level.getFluidState(p).isEmpty()) return false;
            BlockState old = level.getBlockState(p);
            if (!old.isAir() && !old.canBeReplaced() && !old.is(Blocks.MOSS_CARPET) && !old.is(Blocks.COBWEB)
                    && !old.is(Blocks.RAIL) && !old.is(Blocks.SMOOTH_STONE_SLAB)) return false;
            if (p.getY() == scene.origin.getY() && !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return false;
        }
        for (BlockPos p : scene.spawns) if (!GasVolumes.floor(level, p)) return false;
        return true;
    }

    static boolean build(ServerLevel level, Scene scene, String type, UUID id) {
        if (!fits(level, scene)) return false;
        int tier = Math.min(5, 1 + (int) (level.getDayTime() / 24000) / 10);
        ResourceLocation loot = SHELTER.equals(type) ? new ResourceLocation("dyairdrop", "chests/smallairdrop" + scene.tier)
                : ApiSupport.id("chests/lost_miners" + tier);
        if (level.getServer().getLootData().getLootTable(loot) == LootTable.EMPTY) return false;
        // Complete validation above; retain a rollback snapshot in case an entity cannot be created.
        Map<BlockPos, BlockState> old = new LinkedHashMap<>();
        List<net.minecraft.world.entity.Entity> actors = new ArrayList<>();
        try {
            for (var entry : scene.blocks.entrySet()) {
                old.put(entry.getKey(), level.getBlockState(entry.getKey()));
                if (!level.setBlock(entry.getKey(), entry.getValue(), 2) && !level.getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("block placement rejected");
            }
            for (BlockPos p : scene.loot) {
                if (!(level.getBlockEntity(p) instanceof RandomizableContainerBlockEntity chest)) throw new IllegalStateException("missing chest");
                chest.setLootTable(loot, level.random.nextLong());
            }
            if (level.getBlockEntity(scene.diary) instanceof LecternBlockEntity lectern) {
                lectern.setBook(book(SHELTER.equals(type) ? "Platform register" : "The last shift", SHELTER.equals(type)
                        ? "We moved below when the streets fell. Keep the other platform clear.\n\nThe coughing started in tent four. We waited for the evacuation train. It never came."
                        : "The lift stopped before the last shift returned. We stacked the ore and kept digging for another way out.\n\nNo more torches. There is a hiss behind the stone."));
                net.minecraft.world.level.block.LecternBlock.resetBookState(null, level, scene.diary, level.getBlockState(scene.diary), true);
            }
            for (BlockPos p : scene.spawns) {
                var entity = LazarusEvents.spawn(level, p, "apocalypsenow:walker_" + (1 + level.random.nextInt(33)), id, true);
                if (!(entity instanceof Mob mob)) throw new IllegalStateException("missing infected");
                actors.add(mob);
                if (MINERS.equals(type)) {
                    mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
                    mob.setDropChance(EquipmentSlot.HEAD, .03f); mob.setDropChance(EquipmentSlot.MAINHAND, .05f);
                }
            }
            if (scene.cart != null) {
                Minecart cart = new Minecart(level, scene.cart.getX() + .5, scene.cart.getY(), scene.cart.getZ() + .5);
                cart.addTag(LazarusEvents.ID + ":" + id);
                if (!level.addFreshEntity(cart)) throw new IllegalStateException("cart rejected");
                actors.add(cart);
            } else {
                BlockPos sign = at(scene.origin, scene.alongX, 14, 0, 4);
                // sign() intentionally checks passability; replace our prevalidated sign only.
                level.removeBlock(sign, false);
                BuildingHideout.sign(level, sign, "EVACUATION", "WAIT HERE", "Keep tracks clear", "No train came");
            }
            return true;
        } catch (RuntimeException failed) {
            actors.forEach(net.minecraft.world.entity.Entity::discard);
            old.forEach((p, state) -> level.setBlock(p, state, 3));
            ApiSupport.LOGGER.warn("Territory scene rolled back: {}", failed.toString());
            return false;
        }
    }

    private static ItemStack book(String title, String text) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag(); tag.putString("title", title); tag.putString("author", "A survivor");
        ListTag pages = new ListTag(); pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(text))));
        tag.put("pages", pages); return book;
    }

    /** Loot exhausted resolves the encounter, but the saved site reservation prevents replenishing it. */
    static void tick(ServerLevel level, EventData data) {
        for (EventData.Encounter e : List.copyOf(data.encounters)) {
            if (!SHELTER.equals(e.type) && !MINERS.equals(e.type)) continue;
            boolean empty = true;
            for (long value : e.data.getLongArray("Loot")) {
                BlockPos p = BlockPos.of(value);
                if (!level.hasChunkAt(p)) { empty = false; break; }
                if (level.getBlockEntity(p) instanceof RandomizableContainerBlockEntity container
                        && (container.saveWithoutMetadata().contains("LootTable") || !container.isEmpty())) { empty = false; break; }
            }
            if (empty) data.remove(e.id);
        }
    }
}
