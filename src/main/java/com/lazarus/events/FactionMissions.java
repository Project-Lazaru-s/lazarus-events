package com.lazarus.events;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.dialogue.DialogueContext;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskContext;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjective;
import com.lazarus.npcs.api.task.TaskObjectives;
import com.lazarus.npcs.api.task.TaskState;
import com.lazarus.npcs.api.task.TaskView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Recon and supply theft share the NPC task UI; mission state remains on each player. */
final class FactionMissions {
    private static final ResourceLocation DIALOGUE = ApiSupport.id("faction_base_tasks");
    private static final ResourceLocation ROLE_DIALOGUE = ApiSupport.id("faction_base_professions");
    private static final String PACKAGE = "LazarusFactionMission";
    private static final String TARGETS = "LazarusFactionMissionTargets";
    private final Map<UUID, BlockPos> lastPosition = new HashMap<>();

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastPosition.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void clonePlayer(PlayerEvent.Clone event) {
        if (event.isWasDeath() && event.getOriginal().getPersistentData().contains(TARGETS))
            event.getEntity().getPersistentData().put(TARGETS,
                    event.getOriginal().getPersistentData().getCompound(TARGETS).copy());
    }

    static void register() {
        for (String faction : BaseRoster.factions()) {
            registerTask(mission(faction, false), faction, false);
            registerTask(mission(faction, true), faction, true);
        }
        ApiSupport.log("faction task dialogue", NpcApi.registerDialogue(new DialogueDefinition(
                DIALOGUE, Set.of(DialogueTrigger.INTERACT), 45, FactionMissions::dialogue)));
        ApiSupport.log("faction profession dialogue", NpcApi.registerDialogue(new DialogueDefinition(
                ROLE_DIALOGUE, Set.of(DialogueTrigger.INTERACT), 44, FactionMissions::professionDialogue)));
    }

    private static void registerTask(ResourceLocation id, String faction, boolean theft) {
        TaskObjective objective = theft ? new TheftObjective() : TaskObjectives.counter("observed", 5,
                Component.literal("Observation"));
        List<ItemStack> reward = theft ? ApiSupport.items(ApiSupport.stack("minecraft:iron_ingot", 3),
                ApiSupport.stack("apocalypsenow:bandage", 2))
                : ApiSupport.items(ApiSupport.stack("minecraft:bread", 3), ApiSupport.stack("minecraft:compass", 1));
        ApiSupport.log("faction task " + id, NpcApi.registerTask(new TaskDefinition(id,
                Component.literal(theft ? "Recover rival supplies" : "Scout the rival outpost"),
                Component.literal(theft ? "Take the sealed package from the rival depot and return to the giver. The rival may spot you; success affects both factions."
                        : "Observe the rival outpost from 16 to 48 blocks away for five seconds, then report to the giver. Success affects both factions."),
                objective, 72_000, TaskDefinition.DeathPolicy.FAIL, true, false,
                reward, faction, theft ? 8 : 4)));
    }

    private static ResourceLocation mission(String faction, boolean theft) {
        return ApiSupport.id(faction + (theft ? "_theft" : "_recon"));
    }

    private static String faction(ResourceLocation task) {
        if (!"lazarusevents".equals(task.getNamespace())) return null;
        String path = task.getPath();
        String name = path.endsWith("_theft") ? path.substring(0, path.length() - 6)
                : path.endsWith("_recon") ? path.substring(0, path.length() - 6) : "";
        return BaseRoster.profile(name) == null ? null : name;
    }

    private static boolean theft(ResourceLocation task) {
        return faction(task) != null && task.getPath().endsWith("_theft");
    }

    private static FactionBaseData.Base rivalBase(FactionBaseData data, String faction) {
        NpcFactions.Definition definition = NpcFactions.get(faction);
        if (definition == null) return null;
        return definition.rivals().stream().sorted().map(data::faction).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private static void rememberTarget(ServerPlayer player, ApiResult result, FactionBaseData.Base target) {
        if (!result.ok() || result.id() == null) return;
        CompoundTag targets = player.getPersistentData().getCompound(TARGETS);
        targets.putString(result.id().toString(), target.faction);
        player.getPersistentData().put(TARGETS, targets);
    }

    private static FactionBaseData.Base targetFor(ServerPlayer player, FactionBaseData data, TaskView task) {
        String issuer = faction(task.taskId());
        if (issuer == null) return null;
        CompoundTag targets = player.getPersistentData().getCompound(TARGETS);
        String saved = targets.getString(task.instanceId().toString());
        return saved.isEmpty() ? rivalBase(data, issuer) : data.faction(saved);
    }

    private static Optional<DialoguePage> dialogue(DialogueContext c) {
        if (c.encounterId() == null || !(c.npc().level() instanceof ServerLevel level)) return Optional.empty();
        FactionBaseData data = FactionBaseData.get(level);
        FactionBaseData.Base issuer = data.bases.stream().filter(b -> b.id.equals(c.encounterId())).findFirst().orElse(null);
        if (issuer == null || issuer.staff.isEmpty() || !issuer.staff.get(0).equals(c.npc().getUUID())
                || NpcApi.reputation(c.player(), issuer.faction) <= -20) return Optional.empty();
        if (issuer.leaderReadyAt > level.getGameTime()) return Optional.empty();
        FactionBaseData.Base target = rivalBase(data, issuer.faction);
        if (target == null) return Optional.empty();
        if (NpcApi.encounterTask(c.player()).filter(t -> t.state().open()).isPresent()) return Optional.empty();
        String direction = direction(issuer.pos, target.pos);
        return Optional.of(new DialoguePage(Component.literal("Rival activity lies " + direction
                + ". Can you investigate for " + issuer.faction + "?"), List.of(
                new DialogueOption("recon", Component.literal("Scout their outpost"), ctx -> offer(ctx, issuer, false)),
                new DialogueOption("theft", Component.literal("Recover their supplies"), ctx -> offer(ctx, issuer, true)),
                new DialogueOption("menu", Component.literal("Trade and services"),
                        ctx -> NpcApi.openNpcMenu(ctx.player(), ctx.npc())))));
    }

    private static Optional<DialoguePage> professionDialogue(DialogueContext c) {
        if (c.encounterId() == null || !(c.npc().level() instanceof ServerLevel level)) return Optional.empty();
        FactionBaseData.Base base = FactionBaseData.get(level).bases.stream()
                .filter(b -> b.id.equals(c.encounterId()) && b.staff.contains(c.npc().getUUID())).findFirst().orElse(null);
        if (base == null) return Optional.empty();
        // A resident who gave the player a task lets the task dialogue handle hand-overs and reports.
        if (NpcApi.encounterTask(c.player()).filter(t -> t.state().open() && t.giverId().equals(c.npc().getUUID())).isPresent())
            return Optional.empty();
        if (NpcApi.reputation(c.player(), base.faction) <= -20)
            return Optional.of(new DialoguePage(Component.literal("You're not welcome at this outpost."), List.of(
                    new DialogueOption("leave", Component.literal("Leave"), ctx -> {}))));
        String profession = NpcApi.profession(c.npc()).map(ResourceLocation::getPath).orElse("");
        boolean refuge = NpcFactions.get(base.faction) != null && NpcFactions.get(base.faction).rivals().isEmpty();
        String line = switch (profession) {
            case "leader" -> base.leaderReadyAt > level.getGameTime()
                    ? "I'm holding this place together. Give us a day to settle the succession."
                    : refuge ? HavenRefuge.leaderLine(c.player())
                    : "I coordinate this outpost. Ask me about work against our rivals.";
            case "guard" -> refuge ? "I keep an eye on the gate. Walkers come out of the trees at dusk."
                    : "I watch the approaches. Speak to our leader about faction work.";
            case "armorer" -> "I keep weapons and protection ready. Check my stock if you have supplies to trade.";
            case "trader" -> "Food, medicine and travel supplies are scarce. Have a look at what I can spare.";
            case "medic" -> "Bring bandages and clean water and I can patch you up. Check my services.";
            case "expedition_soldier" -> "We range out with the next patrol. Until then, we rest and keep our kit ready.";
            default -> refuge ? "You got me out of there, " + c.player().getGameProfile().getName() + ". I won't forget it."
                    : "State your business.";
        };
        if (refuge && "leader".equals(profession) && base.leaderReadyAt <= level.getGameTime())
            return Optional.of(new DialoguePage(Component.literal(line), List.of(
                    new DialogueOption("request", Component.literal("What does the refuge need today?"),
                            ctx -> HavenRefuge.offer(ctx.player(), ctx.npc(), base)),
                    new DialogueOption("menu", Component.literal("Talk and services"),
                            ctx -> NpcApi.openNpcMenu(ctx.player(), ctx.npc())))));
        return Optional.of(new DialoguePage(Component.literal(line), List.of(
                new DialogueOption("menu", Component.literal("Talk and services"),
                        ctx -> NpcApi.openNpcMenu(ctx.player(), ctx.npc())))));
    }

    private static void offer(DialogueContext c, FactionBaseData.Base issuer, boolean theft) {
        if (!(c.player().level() instanceof ServerLevel level)) return;
        if (issuer.staff.isEmpty() || !issuer.staff.get(0).equals(c.npc().getUUID())
                || issuer.leaderReadyAt > level.getGameTime()) return;
        FactionBaseData.Base target = rivalBase(FactionBaseData.get(level), issuer.faction);
        if (target == null) return;
        ApiResult result = NpcApi.offerTask(c.player(), c.npc(), mission(issuer.faction, theft), issuer.id,
                destination(level, target, theft));
        rememberTarget(c.player(), result, target);
        if (!result.ok()) c.player().sendSystemMessage(Component.literal(result.message()));
        else c.player().sendSystemMessage(Component.literal("Last report: rival outpost "
                + direction(issuer.pos, target.pos) + ", roughly " + Math.round(Math.sqrt(issuer.pos.distSqr(target.pos)) / 50) * 50
                + " blocks away. The journal tracks your task."));
    }

    static boolean offerTest(ServerPlayer player, boolean theft) {
        ServerLevel level = player.serverLevel();
        FactionBaseData data = FactionBaseData.get(level);
        FactionBaseData.Base issuer = data.near(player.blockPosition(), 20);
        if (issuer == null || rivalBase(data, issuer.faction) == null) {
            player.sendSystemMessage(Component.literal("Stand near a faction base; both bases must exist."));
            return false;
        }
        if (issuer.leaderReadyAt > level.getGameTime()) {
            player.sendSystemMessage(Component.literal("The new leader is still settling into command."));
            return false;
        }
        UUID leader = issuer.staff.isEmpty() ? null : issuer.staff.get(0);
        Mob giver = level.getEntitiesOfClass(Mob.class, new AABB(Vec3.atCenterOf(player.blockPosition()),
                Vec3.atCenterOf(player.blockPosition())).inflate(14), mob -> mob.getUUID().equals(leader))
                .stream().findFirst().orElse(null);
        if (giver == null || giver.distanceTo(player) > 16) {
            player.sendSystemMessage(Component.literal("No base NPC is loaded within 16 blocks."));
            return false;
        }
        FactionBaseData.Base target = rivalBase(data, issuer.faction);
        ApiResult result = NpcApi.offerTask(player, giver, mission(issuer.faction, theft), issuer.id,
                destination(level, target, theft));
        rememberTarget(player, result, target);
        if (!result.ok()) player.sendSystemMessage(Component.literal(result.message()));
        return result.ok();
    }

    /** The journal shows Dest: the rival depot for theft, the rival outpost for recon. */
    static GlobalPos destination(ServerLevel level, FactionBaseData.Base target, boolean theft) {
        return GlobalPos.of(level.dimension(), theft ? depot(target) : target.pos);
    }

    static String direction(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        String ns = Math.abs(dz) > 24 ? (dz < 0 ? "north" : "south") : "";
        String ew = Math.abs(dx) > 24 ? (dx < 0 ? "west" : "east") : "";
        return ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew;
    }

    @SubscribeEvent
    public void observe(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 3) return;
        ServerLevel level = event.getServer().overworld();
        FactionBaseData data = FactionBaseData.get(level);
        for (ServerPlayer player : level.players()) {
            BlockPos old = lastPosition.put(player.getUUID(), player.blockPosition());
            if (old != null && old.distSqr(player.blockPosition()) > 64 * 64)
                ApiSupport.LOGGER.info("Faction mission observer {} moved over 64 blocks in one second (teleport or fast travel)", player.getUUID());
            Optional<TaskView> mine = NpcApi.encounterTask(player);
            if (mine.isEmpty() || mine.get().state() != TaskState.ACTIVE || theft(mine.get().taskId())) continue;
            FactionBaseData.Base target = targetFor(player, data, mine.get());
            if (target == null || player.isSpectator()) continue;
            double distance = player.distanceToSqr(Vec3.atCenterOf(target.pos));
            if (distance >= 16 * 16 && distance <= 48 * 48
                    && visible(level, player, target.pos.above(BaseRoster.layout(target).height())))
                NpcApi.progressTask(player, mine.get().instanceId(), "observed", 1);
        }
    }

    private static boolean visible(ServerLevel level, ServerPlayer player, BlockPos target) {
        Vec3 end = Vec3.atCenterOf(target);
        var hit = level.clip(new ClipContext(player.getEyePosition(), end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(end) < 9 * 9;
    }

    @SubscribeEvent
    public void depot(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) return;
        Optional<TaskView> mine = NpcApi.encounterTask(player);
        if (mine.isEmpty() || mine.get().state() != TaskState.ACTIVE || !theft(mine.get().taskId())) return;
        FactionBaseData.Base target = targetFor(player, FactionBaseData.get(level), mine.get());
        if (target == null || !event.getPos().equals(depot(target))) return;
        event.setCanceled(true);
        if (target.stock <= 0) {
            player.sendSystemMessage(Component.literal("The rival depot is empty."));
            return;
        }
        UUID missionId = mine.get().instanceId();
        ItemStack packet = new ItemStack(Items.PAPER);
        packet.setHoverName(Component.literal("Sealed " + target.faction + " supplies"));
        packet.getOrCreateTag().putUUID(PACKAGE, missionId);
        if (!player.getInventory().add(packet)) {
            player.sendSystemMessage(Component.literal("Make room in your inventory before taking the package."));
            return;
        }
        ApiResult result = NpcApi.progressTask(player, missionId, "stolen", 1);
        if (!result.ok()) { removePackage(player, missionId); return; }
        FactionBaseData data = FactionBaseData.get(level);
        target.stock = Math.max(0, target.stock - 10);
        data.setDirty();
        boolean spotted = level.getEntitiesOfClass(Mob.class, new AABB(Vec3.atCenterOf(target.pos),
                Vec3.atCenterOf(target.pos)).inflate(16), mob -> target.staff.contains(mob.getUUID()))
                .stream().anyMatch(mob -> mob.distanceToSqr(player) < 12 * 12 && mob.hasLineOfSight(player));
        if (spotted) {
            NpcApi.adjustReputation(player, target.faction, -5, ApiSupport.id("caught_stealing"), missionId);
            player.sendSystemMessage(Component.literal("A guard spotted the theft. " + target.faction + " standing -5."));
        }
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(Component.literal("Package secured. Return to the mission giver."));
    }

    private static BlockPos depot(FactionBaseData.Base base) {
        return BaseRoster.layout(base).at(base.pos, BaseRoster.layout(base).depot());
    }

    private static int packageSlot(ServerPlayer player, UUID id) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            CompoundTag tag = stack.getTag();
            if (stack.is(Items.PAPER) && tag != null && tag.hasUUID(PACKAGE) && tag.getUUID(PACKAGE).equals(id)) return i;
        }
        return -1;
    }

    private static void removePackage(ServerPlayer player, UUID id) {
        int slot = packageSlot(player, id);
        if (slot >= 0) player.getInventory().getItem(slot).shrink(1);
    }

    private static final class TheftObjective implements TaskObjective {
        @Override public boolean complete(TaskContext context) { return context.counter("stolen") > 0; }
        @Override public Component progress(TaskContext context) {
            return Component.literal(complete(context) ? "Return with the sealed package" : "Take the rival depot package");
        }
        @Override public boolean turnIn(TaskContext context) {
            int slot = packageSlot(context.player(), context.instanceId());
            if (slot < 0) return false;
            context.player().getInventory().getItem(slot).shrink(1);
            context.player().inventoryMenu.broadcastChanges();
            return true;
        }
    }

    @SubscribeEvent
    public void taskState(NpcApiEvents.TaskStateChanged event) {
        if (faction(event.getTaskId()) != null && !event.getTo().open()) {
            CompoundTag targets = event.getPlayer().getPersistentData().getCompound(TARGETS);
            targets.remove(event.getInstanceId().toString());
            event.getPlayer().getPersistentData().put(TARGETS, targets);
        }
        if (!theft(event.getTaskId()) || event.getTo().open() || event.getTo() == TaskState.COMPLETED) return;
        removePackage(event.getPlayer(), event.getInstanceId());
    }
}
