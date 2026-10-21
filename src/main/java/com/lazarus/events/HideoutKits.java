package com.lazarus.events;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Furniture kits for lived-in hideouts: small arrangements (a workbench corner, a kitchen, two beds, a stack of
 * crates) written as text in {@code data/lazarusevents/kits/*.json} and set against a wall facing into the room.
 *
 * <p>Format: {@code tags} (what the kit is for), {@code palette} (one character per block) and {@code layers} (bottom
 * up; in each layer, rows from the wall outwards, columns left to right as seen facing the wall). A space leaves the
 * world untouched; {@code .} only needs the space to be free. A palette entry is a block state string, a list of
 * alternatives (the first that exists wins, so kits work without optional mods), {@code {"random": [...]}} to vary it,
 * or {@code {"block": ..., "loot": "smallairdrop"}} for a container with the current tier's Dyairdrop loot. In state
 * strings {@code facing=front|back|left|right} is relative to the wall: front points into the room; so is
 * {@code rotation=front|...} for skulls and standing banners.
 *
 * <p>Groups: a kit with {@code "groups": ["hollow"]} is only used by those groups, and before any kit for everyone. A
 * group style ({@code data/lazarusevents/kit_styles/<group>.json}, {@code {"replace": {"minecraft:lantern": ...}}})
 * swaps blocks by id in every kit the group sets down; the replacement is a palette entry like any other.
 */
final class HideoutKits {
    record Kit(String name, Set<String> tags, Set<String> groups, Map<Character, JsonElement> palette, List<List<String>> layers) {
        int depth() { return layers.stream().mapToInt(List::size).max().orElse(0); }

        int width() { return layers.stream().flatMap(List::stream).mapToInt(String::length).max().orElse(0); }

        char at(int y, int row, int column) {
            List<String> layer = layers.get(y);
            if (row >= layer.size() || column >= layer.get(row).length()) return ' ';
            return layer.get(row).charAt(column);
        }
    }

    /** Where a kit goes: its back-left corner (on the floor, against the wall) and the way it faces. */
    record Spot(BlockPos origin, Direction front) {}

    private record Piece(BlockState state, String loot) {}

    private static final Pattern FACING = Pattern.compile("(facing|rotation)=(front|back|left|right)");
    /** ponytail: loaded once per server start; /reload does not refresh kits, restart the server after editing them. */
    private static Map<String, Kit> kits;
    private static Map<String, JsonObject> styles;
    private static MinecraftServer loadedFor;

    private HideoutKits() {}

    static Map<String, Kit> all(MinecraftServer server) {
        if (kits == null || loadedFor != server) {
            kits = load(server);
            styles = loadStyles(server);
            loadedFor = server;
        }
        return kits;
    }

    static List<Kit> tagged(MinecraftServer server, String tag) {
        return all(server).values().stream().filter(kit -> kit.tags().contains(tag)).toList();
    }

    /** The kits a group may use for {@code use}: its own first (shuffled), then those for everyone (shuffled). */
    static List<Kit> forUse(MinecraftServer server, String use, String group) {
        List<Kit> own = new ArrayList<>(), shared = new ArrayList<>();
        for (Kit kit : tagged(server, use)) {
            if (kit.groups().isEmpty()) shared.add(kit);
            else if (group != null && kit.groups().contains(group)) own.add(kit);
        }
        Collections.shuffle(own, ThreadLocalRandom.current());
        Collections.shuffle(shared, ThreadLocalRandom.current());
        own.addAll(shared);
        return own;
    }

    /** The groups that have a block style. */
    static Set<String> styled(MinecraftServer server) {
        all(server);
        return styles.keySet();
    }

    private static Map<String, JsonObject> loadStyles(MinecraftServer server) {
        Map<String, JsonObject> found = new TreeMap<>();
        server.getResourceManager().listResources("kit_styles", id -> id.getNamespace().equals(LazarusEvents.ID) && id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    String group = id.getPath().substring("kit_styles/".length(), id.getPath().length() - ".json".length());
                    try (Reader reader = resource.openAsReader()) {
                        found.put(group, GsonHelper.getAsJsonObject(GsonHelper.parse(reader), "replace"));
                    } catch (Exception failure) {
                        ApiSupport.LOGGER.warn("Lazarus Events: kit style {} could not be read: {}", group, failure.toString());
                    }
                });
        return found;
    }

    private static Map<String, Kit> load(MinecraftServer server) {
        Map<String, Kit> found = new TreeMap<>();
        server.getResourceManager().listResources("kits", id -> id.getNamespace().equals(LazarusEvents.ID) && id.getPath().endsWith(".json"))
                .forEach((id, resource) -> {
                    String name = id.getPath().substring("kits/".length(), id.getPath().length() - ".json".length());
                    try (Reader reader = resource.openAsReader()) {
                        Kit kit = parse(name, GsonHelper.parse(reader));
                        if (kit != null) found.put(name, kit);
                    } catch (Exception failure) {
                        ApiSupport.LOGGER.warn("Lazarus Events: kit {} could not be read: {}", name, failure.toString());
                    }
                });
        return found;
    }

    /** A kit from its JSON, or null (logged) when a layer uses a character the palette does not define. */
    static Kit parse(String name, JsonObject json) {
        Set<String> tags = new HashSet<>(), groups = new HashSet<>();
        for (JsonElement tag : GsonHelper.getAsJsonArray(json, "tags", new JsonArray())) tags.add(tag.getAsString());
        for (JsonElement group : GsonHelper.getAsJsonArray(json, "groups", new JsonArray())) groups.add(group.getAsString());
        Map<Character, JsonElement> palette = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : GsonHelper.getAsJsonObject(json, "palette").entrySet())
            palette.put(entry.getKey().charAt(0), entry.getValue());
        List<List<String>> layers = new ArrayList<>();
        for (JsonElement layer : GsonHelper.getAsJsonArray(json, "layers")) {
            List<String> rows = new ArrayList<>();
            for (JsonElement row : layer.getAsJsonArray()) {
                for (char c : row.getAsString().toCharArray())
                    if (c != ' ' && c != '.' && !palette.containsKey(c)) {
                        ApiSupport.LOGGER.warn("Lazarus Events: kit {} uses '{}' with no palette entry", name, c);
                        return null;
                    }
                rows.add(row.getAsString());
            }
            layers.add(rows);
        }
        return layers.isEmpty() ? null : new Kit(name, tags, groups, palette, layers);
    }

    /** The world position of a kit cell, from its back-left corner. */
    static BlockPos at(Spot spot, int y, int row, int column) {
        return spot.origin().relative(spot.front(), row).relative(spot.front().getCounterClockWise(), column).above(y);
    }

    /**
     * True when every cell the kit uses is free and not {@code taken}; with {@code floor} given, its bottom layer must
     * also stand on those floor cells (inside the room).
     */
    static boolean fits(ServerLevel level, Kit kit, Spot spot, Collection<BlockPos> floor, Set<BlockPos> taken) {
        for (int y = 0; y < kit.layers().size(); y++)
            for (int row = 0; row < kit.depth(); row++)
                for (int column = 0; column < kit.width(); column++) {
                    if (kit.at(y, row, column) == ' ') continue;
                    BlockPos pos = at(spot, y, row, column);
                    if (taken.contains(pos) || !level.isLoaded(pos) || !BuildingHideout.passable(level, pos)) return false;
                    if (y == 0 && floor != null && !floor.contains(pos)) return false;
                }
        return true;
    }

    /** A random spot against a wall of the room where the kit fits, or null. */
    static Spot find(ServerLevel level, Kit kit, Collection<BlockPos> floor, Set<BlockPos> taken) {
        Set<BlockPos> cells = floor instanceof Set<BlockPos> set ? set : new HashSet<>(floor);
        List<Spot> spots = new ArrayList<>();
        for (BlockPos cell : cells)
            for (Direction front : Direction.Plane.HORIZONTAL) {
                if (BuildingHideout.passable(level, cell.relative(front.getOpposite()))) continue; // no wall behind
                for (int shift = 0; shift < kit.width(); shift++)
                    spots.add(new Spot(cell.relative(front.getCounterClockWise(), -shift), front));
            }
        Collections.shuffle(spots, ThreadLocalRandom.current());
        for (Spot spot : spots) if (fits(level, kit, spot, cells, taken)) return spot;
        return null;
    }

    /** Sets the kit down at {@code spot}, marking its cells {@code taken}; returns how many blocks it placed. */
    static int place(ServerLevel level, Kit kit, Spot spot, int tier, Set<BlockPos> taken) { return place(level, kit, spot, tier, taken, null); }

    /** As {@link #place(ServerLevel, Kit, Spot, int, Set)}, with the blocks swapped by the group's style. */
    static int place(ServerLevel level, Kit kit, Spot spot, int tier, Set<BlockPos> taken, String group) {
        all(level.getServer());
        JsonObject style = group == null ? null : styles.get(group);
        List<BlockPos> placed = new ArrayList<>();
        Map<Character, Piece> chosen = new LinkedHashMap<>(); // one draw per character, so a kit's beds match
        for (int y = 0; y < kit.layers().size(); y++)
            for (int row = 0; row < kit.depth(); row++)
                for (int column = 0; column < kit.width(); column++) {
                    char c = kit.at(y, row, column);
                    if (c == ' ') continue;
                    BlockPos pos = at(spot, y, row, column);
                    taken.add(pos);
                    if (c == '.') continue;
                    Piece piece = chosen.computeIfAbsent(c, key -> styled(level, resolve(level, kit.palette().get(key), spot.front()), style, spot.front()));
                    if (piece == null || !level.setBlock(pos, piece.state(), 3)) continue;
                    placed.add(pos);
                    if (piece.loot() != null && level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container) {
                        ResourceLocation table = piece.loot().contains(":") ? new ResourceLocation(piece.loot())
                                : new ResourceLocation("dyairdrop", "chests/" + piece.loot() + tier);
                        if (level.getServer().getLootData().getLootTable(table) != LootTable.EMPTY)
                            container.setLootTable(table, ThreadLocalRandom.current().nextLong());
                    }
                }
        // Fences, bars, walls and panes join their neighbours only once all of them are down.
        for (BlockPos pos : placed) level.setBlock(pos, Block.updateFromNeighbourShapes(level.getBlockState(pos), level, pos), 3);
        return placed.size();
    }

    /** Finds a spot for the kit in the room and sets it down; false when it fits nowhere. */
    static boolean furnish(ServerLevel level, Kit kit, Collection<BlockPos> floor, Set<BlockPos> taken, int tier) {
        return furnish(level, kit, floor, taken, tier, null);
    }

    static boolean furnish(ServerLevel level, Kit kit, Collection<BlockPos> floor, Set<BlockPos> taken, int tier, String group) {
        Spot spot = find(level, kit, floor, taken);
        if (spot == null) return false;
        place(level, kit, spot, tier, taken, group);
        return true;
    }

    /** Palette characters of the kit (and swaps of the group's style) that resolve to no block here; for checks. */
    static List<String> unresolved(ServerLevel level, Kit kit, String group) {
        all(level.getServer());
        List<String> missing = new ArrayList<>();
        kit.palette().forEach((c, entry) -> { if (resolve(level, entry, Direction.NORTH) == null) missing.add(kit.name() + ":" + c); });
        JsonObject style = group == null ? null : styles.get(group);
        if (style != null)
            style.entrySet().forEach(swap -> { if (resolve(level, swap.getValue(), Direction.NORTH) == null) missing.add(group + ":" + swap.getKey()); });
        return missing;
    }

    /** The piece with its block swapped by the style, keeping its loot; unchanged when the style has no swap for it. */
    private static Piece styled(ServerLevel level, Piece piece, JsonObject style, Direction front) {
        if (piece == null || style == null) return piece;
        JsonElement swap = style.get(String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(piece.state().getBlock())));
        Piece replaced = swap == null ? null : resolve(level, swap, front);
        return replaced == null ? piece : new Piece(replaced.state(), piece.loot());
    }

    private static Piece resolve(ServerLevel level, JsonElement entry, Direction front) {
        if (entry == null) return null;
        if (entry.isJsonPrimitive()) {
            BlockState state = state(level, entry.getAsString(), front);
            return state == null ? null : new Piece(state, null);
        }
        if (entry.isJsonArray()) {
            for (JsonElement alternative : entry.getAsJsonArray()) {
                Piece piece = resolve(level, alternative, front);
                if (piece != null) return piece;
            }
            return null;
        }
        JsonObject object = entry.getAsJsonObject();
        if (object.has("random")) {
            List<JsonElement> options = new ArrayList<>();
            object.getAsJsonArray("random").forEach(options::add);
            Collections.shuffle(options, ThreadLocalRandom.current());
            for (JsonElement option : options) {
                Piece piece = resolve(level, option, front);
                if (piece != null) return piece;
            }
            return null;
        }
        Piece block = resolve(level, object.get("block"), front);
        return block == null ? null : new Piece(block.state(), GsonHelper.getAsString(object, "loot", null));
    }

    /** A block state from text with wall-relative facing, or null when the block or a property does not exist. */
    static BlockState state(ServerLevel level, String text, Direction front) {
        Matcher matcher = FACING.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Direction facing = switch (matcher.group(2)) {
                case "front" -> front;
                case "back" -> front.getOpposite();
                case "right" -> front.getCounterClockWise();
                default -> front.getClockWise();
            };
            matcher.appendReplacement(out, matcher.group(1).equals("rotation")
                    ? "rotation=" + net.minecraft.world.level.block.state.properties.RotationSegment.convertToSegment(facing) // skulls, standing banners
                    : "facing=" + facing.getName());
        }
        matcher.appendTail(out);
        try {
            return BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), out.toString(), false).blockState();
        } catch (Exception missing) {
            return null;
        }
    }
}
