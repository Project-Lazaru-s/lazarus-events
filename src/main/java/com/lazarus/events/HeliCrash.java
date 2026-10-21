package com.lazarus.events;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.dialogue.DialogueContext;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjectives;
import com.lazarus.npcs.api.task.TaskState;
import com.lazarus.npcs.api.task.TaskView;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** A broken military helicopter, its signal smoke, scavengers and an occasional wounded pilot. */
final class HeliCrash {
    static final String TYPE = "heli_crash";
    static final long FIRE_TICKS = 24_000, SMOKE_TICKS = 48_000;
    static final int HORDE_CAP = 12;
    static final String FIRE = "Fire", SMOKE = "Smoke", NEXT_HORDE = "NextHorde";
    static final ResourceLocation OWNER = ApiSupport.id("heli_pilot"), TASK = ApiSupport.id("heli_pilot_rescue");
    private static final ResourceLocation PLEA = ApiSupport.id("heli_pilot_plea");
    private static final ResourceLocation THANKS = ApiSupport.id("heli_pilot_thanks");
    private static final ResourceLocation REWARD = ApiSupport.id("heli_pilot_rescued");

    private HeliCrash() {}

    static void register() {
        ApiSupport.log("pilot task", NpcApi.registerTask(new TaskDefinition(TASK, Component.literal("Rescue the pilot"),
                Component.literal("Bandage the pilot and escort them to safety."),
                TaskObjectives.sequence(TaskObjectives.deliver(List.of(ApiSupport.stack("apocalypsenow:bandage", 2))),
                        TaskObjectives.escort(6)), 24_000, TaskDefinition.DeathPolicy.PERSIST, true, false,
                ApiSupport.items(MilitaryBaseEncounter.ammo("tacz:556x45", 30), ApiSupport.stack("minecraft:iron_ingot", 4)), null, 0)));
        ApiSupport.log("pilot plea", NpcApi.registerDialogue(new DialogueDefinition(PLEA,
                Set.of(DialogueTrigger.INTERACT), 50, HeliCrash::plea)));
        ApiSupport.log("pilot thanks", NpcApi.registerDialogue(new DialogueDefinition(THANKS,
                Set.of(DialogueTrigger.TASK_COMPLETE), 50, HeliCrash::thanks)));
    }

    /**
     * True when the 11-square footprint is near enough to flat for the crash to level it: each column's ground may sit
     * one block lower or higher than the centre (a dip gets filled, a bump of natural ground is knocked off), plants
     * and snow are cleared, and nothing built, wet or solid stands above. Does not rely on the sky, so the buried
     * GameTest arena can use it.
     */
    static boolean flat(ServerLevel level, BlockPos center) {
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            BlockPos at = center.offset(x, 0, z);
            if (!level.isLoaded(at.above(4)) || !level.isLoaded(at.below(2))) return false;
            BlockState ground = level.getBlockState(at.below());
            boolean floor = ground.isFaceSturdy(level, at.below(), Direction.UP) && ground.getFluidState().isEmpty();
            boolean dip = !floor && EncounterScenery.clearable(ground, true) && ground.getFluidState().isEmpty()
                    && level.getBlockState(at.below(2)).isFaceSturdy(level, at.below(2), Direction.UP);
            if (!floor && !dip) return false;
            for (int y = 0; y <= 4; y++) {
                BlockState state = level.getBlockState(at.above(y));
                if (!state.getFluidState().isEmpty()) return false;
                if (EncounterScenery.clearable(state, y == 0)) continue;
                if (y == 0 && bump(state)) continue; // knocked off when the crash levels the site
                return false;
            }
        }
        return true;
    }

    /** Natural ground the crash may knock off one block of. */
    private static boolean bump(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(net.minecraft.tags.BlockTags.SAND) || state.is(Blocks.GRAVEL)
                || state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD);
    }

    /** Levels the footprint at the centre's height: dips filled with coarse dirt, bumps and plants cleared. */
    private static void level(ServerLevel level, BlockPos center) {
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
            BlockPos at = center.offset(x, 0, z);
            if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP))
                level.setBlock(at.below(), Blocks.COARSE_DIRT.defaultBlockState(), 3);
            for (int y = 0; y <= 4; y++)
                if (!level.getBlockState(at.above(y)).isAir()) level.setBlock(at.above(y), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** A crash site, or null with the count of each reason the candidates were refused. */
    record Site(BlockPos pos, Map<String, Integer> refused) {}

    /**
     * Surface search, farther out than ordinary encounters, with open sky across the crash site. The test command's
     * near mode ignores other encounters close by and the player's view.
     */
    static Site find(ServerLevel level, ServerPlayer player, EventData data, boolean near) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Map<String, Integer> refused = new TreeMap<>();
        for (int attempt = 0; attempt < 1024; attempt++) {
            double angle = random.nextDouble(Math.PI * 2), distance = random.nextDouble(near ? 14 : 60, near ? 34 : 120);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            String reason = null;
            BlockPos at = null;
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) reason = "unloaded";
            else {
                at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (Math.abs(at.getY() - player.blockPosition().getY()) > 8) reason = "height";
                else if (!near && data.near(at)) reason = "recent encounter";
                else if (!flat(level, at)) reason = "uneven or blocked";
                else {
                    for (int dx : new int[] {-5, 0, 5}) for (int dz : new int[] {-5, 0, 5})
                        if (!level.canSeeSky(at.offset(dx, 4, dz))) reason = "no open sky";
                    if (reason == null && !near
                            && player.getLookAngle().dot(Vec3.atCenterOf(at).subtract(player.getEyePosition()).normalize()) > 0.25)
                        reason = "in view";
                }
            }
            if (reason == null) return new Site(at, refused);
            refused.merge(reason, 1, Integer::sum);
        }
        return new Site(null, refused);
    }

    private static BlockPos at(BlockPos center, int turn, int x, int y, int z) {
        return switch (Math.floorMod(turn, 4)) {
            case 1 -> center.offset(-z, y, x);
            case 2 -> center.offset(-x, y, -z);
            case 3 -> center.offset(z, y, -x);
            default -> center.offset(x, y, z);
        };
    }

    private static void put(ServerLevel level, BlockPos center, int turn, int x, int y, int z, BlockState state) {
        level.setBlock(at(center, turn, x, y, z), state, 3);
    }

    /** Builds the wreck on tested ground; forcePilot is for the deterministic GameTest. */
    static CompoundTag place(ServerLevel level, BlockPos center, int turn, int tier, UUID id, boolean forcePilot) {
        if (!flat(level, center)) return null;
        level(level, center);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        BlockState olive = Blocks.GREEN_CONCRETE.defaultBlockState(), steel = Blocks.IRON_BLOCK.defaultBlockState();
        BlockState soot = ApiSupport.state("immersive_weathering:charred_planks", Blocks.BLACK_CONCRETE.defaultBlockState());
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
            if ((x * x + z * z < 26 && random.nextInt(4) == 0) || z == 0 && x < -2)
                put(level, center, turn, x, -1, z, random.nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
        // Belly, sidewalls, glass nose and roof leave a recognisable long cabin.
        for (int x = -2; x <= 2; x++) for (int z = -1; z <= 1; z++) {
            put(level, center, turn, x, 0, z, x == 2 ? steel : olive);
            put(level, center, turn, x, 2, z, x == 2 ? steel : olive);
            if (Math.abs(z) == 1) put(level, center, turn, x, 1, z,
                    x >= 1 ? Blocks.GLASS_PANE.defaultBlockState() : x == -2 ? steel : olive);
        }
        put(level, center, turn, 3, 0, 0, steel);
        put(level, center, turn, 3, 1, 0, Blocks.GLASS_PANE.defaultBlockState());
        for (int x = -5; x <= -3; x++) put(level, center, turn, x, 1, 0, steel);
        put(level, center, turn, -5, 2, 0, olive);
        put(level, center, turn, -5, 3, 0, Blocks.IRON_BARS.defaultBlockState());
        for (int x = -3; x <= 3; x++) put(level, center, turn, x, 3, 0, Blocks.IRON_BARS.defaultBlockState());
        for (int z = -3; z <= 3; z++) put(level, center, turn, 0, 3, z, Blocks.IRON_BARS.defaultBlockState());
        put(level, center, turn, 0, 3, 0, steel);
        // Broken blade lies beside the fuselage, with scattered metal well clear of the flames.
        for (int x = -2; x <= 2; x++) put(level, center, turn, x, 0, 4, Blocks.IRON_BARS.defaultBlockState());
        put(level, center, turn, 4, 0, -3, soot);
        put(level, center, turn, -4, 0, 3, Blocks.CHAIN.defaultBlockState());
        put(level, center, turn, 3, 0, 3, Blocks.IRON_TRAPDOOR.defaultBlockState());
        // Fire sits on hidden netherrack; every adjacent wreck block is stone or metal.
        BlockPos fire = at(center, turn, 2, 3, -1);
        put(level, center, turn, 2, 2, -1, Blocks.NETHERRACK.defaultBlockState());
        level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 3);
        BlockPos[] smoke = {at(center, turn, -1, 3, -1), at(center, turn, 1, 3, 1)};
        for (BlockPos camp : smoke) {
            level.setBlock(camp.below(), Blocks.HAY_BLOCK.defaultBlockState(), 3);
            level.setBlock(camp, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true), 3);
        }
        if (!crate(level, at(center, turn, 1, 0, -3), "weaponairdrop" + tier)
                || !crate(level, at(center, turn, -2, 0, 3), "largeairdrop" + tier)) return null;
        if (random.nextInt(4) == 0) crate(level, at(center, turn, 4, 0, 3), "medicalairdrop" + tier);
        int[][] spots = {{-4, -3}, {4, -4}, {-4, 4}, {4, 4}};
        int count = 3 + random.nextInt(2);
        for (int i = 0; i < count; i++) {
            if (!(LazarusEvents.spawn(level, at(center, turn, spots[i][0], 0, spots[i][1]), "minecraft:zombie", id, false) instanceof Mob zombie)) continue;
            zombie.addTag("lazarusevents:heli_horde");
            MilitaryCollapse.equip(zombie, EquipmentSlot.HEAD, "apocalypsenow:usa_armor_helmet");
            MilitaryCollapse.equip(zombie, EquipmentSlot.CHEST, "apocalypsenow:military_bulletprooft_vest_chestplate");
            MilitaryCollapse.equip(zombie, EquipmentSlot.LEGS, "apocalypsenow:usa_armor_leggings");
        }
        CompoundTag memory = new CompoundTag();
        memory.putLong(FIRE, fire.asLong());
        memory.putLongArray(SMOKE, new long[] {smoke[0].asLong(), smoke[1].asLong()});
        memory.putLong(NEXT_HORDE, level.getGameTime() + 2_400);
        if (forcePilot || random.nextFloat() < 0.4f) pilot(level, at(center, turn, 4, 0, 2), id, memory);
        return memory;
    }

    private static boolean crate(ServerLevel level, BlockPos at, String loot) {
        ResourceLocation table = new ResourceLocation("dyairdrop", "chests/" + loot);
        if (level.getServer().getLootData().getLootTable(table) == LootTable.EMPTY) return false;
        BlockState crate = ApiSupport.state("apocalypsenow:military_crate", Blocks.BARREL.defaultBlockState());
        level.setBlock(at, crate, 3);
        RandomizableContainerBlockEntity container;
        if (level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity found) container = found;
        else {
            level.setBlock(at, Blocks.BARREL.defaultBlockState(), 3);
            if (!(level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity barrel)) return false;
            container = barrel;
        }
        container.setLootTable(table, ThreadLocalRandom.current().nextLong());
        return true;
    }

    private static void pilot(ServerLevel level, BlockPos spot, UUID id, CompoundTag memory) {
        Entity entity = LazarusEvents.spawn(level, spot, "apocalypsenow:military_0" + (1 + ThreadLocalRandom.current().nextInt(2)), id, true);
        if (!(entity instanceof Mob mob)) return;
        mob.setHealth(mob.getMaxHealth() * 0.4f);
        mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 168_000, 2, false, false));
        ApiResult order = NpcApi.assign(mob, new ScenarioAssignment(OWNER, id, ScenarioOrder.WAIT,
                GlobalPos.of(level.dimension(), spot), null, 2, 168_000, 5));
        if (order.ok()) memory.putUUID("Pilot", mob.getUUID());
        else ApiSupport.LOGGER.warn("Helicopter pilot could not wait: {}", order.message());
    }

    /** Called every 100 ticks: fire ends at one day, signal smoke at two, and the horde stays capped. */
    static void tick(ServerLevel level, EventData data) {
        long now = level.getGameTime();
        for (EventData.Encounter encounter : List.copyOf(data.encounters)) {
            if (!TYPE.equals(encounter.type) || !level.isLoaded(encounter.pos)) continue;
            long age = now - encounter.created;
            if (age >= FIRE_TICKS && encounter.data.contains(FIRE)) {
                BlockPos fire = BlockPos.of(encounter.data.getLong(FIRE));
                if (level.getBlockState(fire).is(Blocks.FIRE)) level.setBlock(fire, Blocks.AIR.defaultBlockState(), 3);
                encounter.data.remove(FIRE);
                data.setDirty();
            }
            if (age >= SMOKE_TICKS && encounter.data.contains(SMOKE)) {
                for (long packed : encounter.data.getLongArray(SMOKE)) {
                    BlockPos smoke = BlockPos.of(packed);
                    if (level.getBlockState(smoke).is(Blocks.CAMPFIRE)) level.setBlock(smoke,
                            Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false), 3);
                }
                encounter.data.remove(SMOKE);
                data.setDirty();
            }
            if (age >= SMOKE_TICKS || now < encounter.data.getLong(NEXT_HORDE)) continue;
            List<ServerPlayer> near = level.players().stream().filter(p -> !p.isSpectator()
                    && p.distanceToSqr(Vec3.atCenterOf(encounter.pos)) <= 64 * 64).toList();
            if (near.isEmpty()) continue;
            encounter.data.putLong(NEXT_HORDE, now + 2_400);
            data.setDirty();
            int alive = level.getEntitiesOfClass(Mob.class, new AABB(encounter.pos).inflate(64),
                    mob -> mob.isAlive() && mob.getTags().contains(LazarusEvents.ID + ":" + encounter.id)
                            && mob.getTags().contains("lazarusevents:heli_horde")).size();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0, count = Math.min(1 + random.nextInt(3), HORDE_CAP - alive); i < count; i++) {
                BlockPos at = InfestedZone.streetSpot(level, near.get(random.nextInt(near.size())), encounter.pos);
                if (at == null) continue;
                Entity zombie = LazarusEvents.spawn(level, at, "minecraft:zombie", encounter.id, false);
                if (zombie != null) zombie.addTag("lazarusevents:heli_horde");
            }
        }
    }

    private static boolean isPilot(LivingEntity npc, UUID id) {
        return id != null && NpcApi.assignment(npc)
                .filter(a -> a.owner().equals(OWNER) && a.encounterId().equals(id)).isPresent();
    }

    private static Optional<DialoguePage> plea(DialogueContext c) {
        if (!isPilot(c.npc(), c.encounterId()) || NpcApi.assignment(c.npc()).get().order() != ScenarioOrder.WAIT) return Optional.empty();
        if (NpcApi.encounterTask(c.player()).filter(t -> t.state().open() && t.giverId().equals(c.npc().getUUID())).isPresent())
            return Optional.empty();
        return Optional.of(new DialoguePage(Component.literal("Mayday... my leg is bad. Patch me up and get me clear of the wreck."),
                List.of(new DialogueOption("help", Component.literal("I'll get you out"), ctx -> offer(ctx, c.encounterId())),
                        new DialogueOption("leave", Component.literal("Not now"), ctx -> {}))));
    }

    private static void offer(DialogueContext c, UUID id) {
        ServerLevel level = c.player().serverLevel();
        Vec3 away = c.player().position().subtract(c.npc().position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1) away = new Vec3(1, 0, 0);
        Vec3 target = c.npc().position().add(away.normalize().scale(40));
        int x = (int) Math.floor(target.x), z = (int) Math.floor(target.z);
        BlockPos safe = level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null ? c.player().blockPosition()
                : new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        ApiResult result = NpcApi.offerTask(c.player(), c.npc(), TASK, id, GlobalPos.of(level.dimension(), safe));
        if (!result.ok()) c.player().displayClientMessage(Component.literal("The pilot cannot go with you yet: " + result.message()), true);
    }

    private static Optional<DialoguePage> thanks(DialogueContext c) {
        return isPilot(c.npc(), c.encounterId()) ? Optional.of(new DialoguePage(
                Component.literal("You got me out. Command will hear about this."),
                List.of(new DialogueOption("bye", Component.literal("Stay safe"), ctx -> {})))) : Optional.empty();
    }

    static final class Listener {
        @SubscribeEvent
        public void heliPilotProgress(NpcApiEvents.TaskProgress event) {
            if (!TASK.equals(event.getTaskId()) || !"stage".equals(event.getKey()) || event.getValue() != 1) return;
            ServerPlayer player = event.getPlayer();
            Optional<TaskView> task = NpcApi.encounterTask(player);
            if (task.isEmpty() || !(player.serverLevel().getEntity(task.get().giverId()) instanceof Mob pilot) || !pilot.isAlive()) return;
            pilot.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            pilot.setHealth(Math.max(pilot.getHealth(), pilot.getMaxHealth() * 0.7f));
            ApiSupport.log("pilot follow", NpcApi.assign(pilot, new ScenarioAssignment(OWNER, event.getEncounterId(),
                    ScenarioOrder.FOLLOW_PLAYER, GlobalPos.of(pilot.level().dimension(), pilot.blockPosition()),
                    player.getUUID(), 4, 24_000, 5)));
        }

        @SubscribeEvent
        public void heliPilotState(NpcApiEvents.TaskStateChanged event) {
            if (!TASK.equals(event.getTaskId())) return;
            ServerPlayer player = event.getPlayer();
            Optional<TaskView> task = NpcApi.encounterTask(player);
            Mob pilot = task.map(t -> player.serverLevel().getEntity(t.giverId()))
                    .filter(e -> e instanceof Mob mob && mob.isAlive()).map(e -> (Mob) e).orElse(null);
            if (event.getTo() == TaskState.COMPLETED && pilot != null) {
                NpcApi.assign(pilot, new ScenarioAssignment(OWNER, event.getEncounterId(), ScenarioOrder.WAIT,
                        GlobalPos.of(pilot.level().dimension(), pilot.blockPosition()), null, 3, 12_000, 5));
                NpcApi.adjustReputation(player, "military", 3, REWARD, event.getEncounterId());
                NpcApi.openDialogue(player, pilot, DialogueTrigger.TASK_COMPLETE);
            } else if ((event.getTo() == TaskState.ABANDONED || event.getTo() == TaskState.EXPIRED)
                    && pilot != null && event.getFrom() != TaskState.OFFERED)
                NpcApi.assign(pilot, new ScenarioAssignment(OWNER, event.getEncounterId(), ScenarioOrder.WAIT,
                        GlobalPos.of(pilot.level().dimension(), pilot.blockPosition()), null, 2, 168_000, 5));
        }
    }
}
