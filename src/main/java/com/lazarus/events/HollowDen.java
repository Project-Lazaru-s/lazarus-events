package com.lazarus.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.phys.AABB;

/**
 * A den of the Hollow cult in a building room, drawn only at night: bones, candles and a cold fire, a few cultists, and
 * a survivor caged in a corner. Clearing the den frees the captive, who may move into the Haven refuge.
 */
final class HollowDen {
    static final String TYPE = "hollow_den";
    static final String CAPTIVE = "lazarusevents:captive";

    private HollowDen() {}

    static boolean night(ServerLevel level) {
        long time = level.getDayTime() % 24000;
        return time >= 13000 && time < 23000;
    }

    /** Furnishes the room, cages a captive and stations the cultists; false when nothing could be placed. */
    static boolean place(ServerLevel level, BuildingHideout.Room room, UUID id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BuildingHideout.Cells cells = BuildingHideout.cells(level, room);
        // The cage needs a corner: two walls already, bars on the other sides.
        BlockPos cage = cells.walls().stream().filter(cell -> walls(level, cell) >= 2).findFirst().orElse(null);
        if (cage == null || cells.walls().size() < 5) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = cage.relative(side);
            if (!BuildingHideout.passable(level, next)) continue;
            level.setBlock(next, Blocks.IRON_BARS.defaultBlockState(), 3);
            level.setBlock(next.above(), Blocks.IRON_BARS.defaultBlockState(), 3);
        }
        String captiveType = "apocalypsenow:survivor_" + (1 + random.nextInt(8));
        if (!(LazarusEvents.spawn(level, cage, captiveType, id, true) instanceof Mob captive)) return false;
        captive.addTag(CAPTIVE);
        captive.setInvulnerable(true); // the cult keeps them alive; so does the cage
        ApiSupport.log("hollow captive", NpcApi.assign(captive, new ScenarioAssignment(HostileHideouts.OWNER, id,
                ScenarioOrder.WAIT, GlobalPos.of(level.dimension(), cage), null, 1, 168_000L, 5)));

        List<BlockPos> walls = new ArrayList<>(cells.walls());
        walls.removeIf(cell -> cell.closerThan(cage, 1.5));
        Collections.shuffle(walls, random);
        BuildingHideout.put(level, walls.get(0), Blocks.BONE_BLOCK.defaultBlockState());
        BuildingHideout.put(level, walls.get(1), Blocks.BONE_BLOCK.defaultBlockState());
        BuildingHideout.put(level, walls.get(2), Blocks.SKELETON_SKULL.defaultBlockState());
        for (int i = 3; i < Math.min(6, walls.size()); i++)
            BuildingHideout.put(level, walls.get(i), Blocks.CANDLE.defaultBlockState()
                    .setValue(CandleBlock.CANDLES, 1 + random.nextInt(3)).setValue(CandleBlock.LIT, true));
        BuildingHideout.put(level, room.center(), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));

        List<BlockPos> spawns = new ArrayList<>(cells.free().isEmpty() ? walls.subList(Math.min(6, walls.size()), walls.size()) : cells.free());
        spawns.remove(room.center());
        Collections.shuffle(spawns, random);
        List<Mob> cultists = new ArrayList<>();
        for (int i = 0; i < Math.min(2 + random.nextInt(2), spawns.size()); i++)
            if (LazarusEvents.spawn(level, spawns.get(i), "apocalypsenow:cannibal", id, true) instanceof Mob mob) cultists.add(mob);
        if (cultists.isEmpty()) return false;
        for (Mob mob : cultists)
            ApiSupport.log("hollow guard", NpcApi.assign(mob, new ScenarioAssignment(HostileHideouts.OWNER, id,
                    ScenarioOrder.GUARD_AREA, GlobalPos.of(level.dimension(), room.center()), null, 8, 168_000L, 4)));
        return true;
    }

    private static int walls(ServerLevel level, BlockPos cell) {
        int count = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) if (!BuildingHideout.passable(level, cell.relative(side))) count++;
        return count;
    }

    /** When the den is cleared: the bars come down and the captive is rescued by a player who was there. */
    static void free(ServerLevel level, BlockPos center, UUID id, ServerPlayer rescuer) {
        String tag = LazarusEvents.ID + ":" + id;
        for (Mob captive : level.getEntitiesOfClass(Mob.class, new AABB(center).inflate(24),
                mob -> mob.isAlive() && mob.getTags().contains(tag) && mob.getTags().contains(CAPTIVE))) {
            for (BlockPos pos : BlockPos.betweenClosed(captive.blockPosition().offset(-1, 0, -1), captive.blockPosition().offset(1, 1, 1)))
                if (level.getBlockState(pos).is(Blocks.IRON_BARS)) level.removeBlock(pos, false);
            captive.setInvulnerable(false);
            captive.removeTag(CAPTIVE);
            NpcApi.clearAssignment(captive, HostileHideouts.OWNER, id);
            rescuer.sendSystemMessage(Component.literal("\"You came for me... I thought the Hollow would keep me forever.\"")
                    .withStyle(style -> style.withColor(0x8FCB91)));
            HavenRefuge.rescued(rescuer, captive);
            HavenRefuge.sendOff(captive);
        }
    }
}
