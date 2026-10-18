package com.lazarus.events;

import java.util.List;
import java.util.UUID;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/** Small helpers shared by the scenarios that use the Lazarus NPCs API. */
final class ApiSupport {
    static final Logger LOGGER = LogUtils.getLogger();
    static final ResourceLocation CANCELLED = id("cancelled");

    private ApiSupport() {}

    static ResourceLocation id(String path) { return new ResourceLocation(LazarusEvents.ID, path); }

    static ItemStack stack(String id, int count) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    /** Reward lists skip items whose mod is missing instead of failing registration. */
    static List<ItemStack> items(ItemStack... stacks) { return List.of(stacks).stream().filter(s -> !s.isEmpty()).toList(); }

    static void log(String what, ApiResult result) {
        if (!result.ok()) LOGGER.warn("Lazarus Events {}: {}", what, result.message());
    }

    /** Living actors of an encounter near a point (Events tags every actor it spawns). */
    static List<Mob> actors(ServerLevel level, UUID encounterId, Vec3 near, double radius) {
        String tag = LazarusEvents.ID + ":" + encounterId;
        return level.getEntitiesOfClass(Mob.class, new AABB(near, near).inflate(radius),
                mob -> mob.isAlive() && mob.getTags().contains(tag));
    }

    /** A spawn spot on the surface at a distance from the center, or null when that ground is not loaded. */
    static BlockPos ring(ServerLevel level, BlockPos center, double distance, double angle) {
        int x = (int) Math.floor(center.getX() + Math.cos(angle) * distance);
        int z = (int) Math.floor(center.getZ() + Math.sin(angle) * distance);
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
        BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        return Math.abs(pos.getY() - center.getY()) > 10 ? null : pos;
    }

    /** Cancelling an encounter fails the open tasks of online players for it, without reward. */
    static void failTasks(ServerLevel level, UUID encounterId, ResourceLocation outcome) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            NpcApi.encounterTask(player)
                    .filter(t -> t.state().open() && t.encounterId().equals(encounterId))
                    .ifPresent(t -> NpcApi.finishTask(player, t.instanceId(), outcome));
        }
    }
}
