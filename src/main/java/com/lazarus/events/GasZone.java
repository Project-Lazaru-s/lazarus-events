package com.lazarus.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.NpcApi;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Infectious green gas filling an enclosed space (a basement, sewer, cistern or building room) for two days. Without a
 * gas mask it sickens, poisons, then kills, and can pass on The Hordes infection; a mask blocks it but wears down
 * (a breathing filter restores it), and a full hazmat suit blocks it without wear. Unmasked human NPCs choke too and
 * flee for the nearest doorway. The best loot waits deep inside.
 */
final class GasZone {
    static final String TYPE = "infection_zone";
    static final long DURATION = 48_000L;
    static final int MAX_CELLS = 400, REACH = 20;
    static final TagKey<Item> GAS_MASKS = TagKey.create(Registries.ITEM, ApiSupport.id("gas_masks"));
    static final TagKey<Item> HAZMAT = TagKey.create(Registries.ITEM, ApiSupport.id("hazmat"));
    static final ResourceLocation FILTER = new ResourceLocation("apocalypsenow", "breathingfiltermodule");
    private static final String CELLS = "Cells", EXITS = "Exits", EXPIRES = "Expires";
    /** Seconds each player has spent breathing gas without protection; resets when they get out. */
    private static final Map<UUID, Integer> EXPOSURE = new HashMap<>();

    private GasZone() {}

    /** The enclosed space around {@code start}, up to 400 floor cells so a sewer stretch fits. */
    static BuildingHideout.Room space(ServerLevel level, BlockPos start) { return BuildingHideout.room(level, start, MAX_CELLS, REACH); }

    /** The gas-sized space around a building room's center, or null. */
    static BuildingHideout.Room space(ServerLevel level, BuildingHideout.Room room) { return room == null ? null : space(level, room.center()); }

    /** An underground space 30-80 blocks from the player when possible, otherwise any roofed room. */
    static BuildingHideout.Room find(ServerLevel level, ServerPlayer player, EventData data) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < CityWorldSites.SEWER_PREFERENCE) { // the gas likes sewers best
            BuildingHideout.Room sewer = CityWorldSites.findSewer(level, player, data, MAX_CELLS, REACH);
            if (sewer != null) return sewer;
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            double angle = random.nextDouble(Math.PI * 2), distance = random.nextDouble(30, 80);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = surface - 4; y > Math.max(level.getMinBuildHeight() + 1, surface - 32); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BuildingHideout.Room room = space(level, pos);
                if (room == null) continue;
                if (data.near(room.center()) || player.distanceToSqr(Vec3.atCenterOf(room.center())) < 24 * 24) break;
                return room;
            }
        }
        return space(level, BuildingHideout.find(level, player, data));
    }

    /** The medical reward barrel deep inside, with a chance of a serum; false when the space has no wall to use. */
    static boolean place(ServerLevel level, BuildingHideout.Room room) {
        int tier = Math.min(5, 1 + (int) (level.getDayTime() / 24000) / 10);
        BlockPos at = barrel(level, room, "medicalairdrop" + tier);
        if (at == null) return false;
        if (level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity barrel && ThreadLocalRandom.current().nextFloat() < 0.3f)
            barrel.setItem(barrel.getContainerSize() - 1, new ItemStack(VaccineProject.SERUM.get()));
        return true;
    }

    /** A barrel of dyairdrop {@code loot} in the wall cell farthest from every way out, or null. */
    static BlockPos barrel(ServerLevel level, BuildingHideout.Room room, String loot) {
        BuildingHideout.Cells cells = BuildingHideout.cells(level, room);
        if (cells.walls().isEmpty()) return null;
        BlockPos deepest = cells.walls().get(0);
        double best = -1;
        for (BlockPos cell : cells.walls()) {
            double nearestExit = room.openings().stream().mapToDouble(cell::distSqr).min().orElse(Double.MAX_VALUE);
            if (nearestExit > best) { best = nearestExit; deepest = cell; }
        }
        if (!level.setBlock(deepest, Blocks.BARREL.defaultBlockState(), 3)) return null;
        ResourceLocation table = new ResourceLocation("dyairdrop", "chests/" + loot);
        if (level.getBlockEntity(deepest) instanceof RandomizableContainerBlockEntity barrel
                && level.getServer().getLootData().getLootTable(table) != LootTable.EMPTY)
            barrel.setLootTable(table, ThreadLocalRandom.current().nextLong());
        return deepest;
    }

    /** Encounters that fill their space with gas. */
    static boolean gassed(String type) { return TYPE.equals(type) || Quarantine.TYPE.equals(type); }

    static void record(EventData.Encounter encounter, BuildingHideout.Room room, long now) {
        encounter.data.putLongArray(CELLS, room.cells().stream().mapToLong(BlockPos::asLong).toArray());
        encounter.data.putLongArray(EXITS, room.openings().stream().mapToLong(BlockPos::asLong).toArray());
        encounter.data.putLong(EXPIRES, now + DURATION);
    }

    /** True when a player standing at {@code feet} breathes the zone's gas (two blocks tall over each floor cell). */
    static boolean inGas(Set<Long> cells, BlockPos feet) { return cells.contains(feet.asLong()) || cells.contains(feet.below().asLong()); }

    static boolean hazmat(ServerPlayer player) {
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
            if (!player.getItemBySlot(slot).is(HAZMAT)) return false;
        return true;
    }

    static boolean masked(ServerPlayer player) { return player.getItemBySlot(EquipmentSlot.HEAD).is(GAS_MASKS); }

    /** One second in the gas for this player. Returns the unprotected exposure in seconds (0 when protected). */
    static int breathe(ServerPlayer player, long second) {
        if (hazmat(player)) { EXPOSURE.remove(player.getUUID()); return 0; }
        if (masked(player)) {
            EXPOSURE.remove(player.getUUID());
            if (second % 2 == 0) {
                ItemStack mask = player.getItemBySlot(EquipmentSlot.HEAD);
                if (mask.isDamageableItem()) mask.hurtAndBreak(1, player, broken -> broken.broadcastBreakEvent(EquipmentSlot.HEAD));
            }
            return 0;
        }
        int seconds = EXPOSURE.merge(player.getUUID(), 1, Integer::sum);
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, false, false));
        if (seconds % 3 == 1) player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_HURT_DROWN, SoundSource.PLAYERS, 0.6f, 0.8f);
        if (seconds > 5) player.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
        if (seconds > 15) player.hurt(player.damageSources().magic(), 2.0f);
        if (seconds % 10 == 0 && ThreadLocalRandom.current().nextFloat() < 0.25f) {
            MobEffect infected = ForgeRegistries.MOB_EFFECTS.getValue(VaccineProject.INFECTED);
            if (infected != null && !player.hasEffect(infected)) player.addEffect(new MobEffectInstance(infected, 12_000, 0));
        }
        return seconds;
    }

    /** One second in the gas for a human NPC: unmasked ones choke and head for the nearest way out. */
    static void choke(Mob npc, EventData.Encounter encounter) {
        if (npc.getItemBySlot(EquipmentSlot.HEAD).is(GAS_MASKS)) return;
        npc.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0));
        npc.hurt(npc.damageSources().magic(), 1.0f);
        BlockPos out = java.util.Arrays.stream(encounter.data.getLongArray(EXITS)).mapToObj(BlockPos::of)
                .min(Comparator.comparingDouble(npc.blockPosition()::distSqr)).orElse(null);
        if (out == null) // no doorway known (a sealed basement): just get away from the leak
            out = BlockPos.containing(npc.position().add(npc.position().subtract(Vec3.atCenterOf(encounter.pos)).normalize().scale(16)));
        npc.getNavigation().moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 1.3);
    }

    static Set<Long> cells(EventData.Encounter encounter) {
        Set<Long> set = new HashSet<>();
        for (long packed : encounter.data.getLongArray(CELLS)) set.add(packed);
        return set;
    }

    static final class Listener {
        @SubscribeEvent
        public void gasTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 3) return;
            ServerLevel level = event.getServer().overworld();
            EventData data = EventData.get(level);
            long now = level.getGameTime();
            Set<UUID> exposed = new HashSet<>();
            for (EventData.Encounter encounter : List.copyOf(data.encounters)) {
                if (!gassed(encounter.type)) continue;
                if (now >= encounter.data.getLong(EXPIRES)) {
                    LazarusEvents.radio(level, encounter.pos, "Military band: the chemical leak has dispersed, masks may come off");
                    data.remove(encounter.id);
                    continue;
                }
                Vec3 center = Vec3.atCenterOf(encounter.pos);
                List<ServerPlayer> near = level.players().stream()
                        .filter(player -> !player.isSpectator() && player.distanceToSqr(center) < 48 * 48).toList();
                if (near.isEmpty() || !level.isLoaded(encounter.pos)) continue;
                Set<Long> cells = cells(encounter);
                List<Long> list = new ArrayList<>(cells);
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < Math.min(24, list.size()); i++) {
                    BlockPos cell = BlockPos.of(list.get(random.nextInt(list.size())));
                    level.sendParticles(i % 3 == 0 ? ParticleTypes.ITEM_SLIME : ParticleTypes.SNEEZE,
                            cell.getX() + random.nextDouble(), cell.getY() + random.nextDouble() * 1.8, cell.getZ() + random.nextDouble(),
                            2, 0.2, 0.2, 0.2, 0.01);
                }
                if (encounter.data.contains(Quarantine.LEAK)) { // the leaking barrel smokes thicker
                    BlockPos leak = BlockPos.of(encounter.data.getLong(Quarantine.LEAK));
                    level.sendParticles(ParticleTypes.ITEM_SLIME, leak.getX() + 0.5, leak.getY() + 1.1, leak.getZ() + 0.5, 10, 0.3, 0.4, 0.3, 0.02);
                    level.sendParticles(ParticleTypes.SNEEZE, leak.getX() + 0.5, leak.getY() + 1.3, leak.getZ() + 0.5, 6, 0.2, 0.3, 0.2, 0.02);
                }
                if (random.nextInt(6) == 0) level.playSound(null, encounter.pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT, 0.4f, 0.5f);
                for (ServerPlayer player : near) {
                    if (player.isCreative() || !inGas(cells, player.blockPosition())) continue;
                    exposed.add(player.getUUID());
                    breathe(player, now / 20);
                }
                for (Mob npc : level.getEntitiesOfClass(Mob.class, new AABB(encounter.pos).inflate(REACH + 4),
                        mob -> mob.isAlive() && inGas(cells, mob.blockPosition()) && NpcApi.identity(mob).isPresent()))
                    choke(npc, encounter);
            }
            EXPOSURE.keySet().retainAll(exposed); // stepping out clears the build-up
        }

        /** A breathing filter, used with a gas mask on, restores the mask. */
        @SubscribeEvent
        public void filter(PlayerInteractEvent.RightClickItem event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || !FILTER.equals(ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem())))
                return;
            ItemStack mask = player.getItemBySlot(EquipmentSlot.HEAD);
            if (!mask.is(GAS_MASKS) || !mask.isDamaged()) return;
            mask.setDamageValue(0);
            if (!player.getAbilities().instabuild) event.getItemStack().shrink(1);
            player.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 1f, 1.2f);
            player.displayClientMessage(Component.literal("Fresh filter fitted."), true);
            event.setCanceled(true);
        }
    }
}
