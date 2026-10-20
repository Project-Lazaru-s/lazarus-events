package com.lazarus.events;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * The 64-block area around a living nest: extra infected roam its streets out of the players' sight, untouched
 * structure containers hold one extra item, and players are warned once when they walk in. All of it ends with the
 * nest; the infected already out stay as ordinary ones.
 */
final class InfestedZone {
    static final int RADIUS = 64, EXTRA_PER_PLAYER = 20;
    static final String EXTRA = "lazarusevents:infested";
    private static final String WARNED = "LazarusEventsInfestedWarned", BONUS = "LazarusInfestedBonus";

    private InfestedZone() {}

    /** Called by the nest every 100 ticks while it lives. */
    static void tick(ServerLevel level, EventData.Encounter nest) {
        Vec3 center = Vec3.atCenterOf(nest.pos);
        List<ServerPlayer> inside = level.players().stream()
                .filter(player -> !player.isSpectator() && player.distanceToSqr(center) < RADIUS * RADIUS).toList();
        if (inside.isEmpty()) return;
        for (ServerPlayer player : inside) warn(player, nest);
        int extras = level.getEntitiesOfClass(Mob.class, new AABB(nest.pos).inflate(RADIUS),
                mob -> mob.isAlive() && mob.getTags().contains(EXTRA)).size();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int day = (int) (level.getDayTime() / 24000);
        for (int i = 0; i < 3 && extras < inside.size() * EXTRA_PER_PLAYER; i++) {
            ServerPlayer near = inside.get(random.nextInt(inside.size()));
            BlockPos at = streetSpot(level, near, nest.pos);
            if (at == null) continue;
            Entity zombie = LazarusEvents.spawn(level, at, ZombieNest.spawnType(day, random), nest.id, false);
            if (zombie == null) zombie = LazarusEvents.spawn(level, at, "minecraft:zombie", nest.id, false);
            if (zombie != null) {
                zombie.addTag(EXTRA);
                extras++;
            }
        }
    }

    /** A street spot 24-40 blocks from the player, inside the zone, behind them or out of their sight. */
    static BlockPos streetSpot(ServerLevel level, ServerPlayer player, BlockPos nest) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble(Math.PI * 2), distance = random.nextDouble(24, 40);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (pos.distSqr(nest) > RADIUS * RADIUS || Math.abs(pos.getY() - player.getBlockY()) > 12) continue;
            if (!LazarusEvents.safe(level, pos)) continue;
            Vec3 toward = Vec3.atCenterOf(pos).subtract(player.getEyePosition()).normalize();
            if (player.getLookAngle().dot(toward) > 0.2) continue; // never in front of the player
            return pos;
        }
        return null;
    }

    private static void warn(ServerPlayer player, EventData.Encounter nest) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        ListTag warned = persisted.getList(WARNED, Tag.TAG_STRING);
        String id = nest.id.toString();
        for (Tag entry : warned) if (id.equals(entry.getAsString())) return;
        warned.add(StringTag.valueOf(id));
        while (warned.size() > 16) warned.remove(0);
        persisted.put(WARNED, warned);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        player.sendSystemMessage(LazarusEvents.radioText("You are entering an infested area. The nest is somewhere "
                + FactionMissions.direction(player.blockPosition(), nest.pos) + ". Find it and burn it out", nest.pos));
        player.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BIT.value(),
                net.minecraft.sounds.SoundSource.PLAYERS, 0.5f, 1.6f);
    }

    /** The living nest whose zone holds {@code pos}, or null. */
    static EventData.Encounter zoneAt(ServerLevel level, BlockPos pos) {
        for (EventData.Encounter encounter : EventData.get(level).encounters)
            if (ZombieNest.TYPE.equals(encounter.type) && encounter.pos.distSqr(pos) <= RADIUS * RADIUS) return encounter;
        return null;
    }

    /** One extra item from the current tier's supply loot, added before the container rolls its own loot. */
    static boolean addBonus(ServerLevel level, BlockPos pos) {
        BlockEntity block = level.getBlockEntity(pos);
        if (!(block instanceof RandomizableContainerBlockEntity container) || block.getPersistentData().getBoolean(BONUS)
                || !block.saveWithoutMetadata().contains(RandomizableContainerBlockEntity.LOOT_TABLE_TAG)
                || zoneAt(level, pos) == null) return false;
        block.getPersistentData().putBoolean(BONUS, true);
        int tier = Math.min(5, 1 + (int) (level.getDayTime() / 24000) / 10);
        LootTable table = level.getServer().getLootData().getLootTable(new ResourceLocation("dyairdrop", "chests/smallairdrop" + tier));
        List<ItemStack> rolled = table.getRandomItems(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos)).create(LootContextParamSets.CHEST));
        if (rolled.isEmpty()) return false;
        Container slots = container;
        for (int slot = slots.getContainerSize() - 1; slot >= 0; slot--) {
            if (!slots.getItem(slot).isEmpty()) continue;
            slots.setItem(slot, rolled.get(ThreadLocalRandom.current().nextInt(rolled.size())));
            block.setChanged();
            return true;
        }
        return false;
    }

    static final class Listener {
        @SubscribeEvent
        public void infestedContainer(PlayerInteractEvent.RightClickBlock event) {
            if (event.getLevel() instanceof ServerLevel level) addBonus(level, event.getPos());
        }
    }
}
