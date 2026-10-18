package com.lazarus.events;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Compact, complete scenes inspired by ruined buildings and survivor camps. */
final class EncounterScenery {
    static final int RADIUS = 3;
    static final int HEIGHT = 5;
    private static final ResourceLocation CACHE_ID = new ResourceLocation("lazarusevents", "cache_camp");
    private static final ResourceLocation GUNFIRE_ID = new ResourceLocation("lazarusevents", "gunfire_barricade");
    private static final ResourceLocation FLARE_ID = new ResourceLocation("lazarusevents", "flare_marker");
    private static final ResourceLocation MILITARY_ID = new ResourceLocation("lazarusevents", "military_checkpoint");
    private static final BlockPos CACHE_ANCHOR = new BlockPos(4, 1, 8);
    private static final BlockPos GUNFIRE_ANCHOR = new BlockPos(5, 1, 3);
    private static final BlockPos FLARE_ANCHOR = new BlockPos(3, 1, 3);
    private static final BlockPos MILITARY_ANCHOR = new BlockPos(16, 1, 9);
    private static final List<BlockPos> CACHE_BLOCKS = loadPositions("cache_camp");
    private static final List<BlockPos> GUNFIRE_BLOCKS = loadPositions("gunfire_barricade");
    private static final List<BlockPos> FLARE_BLOCKS = loadPositions("flare_marker");
    private static final List<BlockPos> MILITARY_BLOCKS = loadPositions("military_checkpoint");

    private EncounterScenery() {}

    static BlockPos offset(BlockPos origin, int rotation, int x, int z) {
        return switch (rotation) {
            case 1 -> origin.offset(-z, 0, x);
            case 2 -> origin.offset(-x, 0, -z);
            case 3 -> origin.offset(z, 0, -x);
            default -> origin.offset(x, 0, z);
        };
    }

    static BlockPos militaryPosition(BlockPos origin, int rotation, int x, int y, int z) {
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(turn(rotation));
        BlockPos base = templateBase(origin, settings, MILITARY_ANCHOR);
        return base.offset(StructureTemplate.calculateRelativePosition(settings, new BlockPos(x, y, z)));
    }

    static boolean canPlace(ServerLevel level, BlockPos origin, String type, int rotation) {
        if (!List.of("cache", "gunfire", "flare", "military").contains(type)) return false;
        if (type.equals("cache")) return canPlaceTemplate(level, origin, rotation,
                CACHE_ID, CACHE_ANCHOR, CACHE_BLOCKS, true);
        if (type.equals("gunfire")) return canPlaceTemplate(level, origin, rotation,
                GUNFIRE_ID, GUNFIRE_ANCHOR, GUNFIRE_BLOCKS, false);
        if (type.equals("flare")) return canPlaceTemplate(level, origin, rotation,
                FLARE_ID, FLARE_ANCHOR, FLARE_BLOCKS, false);
        if (type.equals("military")) return canPlaceTemplate(level, origin, rotation,
                MILITARY_ID, MILITARY_ANCHOR, MILITARY_BLOCKS, false);
        return false;
    }

    static boolean build(ServerLevel level, BlockPos origin, String type, int rotation) {
        ResourceLocation id = switch (type) {
            case "cache" -> CACHE_ID;
            case "gunfire" -> GUNFIRE_ID;
            case "flare" -> FLARE_ID;
            case "military" -> MILITARY_ID;
            default -> null;
        };
        if (id == null) return false;
        BlockPos anchor = switch (type) {
            case "cache" -> CACHE_ANCHOR;
            case "gunfire" -> GUNFIRE_ANCHOR;
            case "flare" -> FLARE_ANCHOR;
            default -> MILITARY_ANCHOR;
        };
        var template = level.getStructureManager().get(id);
        if (template.isEmpty()) return false;
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(turn(rotation))
                .setIgnoreEntities(true);
        BlockPos base = templateBase(origin, settings, anchor);
        return template.get().placeInWorld(level, base, base, settings, level.getRandom(), 3);
    }

    private static Rotation turn(int rotation) {
        return switch (rotation) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static BlockPos templateBase(BlockPos origin, StructurePlaceSettings settings, BlockPos anchor) {
        return origin.subtract(StructureTemplate.calculateRelativePosition(settings, anchor));
    }

    private static boolean softSurface(BlockState state) {
        return state.is(Blocks.MOSS_CARPET) || state.getBlock() instanceof SlabBlock;
    }

    static boolean clearable(BlockState state, boolean groundLayer) {
        return state.isAir() || state.canBeReplaced() || groundLayer && softSurface(state);
    }

    private static boolean militaryObstacle(BlockState state) {
        return state.is(Blocks.GLOWSTONE) || state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof FenceGateBlock;
    }

    static boolean supported(ServerLevel level, BlockPos groundPos) {
        BlockPos pos = groundPos;
        for (int depth = 0; depth < 3; depth++, pos = pos.below()) {
            if (!level.isLoaded(pos)) return false;
            BlockState ground = level.getBlockState(pos);
            if (!ground.getFluidState().isEmpty() || ground.is(Blocks.ICE)
                    || ground.is(Blocks.PACKED_ICE) || ground.is(Blocks.BLUE_ICE)
                    || ground.is(Blocks.FROSTED_ICE)) return false;
            if (ground.isFaceSturdy(level, pos, Direction.UP)) return true;
            if (!softSurface(ground) && !ground.canBeReplaced()) return false;
        }
        return false;
    }

    private static boolean canPlaceTemplate(ServerLevel level, BlockPos origin, int rotation,
            ResourceLocation id, BlockPos anchor, List<BlockPos> blocks, boolean cache) {
        if (level.getStructureManager().get(id).isEmpty()) return false;
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(turn(rotation));
        BlockPos base = templateBase(origin, settings, anchor);
        boolean military = id.equals(MILITARY_ID);
        for (BlockPos local : blocks) {
            BlockPos target = base.offset(StructureTemplate.calculateRelativePosition(settings, local));
            if (!level.isLoaded(target) || !level.isLoaded(target.below())) return false;
            BlockState current = level.getBlockState(target);
            if (!current.getFluidState().isEmpty() || current.is(Blocks.ICE)
                    || current.is(Blocks.PACKED_ICE) || current.is(Blocks.BLUE_ICE)
                    || current.is(Blocks.FROSTED_ICE)) return false;
            if (local.getY() == 0) {
                // The embedded walkway replaces only ordinary surface, never a container.
                if (current.hasBlockEntity() || current.getDestroySpeed(level, target) < 0) return false;
                if (current.isAir() || !current.isFaceSturdy(level, target, Direction.UP)
                        && !softSurface(current) && !(military && militaryObstacle(current))) return false;
                if (!supported(level, target) && !(military && militaryObstacle(current)
                        && supported(level, target.below()))) return false;
            } else {
                if (!clearable(current, local.getY() == 1)
                        && !(military && militaryObstacle(current)
                        && !current.hasBlockEntity() && current.getDestroySpeed(level, target) >= 0)) return false;
                if (local.getY() == 1) {
                    BlockPos below = target.below();
                    BlockState ground = level.getBlockState(below);
                    if (ground.isAir() || !supported(level, below)
                            && !(military && militaryObstacle(ground) && supported(level, below.below()))) return false;
                }
            }
        }
        if (id.equals(MILITARY_ID)) return true;
        // Keep the template footprint clear of trees and existing structures.
        int minX = cache ? 8 : 0;
        int maxX = cache ? 11 : id.equals(FLARE_ID) ? 5 : 6;
        int minZ = cache ? 2 : 0;
        int maxZ = cache ? 8 : 7;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            for (int y = 1; y <= 4; y++) {
                BlockPos target = base.offset(StructureTemplate.calculateRelativePosition(
                        settings, new BlockPos(x, y, z)));
                if (!level.isLoaded(target)) return false;
                BlockState current = level.getBlockState(target);
                if (!current.getFluidState().isEmpty()
                        || !clearable(current, y == 1)) return false;
                if (y == 1 && softSurface(current) && !supported(level, target)) return false;
            }
        return true;
    }

    private static List<BlockPos> loadPositions(String name) {
        try (InputStream input = EncounterScenery.class.getResourceAsStream(
                "/data/lazarusevents/structures/" + name + ".nbt")) {
            if (input == null) throw new IllegalStateException("Missing " + name + ".nbt");
            CompoundTag template = NbtIo.readCompressed(input);
            ListTag blocks = template.getList("blocks", 10);
            List<BlockPos> positions = new ArrayList<>(blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                ListTag pos = blocks.getCompound(i).getList("pos", 3);
                positions.add(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)));
            }
            return List.copyOf(positions);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load " + name + ".nbt", exception);
        }
    }

}
