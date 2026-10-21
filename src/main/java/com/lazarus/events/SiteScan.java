package com.lazarus.events;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Saves a text cut of the blocks around a position (layers bottom up, one character per block, with a legend) to
 * {@code lazarus_scans/} in the server folder, so hideout kits can be drawn over the real shape of a sewer, a
 * basement or a room.
 */
final class SiteScan {
    static final int RADIUS = 8, BELOW = 3, ABOVE = 6;
    private static final String SYMBOLS = "#abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@$%&*+=<>?!";

    private SiteScan() {}

    /** The scan as text: a header, the legend and one block of rows per layer (rows run north to south, x west to east). */
    static String text(ServerLevel level, BlockPos center) {
        Map<String, Character> legend = new LinkedHashMap<>();
        List<String> layers = new ArrayList<>();
        for (int dy = -BELOW; dy <= ABOVE; dy++) {
            StringBuilder layer = new StringBuilder("y=" + (center.getY() + dy) + (dy == 0 ? "  (feet; P = you)" : "") + "\n");
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (dx == 0 && dz == 0 && dy == 0) { layer.append('P'); continue; }
                    layer.append(symbol(level, pos, legend));
                }
                layer.append('\n');
            }
            layers.add(layer.toString());
        }
        StringBuilder out = new StringBuilder();
        out.append("Lazarus scan at ").append(center.toShortString()).append(" | territory ").append(CityWorldSites.territory(level, center))
                .append(" | lot ").append(CityWorldSites.lotClass(level, center).orElse("none"));
        CityWorldSites.streetLevel(level).ifPresent(street -> out.append(" | street Y ").append(street));
        out.append("\nRows run north (top) to south, columns west (left) to east. ' ' = air, '~' = water.\n\nLegend:\n");
        legend.forEach((id, c) -> out.append(c).append(" = ").append(id).append('\n'));
        out.append('\n');
        layers.forEach(layer -> out.append(layer).append('\n'));
        return out.toString();
    }

    private static char symbol(ServerLevel level, BlockPos pos, Map<String, Character> legend) {
        BlockState state = level.getBlockState(pos);
        if (!level.getFluidState(pos).isEmpty() && state.getCollisionShape(level, pos).isEmpty()) return '~';
        if (state.isAir()) return ' ';
        String id = String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
        return legend.computeIfAbsent(id, key -> legend.size() + 1 < SYMBOLS.length() ? SYMBOLS.charAt(legend.size() + 1) : '?');
    }

    /** Writes the scan and returns the file. */
    static Path save(ServerLevel level, BlockPos center) throws IOException {
        Path folder = level.getServer().getServerDirectory().toPath().resolve("lazarus_scans");
        Files.createDirectories(folder);
        Path file = folder.resolve("scan_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt");
        Files.writeString(file, text(level, center), StandardCharsets.UTF_8);
        return file;
    }
}
