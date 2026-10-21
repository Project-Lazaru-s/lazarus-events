package com.lazarus.events;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The one door to CityWorld's public API ({@code me.daddychurchill.CityWorld.api.CityWorldAPI}), called by reflection
 * so the mod runs without CityWorld: every query then answers "nothing here" and territory events stay off.
 */
final class CityWorldSites {
    /** Where a position sits in the city, for choosing territory events. */
    enum Territory { SURFACE, METRO_STATION, METRO_TUNNEL, MINE, BUNKER, SEWER, CAVE }

    /** A metro chunk as CityWorld reports it; the floor heights are those of the lines that run through. */
    record Subway(boolean station, boolean eastWest, boolean northSouth, int eastWestFloorY, int northSouthFloorY) {}

    /** Sewers, cisterns and basements lie this many blocks under the street at most; deeper is cave or mine. */
    static final int SHALLOW = 16;
    /** How often nests and gas zones look for a sewer first when CityWorld is there. */
    static final double SEWER_PREFERENCE = 0.6;
    private static final String API = "me.daddychurchill.CityWorld.api.CityWorldAPI";
    private static Class<?> api;
    private static boolean looked;

    private CityWorldSites() {}

    static boolean available() { return type() != null; }

    private static Class<?> type() {
        if (!looked) {
            looked = true;
            try {
                api = Class.forName(API);
            } catch (ClassNotFoundException | LinkageError missing) {
                api = null;
            }
        }
        return api;
    }

    private static Object call(String name, Class<?>[] types, Object... args) {
        Class<?> type = type();
        if (type == null) return null;
        try {
            Method method = type.getMethod(name, types);
            return method.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            ApiSupport.LOGGER.warn("Lazarus Events: CityWorld {} failed: {}", name, failure.toString());
            return null;
        }
    }

    private static Object field(Object record, String accessor) throws ReflectiveOperationException {
        return record.getClass().getMethod(accessor).invoke(record);
    }

    /** The street height of a CityWorld world, if this is one. */
    static OptionalInt streetLevel(ServerLevel level) {
        return call("streetLevel", new Class<?>[] {ServerLevel.class}, level) instanceof OptionalInt street ? street : OptionalInt.empty();
    }

    /** The class name of the CityWorld lot at {@code pos} (such as {@code SubwayStationLot}), or empty. */
    static Optional<String> lotClass(ServerLevel level, BlockPos pos) {
        if (!(call("lotAt", new Class<?>[] {ServerLevel.class, BlockPos.class}, level, pos) instanceof Optional<?> lot) || lot.isEmpty())
            return Optional.empty();
        try {
            return Optional.ofNullable((String) field(lot.get(), "lotClass"));
        } catch (ReflectiveOperationException failure) {
            return Optional.empty();
        }
    }

    /** The metro at {@code pos}, or empty when there is none (or no CityWorld). */
    static Optional<Subway> subway(ServerLevel level, BlockPos pos) {
        if (!(call("subwayAt", new Class<?>[] {ServerLevel.class, BlockPos.class}, level, pos) instanceof Optional<?> info) || info.isEmpty())
            return Optional.empty();
        try {
            Object s = info.get();
            if (!(boolean) field(s, "any")) return Optional.empty();
            return Optional.of(new Subway((boolean) field(s, "station"), (boolean) field(s, "eastWest"), (boolean) field(s, "northSouth"),
                    (int) field(s, "eastWestFloorY"), (int) field(s, "northSouthFloorY")));
        } catch (ReflectiveOperationException | ClassCastException failure) {
            return Optional.empty();
        }
    }

    /** True when {@code pos} is inside the metro's own space (a station or a tunnel), not merely above it. */
    static boolean inSubway(ServerLevel level, BlockPos pos) {
        // CityWorld 6.3.0's isSubway only checks the chunk, not the player's height.
        return subway(level, pos).map(s -> contains(s, pos.getY())).orElse(false);
    }

    static boolean contains(Subway s, int y) {
        return s.eastWest() && y > s.eastWestFloorY() && y <= s.eastWestFloorY() + 6
                || s.northSouth() && y > s.northSouthFloorY() && y <= s.northSouthFloorY() + 6;
    }

    /** Centres of up to {@code max} lots of class {@code lotClass} within {@code radius} chunks (CityWorld caps it at 8). */
    static List<BlockPos> findLots(ServerLevel level, BlockPos near, String lotClass, int radius, int max) {
        List<BlockPos> found = new ArrayList<>();
        if (!(call("findLots", new Class<?>[] {ServerLevel.class, BlockPos.class, String.class, int.class, int.class},
                level, near, lotClass, Math.min(radius, 8) * 16, max) instanceof List<?> lots)) return found;
        for (Object lot : lots) {
            try {
                ChunkPos chunk = (ChunkPos) field(lot, "chunk");
                found.add(new BlockPos(chunk.getMiddleBlockX(), near.getY(), chunk.getMiddleBlockZ()));
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                // a lot we cannot read is a lot we skip
            }
        }
        return found;
    }

    /** CityWorld's own description of a position (lot, context, roads...), for the debug command. */
    @SuppressWarnings("unchecked")
    static Map<String, String> fullInfo(ServerLevel level, BlockPos pos) {
        return call("getFullInfo", new Class<?>[] {ServerLevel.class, BlockPos.class}, level, pos) instanceof Map<?, ?> info
                ? (Map<String, String>) info : Map.of();
    }

    /**
     * An enclosed sewer space 30-80 blocks from the player, away from recent encounters and out of their reach; null
     * without CityWorld or when none is found. {@code maxCells} and {@code reach} bound the space as in
     * {@link BuildingHideout#room(ServerLevel, BlockPos, int, int)}.
     */
    static BuildingHideout.Room findSewer(ServerLevel level, ServerPlayer player, EventData data, int maxCells, int reach) {
        OptionalInt street = streetLevel(level);
        if (street.isEmpty()) return null;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = random.nextDouble(Math.PI * 2), distance = random.nextDouble(30, 80);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            for (int y = street.getAsInt() - 2; y >= street.getAsInt() - SHALLOW; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BuildingHideout.Room room = BuildingHideout.room(level, pos, maxCells, reach);
                if (room == null) continue;
                if (territory(level, pos) != Territory.SEWER || data.near(room.center())
                        || player.distanceToSqr(Vec3.atCenterOf(room.center())) < 24 * 24) break;
                return room;
            }
        }
        return null;
    }

    /**
     * Which territory {@code pos} is in. Without CityWorld (or outside its cities) only surface and cave are told
     * apart, by the open-sky height; sewers have no lot of their own, so they are the shallow enclosed ground under
     * a street.
     */
    static Territory territory(ServerLevel level, BlockPos pos) {
        OptionalInt street = streetLevel(level);
        int ground = street.isPresent() ? street.getAsInt() : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        if (pos.getY() >= ground - 1) return Territory.SURFACE;
        if (inSubway(level, pos)) return subway(level, pos).map(Subway::station).orElse(false) ? Territory.METRO_STATION : Territory.METRO_TUNNEL;
        String lot = lotClass(level, pos).orElse("");
        if (lot.contains("Bunker")) return Territory.BUNKER;
        if (lot.contains("Mine")) return Territory.MINE;
        return street.isPresent() && ground - pos.getY() <= SHALLOW ? Territory.SEWER : Territory.CAVE;
    }
}
