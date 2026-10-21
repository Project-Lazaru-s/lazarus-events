package com.lazarus.events;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;

/** Mining hazard: separately saved air volume, finite duration, delayed destructive ignition, no infection. */
final class NaturalGas {
    static final String TYPE = "natural_gas";
    private static final String COOLDOWN = "LazarusGasNext";
    private record Break(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState before, long due) {}
    private final List<Break> pending = new ArrayList<>();

    /** Preserve vanilla explosion damage semantics while identifying this hazard alone. */
    private static final class GasBlastSource extends DamageSource {
        GasBlastSource(ServerLevel level, Vec3 at) { super(level.damageSources().explosion(null, null).typeHolder(), at); }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void blastDamage(LivingHurtEvent event) {
        if (event.getSource() instanceof GasBlastSource)
            event.setAmount(event.getAmount() * TerritoryConfig.BLAST_DAMAGE.get().floatValue());
    }

    static void explode(ServerLevel level, BlockPos at) {
        Vec3 center = Vec3.atCenterOf(at);
        level.explode(null, new GasBlastSource(level, center), null, center.x, center.y, center.z,
                TerritoryConfig.BLAST_POWER.get().floatValue(), false, Level.ExplosionInteraction.BLOCK);
    }

    /** Vanilla blast rays and entity queries must also stay in loaded chunks. */
    static boolean blastLoaded(ServerLevel level, BlockPos at) {
        int reach = (int) Math.ceil(TerritoryConfig.BLAST_POWER.get() * 2) + 1;
        for (int x = (at.getX() - reach) >> 4; x <= (at.getX() + reach) >> 4; x++)
            for (int z = (at.getZ() - reach) >> 4; z <= (at.getZ() + reach) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }

    static boolean mineable(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.BLOCK, new net.minecraft.resources.ResourceLocation("forge", "ores")));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void mining(BlockEvent.BreakEvent event) {
        if (!LazarusEvents.enabled() || !TerritoryConfig.NATURAL.get()
                || !(event.getPlayer() instanceof ServerPlayer player) || player.isCreative() || player.isSpectator()
                || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || event.getPos().getY() >= TerritoryConfig.MAX_Y.get() || !mineable(event.getState())) return;
        if (pending.stream().anyMatch(b -> b.player == player)) return;
        // Verify on the next tick: a later listener or protection plugin can still cancel the actual break.
        pending.add(new Break(level, player, event.getPos().immutable(), event.getState(), level.getGameTime() + 1));
    }

    static boolean release(ServerLevel level, ServerPlayer player, BlockPos start, boolean forced) {
        long now = level.getGameTime();
        CompoundTag saved = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (!forced && now < saved.getLong(COOLDOWN)) return false;
        EventData data = EventData.get(level);
        if (data.encounters.stream().filter(e -> TYPE.equals(e.type)).count() >= 16) return false;
        Set<Long> cells = GasVolumes.pocket(level, start);
        if (cells.isEmpty() || data.encounters.stream().filter(e -> TYPE.equals(e.type))
                .anyMatch(e -> !java.util.Collections.disjoint(cells, GasZone.cells(e)))) return false;
        EventData.Encounter e = data.add(UUID.randomUUID(), TYPE, start, now);
        e.discovered = true;
        e.data.putLongArray("Cells", cells.stream().mapToLong(Long::longValue).toArray());
        e.data.putLong("Expires", now + TerritoryConfig.DURATION.get() * 20L);
        if (!forced) {
            saved.putLong(COOLDOWN, now + TerritoryConfig.COOLDOWN.get() * 20L);
            player.getPersistentData().put(Player.PERSISTED_NBT_TAG, saved);
        }
        data.setDirty();
        level.playSound(null, start, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 0.6f);
        return true;
    }

    static boolean inside(Set<Long> cells, net.minecraft.world.entity.LivingEntity entity) {
        return cells.contains(entity.blockPosition().asLong()) || cells.contains(BlockPos.containing(entity.getEyePosition()).asLong());
    }

    static void breathe(ServerPlayer player, long second) {
        if (GasZone.protectedBreath(player, second)) return;
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0, false, false));
        if (second % 3 == 0) player.hurt(player.damageSources().magic(), 1f);
    }

    /** First ignition wins; later sparks cannot restart the warning timer. */
    static void ignite(ServerLevel level, EventData.Encounter e, BlockPos spark) {
        if (e.data.contains("Ignites")) return;
        e.data.putLong("Ignites", level.getGameTime() + 60);
        e.data.putLong("Spark", spark.asLong());
        EventData.get(level).setDirty();
        level.playSound(null, spark, SoundEvents.CREEPER_PRIMED, SoundSource.BLOCKS, 1.2f, 0.6f);
    }

    static void spark(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return;
        for (EventData.Encounter e : EventData.get(level).encounters)
            if (TYPE.equals(e.type) && GasZone.cells(e).contains(pos.asLong())) ignite(level, e, pos);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void lighter(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level && (event.getItemStack().is(Items.FLINT_AND_STEEL)
                || event.getItemStack().is(Items.FIRE_CHARGE)))
            spark(level, event.getPos().relative(event.getFace() == null ? Direction.UP : event.getFace()));
    }

    static boolean flame(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(Blocks.LAVA)
                || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
                || state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)
                || state.getBlock() instanceof CandleBlock && state.getValue(CandleBlock.LIT);
    }

    static boolean torch(ServerPlayer p) {
        return p.getMainHandItem().is(Items.TORCH) || p.getOffhandItem().is(Items.TORCH)
                || p.getMainHandItem().is(Items.SOUL_TORCH) || p.getOffhandItem().is(Items.SOUL_TORCH);
    }

    static void playerIgnition(ServerLevel level, EventData.Encounter e, Set<Long> cells, ServerPlayer p) {
        if (!p.isSpectator() && inside(cells, p) && (torch(p) || p.isOnFire())) ignite(level, e, p.blockPosition());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (Break b : List.copyOf(pending)) {
            if (b.level.getGameTime() < b.due) continue;
            pending.remove(b);
            ServerPlayer p = b.player;
            if (p.isRemoved() || p.level() != b.level || p.isCreative() || p.isSpectator() || !b.level.hasChunkAt(b.pos)
                    || b.level.getBlockState(b.pos).equals(b.before) || !LazarusEvents.enabled() || !TerritoryConfig.NATURAL.get()) continue;
            if (ThreadLocalRandom.current().nextInt(TerritoryConfig.CHANCE.get()) == 0) release(b.level, p, b.pos, false);
        }
        ServerLevel level = event.getServer().overworld();
        long now = level.getGameTime();
        EventData data = EventData.get(level);
        Set<UUID> breathed = new HashSet<>();
        for (EventData.Encounter e : List.copyOf(data.encounters)) {
            if (!TYPE.equals(e.type)) continue;
            // Ignition takes precedence over normal expiry, even if everyone logs out during the warning.
            if (e.data.contains("Ignites") && now >= e.data.getLong("Ignites")) {
                BlockPos at = BlockPos.of(e.data.getLong("Spark"));
                if (!blastLoaded(level, at)) continue; // resume only when the entire blast area is loaded
                data.remove(e.id);
                explode(level, at);
                continue;
            }
            if (now >= e.data.getLong("Expires") && !e.data.contains("Ignites")) { data.remove(e.id); continue; }
            if (now % 20 != 7) continue;
            Set<Long> cells = GasZone.cells(e);
            if (cells.isEmpty()) { data.remove(e.id); continue; }
            List<Long> list = new ArrayList<>(cells);
            for (int i = 0; i < Math.min(24, list.size()); i++) {
                BlockPos p = BlockPos.of(list.get(ThreadLocalRandom.current().nextInt(list.size())));
                if (!level.hasChunkAt(p)) continue;
                level.sendParticles(new DustParticleOptions(new Vector3f(.45f, .52f, .37f), 1.5f),
                        p.getX() + .5, p.getY() + .5, p.getZ() + .5, 2, .4, .4, .4, 0);
            }
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator() || !inside(cells, p)) continue;
                if (!p.isCreative() && breathed.add(p.getUUID())) breathe(p, now / 20);
                playerIgnition(level, e, cells, p);
            }
            if (e.data.contains("Ignites")) continue;
            outer: for (long v : cells) {
                BlockPos p = BlockPos.of(v);
                if (level.hasChunkAt(p) && flame(level.getBlockState(p))) { ignite(level, e, p); break; }
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (level.hasChunkAt(n) && flame(level.getBlockState(n))) { ignite(level, e, p); break outer; }
                }
            }
        }
    }

    @SubscribeEvent
    public void stopped(net.minecraftforge.event.server.ServerStoppedEvent event) { pending.clear(); }
}
