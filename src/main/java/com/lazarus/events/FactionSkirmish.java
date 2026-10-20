package com.lazarus.events;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** A small fight between two rival factions in a loaded area. */
final class FactionSkirmish {
    private static final ResourceLocation OWNER = ApiSupport.id("faction_skirmish");
    private static final long COOLDOWN = 72_000L;
    private static final long DURATION = 6_000L;

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 7) return;
        ServerLevel level = event.getServer().overworld();
        FactionConflictData data = FactionConflictData.get(level);
        long now = level.getGameTime();
        for (FactionConflictData.PendingReward reward : List.copyOf(data.rewards)) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(reward.player());
            if (player == null) continue;
            ApiResult result = NpcApi.adjustPoliticalReputation(player, reward.faction(), 3,
                    ApiSupport.id("skirmish_assist"), reward.fight());
            if (result.ok() || result.status() == ApiResult.Status.DUPLICATE) {
                data.rewards.remove(reward);
                data.setDirty();
            }
        }
        if (data.active == null) {
            if (now < data.nextAttempt || now % 1200 >= 20) return;
            FactionBaseData bases = FactionBaseData.get(level);
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator() || player.isCreative()) continue;
                FactionBaseData.Base base = bases.near(player.blockPosition(), 96);
                if (base == null) continue;
                String rival = rivalWithBase(bases, base.faction);
                if (rival == null) continue;
                BlockPos center = findSpot(level, base.pos);
                if (center != null && start(level, data, center, false, base.faction, rival)) return;
            }
            data.nextAttempt = now + 1200;
            data.setDirty();
            return;
        }
        FactionConflictData.Conflict fight = data.active;
        if (!level.isLoaded(fight.pos) || !level.isLoaded(fight.pos.offset(24, 0, 0))
                || !level.isLoaded(fight.pos.offset(-24, 0, 0))) return;
        boolean watched = level.players().stream().anyMatch(player -> !player.isSpectator()
                && player.distanceToSqr(Vec3.atCenterOf(fight.pos)) < 96 * 96);
        if (!watched || now - fight.started > DURATION) {
            finish(level, data, "withdrew", null);
            return;
        }
        List<Mob> first = combatants(level, fight.firstMembers);
        List<Mob> second = combatants(level, fight.secondMembers);
        if (first.isEmpty() || second.isEmpty()) {
            String winner = first.isEmpty() == second.isEmpty() ? null
                    : first.isEmpty() ? fight.secondFaction : fight.firstFaction;
            finish(level, data, winner == null ? "draw" : NpcFactions.get(winner).label() + " won", winner);
            return;
        }
        // Two initial route points provide the approach; contact begins after the patrol has moved.
        if (now - fight.started >= 100) {
            target(first, second);
            target(second, first);
        }
    }

    private static String rivalWithBase(FactionBaseData data, String faction) {
        NpcFactions.Definition definition = NpcFactions.get(faction);
        return definition == null ? null : definition.rivals().stream().sorted()
                .filter(rival -> BaseRoster.profile(rival) != null && data.faction(rival) != null)
                .findFirst().orElse(null);
    }

    private static BlockPos findSpot(ServerLevel level, BlockPos base) {
        for (int i = 0; i < 16; i++) {
            BlockPos at = ApiSupport.ring(level, base, 40 + (i % 4) * 5, i * Math.PI / 8);
            if (at != null && safe(level, at) && safe(level, at.offset(24, 0, 0))
                    && safe(level, at.offset(-24, 0, 0))
                    && unseen(level, at.offset(24, 0, 0)) && unseen(level, at.offset(-24, 0, 0))) return at;
        }
        return null;
    }

    private static boolean unseen(ServerLevel level, BlockPos at) {
        Vec3 end = Vec3.atCenterOf(at);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.distanceToSqr(end) > 96 * 96) continue;
            Vec3 direction = end.subtract(player.getEyePosition()).normalize();
            if (player.getLookAngle().dot(direction) < 0.35) continue;
            if (level.clip(new ClipContext(player.getEyePosition(), end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS) return false;
        }
        return true;
    }

    private static boolean safe(ServerLevel level, BlockPos at) {
        return level.isLoaded(at) && level.isLoaded(at.above()) && level.isLoaded(at.below())
                && EncounterScenery.clearable(level.getBlockState(at), true)
                && EncounterScenery.clearable(level.getBlockState(at.above()), false)
                && EncounterScenery.supported(level, at.below())
                && level.getBlockState(at).getFluidState().isEmpty() && level.canSeeSky(at);
    }

    private static boolean start(ServerLevel level, FactionConflictData data, BlockPos center, boolean testing) {
        for (String faction : BaseRoster.factions()) {
            NpcFactions.Definition definition = NpcFactions.get(faction);
            if (definition == null) continue;
            for (String rival : definition.rivals().stream().sorted().toList())
                if (BaseRoster.profile(rival) != null && (testing || (FactionBaseData.get(level).faction(faction) != null
                        && FactionBaseData.get(level).faction(rival) != null)))
                    return start(level, data, center, testing, faction, rival);
        }
        return false;
    }

    private static boolean start(ServerLevel level, FactionConflictData data, BlockPos center, boolean testing,
                                 String firstFaction, String secondFaction) {
        if (data.active != null || !safe(level, center)) return false;
        NpcFactions.Definition firstDefinition = NpcFactions.get(firstFaction);
        NpcFactions.Definition secondDefinition = NpcFactions.get(secondFaction);
        if (firstDefinition == null || secondDefinition == null || !firstDefinition.professions()
                || !secondDefinition.professions() || firstDefinition.survivorVariants().isEmpty()
                || secondDefinition.survivorVariants().isEmpty() || !NpcFactions.rivals(firstFaction, secondFaction)) return false;
        for (int x : new int[] {-24, 24}) for (int z = -1; z <= 1; z++)
            if (!safe(level, center.offset(x, 0, z))) return false;
        FactionConflictData.Conflict fight = new FactionConflictData.Conflict(UUID.randomUUID(), center,
                level.getGameTime(), testing, firstFaction, secondFaction);
        List<Mob> spawned = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Mob first = spawn(level, center.offset(-24, 0, i - 1),
                    "apocalypsenow:survivor_" + firstDefinition.variant(i), fight, firstFaction);
            Mob second = spawn(level, center.offset(24, 0, i - 1),
                    "apocalypsenow:survivor_" + secondDefinition.variant(i), fight, secondFaction);
            if (first == null || second == null) {
                if (first != null) spawned.add(first);
                if (second != null) spawned.add(second);
                spawned.forEach(Entity::discard);
                return false;
            }
            spawned.add(first);
            spawned.add(second);
            fight.firstMembers.add(first.getUUID());
            fight.secondMembers.add(second.getUUID());
        }
        data.active = fight;
        data.setDirty();
        for (ServerPlayer player : level.players()) if (player.distanceToSqr(Vec3.atCenterOf(center)) < 128 * 128)
            player.sendSystemMessage(Component.literal("Distant gunfire: " + firstDefinition.label() + " and "
                    + secondDefinition.label() + " are fighting nearby."));
        ApiSupport.LOGGER.info("Skirmish {} started at {}", fight.id, center);
        return true;
    }

    private static Mob spawn(ServerLevel level, BlockPos at, String type, FactionConflictData.Conflict fight) {
        return spawn(level, at, type, fight, null);
    }

    private static Mob spawn(ServerLevel level, BlockPos at, String type, FactionConflictData.Conflict fight,
                             String faction) {
        if (!(LazarusEvents.spawn(level, at, type, fight.id, false) instanceof Mob mob)) return null;
        if (faction != null && !NpcApi.assignFaction(mob, faction).ok()) { mob.discard(); return null; }
        // Field combatants: the expedition-soldier skin and armor of their faction.
        if (!NpcApi.assignProfession(mob, new ResourceLocation("lazarusnpcs", "expedition_soldier"), null).ok()
                || !NpcApi.assign(mob, new ScenarioAssignment(OWNER, fight.id, ScenarioOrder.GUARD_AREA,
                GlobalPos.of(level.dimension(), fight.pos), null, 32, DURATION + 1200, 6)).ok()) {
            mob.discard();
            return null;
        }
        NpcApi.armScenarioActor(mob, OWNER, fight.id);
        int side = at.getX() < fight.pos.getX() ? -1 : 1;
        NpcApi.setPatrolRoute(mob, OWNER, fight.id,
                List.of(at, fight.pos.offset(side * 8, 0, at.getZ() - fight.pos.getZ())));
        return mob;
    }

    private static List<Mob> combatants(ServerLevel level, List<UUID> ids) {
        List<Mob> living = new ArrayList<>();
        for (UUID id : ids) if (level.getEntity(id) instanceof Mob mob && mob.isAlive()
                && mob.getHealth() > mob.getMaxHealth() * 0.22f) living.add(mob);
        return living;
    }

    private static void target(List<Mob> attackers, List<Mob> enemies) {
        for (Mob mob : attackers) {
            LivingEntity current = mob.getTarget();
            if (current != null && current.isAlive() && enemies.contains(current)) continue;
            // Infected within striking distance are dealt with first; anything else gives way to the rival side.
            if (current != null && current.isAlive() && !attackers.contains(current) && mob.distanceToSqr(current) < 16)
                continue;
            enemies.stream().min((a, b) -> Double.compare(mob.distanceToSqr(a), mob.distanceToSqr(b)))
                    .ifPresent(mob::setTarget);
        }
    }

    @SubscribeEvent
    public void hurt(LivingHurtEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || event.getAmount() <= 0) return;
        Entity source = event.getSource().getEntity();
        if (source == null && event.getSource().getDirectEntity() instanceof Projectile projectile)
            source = projectile.getOwner();
        if (!(source instanceof ServerPlayer player)) return;
        FactionConflictData data = FactionConflictData.get(level);
        FactionConflictData.Conflict fight = data.active;
        if (fight == null) return;
        String helped = fight.firstMembers.contains(event.getEntity().getUUID()) ? fight.secondFaction
                : fight.secondMembers.contains(event.getEntity().getUUID()) ? fight.firstFaction : null;
        if (helped == null) return;
        String before = fight.participants.get(player.getUUID());
        fight.participants.put(player.getUUID(), before == null || before.equals(helped) ? helped : "mixed");
        data.setDirty();
    }

    private static void finish(ServerLevel level, FactionConflictData data, String result, String winner) {
        FactionConflictData.Conflict fight = data.active;
        if (fight == null) return;
        data.active = null; // Persist resolution before rewards or callbacks.
        data.nextAttempt = level.getGameTime() + COOLDOWN;
        data.lastResult = fight.id + ": " + result;
        data.setDirty();
        for (UUID id : fight.firstMembers) clear(level, id, fight.id);
        for (UUID id : fight.secondMembers) clear(level, id, fight.id);
        if (winner != null) {
            FactionBaseData bases = FactionBaseData.get(level);
            FactionBaseData.Base won = bases.faction(winner);
            FactionBaseData.Base lost = bases.faction(winner.equals(fight.firstFaction)
                    ? fight.secondFaction : fight.firstFaction);
            if (won != null) won.stock = Math.min(100, won.stock + 5);
            if (lost != null) lost.stock = Math.max(0, lost.stock - 5);
            bases.setDirty();
            fight.participants.forEach((player, helped) -> {
                if (winner.equals(helped)) data.rewards.add(new FactionConflictData.PendingReward(player, fight.id, winner));
            });
            data.setDirty();
        }
        for (ServerPlayer player : level.players()) if (player.distanceToSqr(Vec3.atCenterOf(fight.pos)) < 128 * 128)
            player.sendSystemMessage(Component.literal("Skirmish ended: " + result + "."));
        ApiSupport.LOGGER.info("Skirmish {} ended: {}", fight.id, result);
    }

    private static void clear(ServerLevel level, UUID id, UUID fight) {
        if (level.getEntity(id) instanceof Mob mob) {
            NpcApi.clearAssignment(mob, OWNER, fight);
            mob.setTarget(null);
        }
    }

    @SubscribeEvent
    public void actorLoaded(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) return;
        var order = NpcApi.assignment(mob).filter(a -> OWNER.equals(a.owner()));
        if (order.isEmpty()) return;
        FactionConflictData.Conflict active = FactionConflictData.get(level).active;
        if (active == null || !active.id.equals(order.get().encounterId())) {
            NpcApi.clearAssignment(mob, OWNER, order.get().encounterId());
            mob.setTarget(null);
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lazarusskirmish").requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.literal("test").executes(c -> test(c.getSource())))
                .then(Commands.literal("clear").executes(c -> clearTest(c.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        FactionConflictData data = FactionConflictData.get(source.getServer().overworld());
        source.sendSuccess(() -> Component.literal(data.active == null ? "No active skirmish; last: " + data.lastResult
                : "Active skirmish " + data.active.id + " at " + data.active.pos.toShortString()), false);
        return data.active == null ? 0 : 1;
    }

    private static int test(CommandSourceStack source) {
        if (source.getLevel().dimension() != Level.OVERWORLD) return 0;
        FactionConflictData data = FactionConflictData.get(source.getLevel());
        BlockPos pos = BlockPos.containing(source.getPosition());
        if (!start(source.getLevel(), data, pos, true)) {
            source.sendFailure(Component.literal("Skirmish needs a loaded 49x3 outdoor area and no active conflict."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Test skirmish " + data.active.id + " started."), false);
        return 1;
    }

    private static int clearTest(CommandSourceStack source) {
        ServerLevel level = source.getServer().overworld();
        FactionConflictData data = FactionConflictData.get(level);
        if (data.active == null || !data.active.testing) {
            source.sendFailure(Component.literal("Only an active test skirmish can be cleared."));
            return 0;
        }
        finish(level, data, "test cleared", null);
        return 1;
    }
}
