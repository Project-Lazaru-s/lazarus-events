package com.lazarus.events;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

@Mod(LazarusEvents.ID)
public final class LazarusEvents {
    static final String ID = "lazarusevents";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] TYPES = {"cache", "gunfire", "flare", "military", RescueEncounter.TYPE,
            LastDefenseEncounter.TYPE, FalseRadioEncounter.TYPE};
    private static final ResourceLocation RADIO_ID = new ResourceLocation("ifreq", "walkie_talkie");
    private static final ForgeConfigSpec CONFIG;
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.IntValue MIN_DAYS;
    private static final ForgeConfigSpec.IntValue MAX_DAYS;
    private static final ForgeConfigSpec.IntValue MILITARY_DAY;
    private static final ForgeConfigSpec.IntValue MAX_ACTIVE;
    private static final ForgeConfigSpec.BooleanValue CACHE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue GUNFIRE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue FLARE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue MILITARY_ENABLED;
    private static final ForgeConfigSpec.BooleanValue RESCUE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue LAST_DEFENSE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue FALSE_RADIO_ENABLED;
    private static final ForgeConfigSpec.BooleanValue FACTION_EXPEDITIONS_ENABLED;
    private static final ForgeConfigSpec.IntValue FACTION_EXPEDITION_DAYS;
    private static final ForgeConfigSpec.IntValue BASE_FALLBACK_DAY;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ENABLED = builder.define("enabled", true);
        MIN_DAYS = builder.comment("Minimum days between successful encounters.").defineInRange("minimumDays", 1, 1, 20);
        MAX_DAYS = builder.comment("Maximum days between successful encounters; values below minimumDays use minimumDays.")
                .defineInRange("maximumDays", 2, 1, 30);
        MILITARY_DAY = builder.comment("First world day with military encounters.").defineInRange("militaryFromDay", 10, 1, 100);
        MAX_ACTIVE = builder.defineInRange("maximumActiveEncounters", 4, 1, 16);
        CACHE_ENABLED = builder.define("enableCache", true);
        GUNFIRE_ENABLED = builder.define("enableGunfire", true);
        FLARE_ENABLED = builder.define("enableFlare", true);
        MILITARY_ENABLED = builder.define("enableMilitary", true);
        RESCUE_ENABLED = builder.comment("Wounded survivor who asks for bandages and an escort (needs Lazarus NPCs).")
                .define("enableRescue", true);
        LAST_DEFENSE_ENABLED = builder.comment("Survivors holding a barricade against waves (needs Lazarus NPCs).")
                .define("enableLastDefense", true);
        FALSE_RADIO_ENABLED = builder.comment("A fake distress call that is a looter ambush; from day 6, like looters (needs Lazarus NPCs).")
                .define("enableFalseRadio", true);
        FACTION_EXPEDITIONS_ENABLED = builder.comment("Rare Rovers or Wardens patrols outside faction bases.")
                .define("enableFactionExpeditions", true);
        FACTION_EXPEDITION_DAYS = builder.comment("Minimum days between faction patrol attempts.")
                .defineInRange("factionExpeditionDays", 5, 2, 30);
        BASE_FALLBACK_DAY = builder.comment("From this world day, a faction without a base may also build it in already explored,",
                        "loaded chunks 72-128 blocks from a player and out of their sight. 0 keeps bases in new chunks only.")
                .defineInRange("factionBaseFallbackDay", 3, 0, 100);
        CONFIG = builder.build();
    }

    public LazarusEvents() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new RescueEncounter.Listener());
        MinecraftForge.EVENT_BUS.register(new LastDefenseEncounter.Listener());
        MinecraftForge.EVENT_BUS.register(new FalseRadioEncounter.Listener());
        MinecraftForge.EVENT_BUS.register(new MilitaryBaseEncounter.Listener());
        MinecraftForge.EVENT_BUS.register(new FactionBases());
        MinecraftForge.EVENT_BUS.register(new FactionMissions());
        MinecraftForge.EVENT_BUS.register(new FactionSkirmish());
        MinecraftForge.EVENT_BUS.register(new FactionExpeditions());
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(
                (net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
                    RescueEncounter.register();
                    LastDefenseEncounter.register();
                    FalseRadioEncounter.register();
                    MilitaryBaseEncounter.register();
                    FactionMissions.register();
                    FactionExpeditions.register();
                }));
    }

    static boolean expeditionsEnabled() { return FACTION_EXPEDITIONS_ENABLED.get(); }

    static int baseFallbackDay() { return BASE_FALLBACK_DAY.get(); }

    static long expeditionCooldown() { return FACTION_EXPEDITION_DAYS.get() * 24_000L; }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ENABLED.get()) return;
        ServerLevel level = event.getServer().overworld();
        long now = level.getGameTime();
        if (now % 100 != 0) return;
        EventData data = EventData.get(level);
        data.prune(now);
        for (EventData.Encounter encounter : data.encounters) {
            if (encounter.discovered) continue;
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator() || player.distanceToSqr(Vec3.atCenterOf(encounter.pos)) > 24 * 24) continue;
                encounter.discovered = true;
                data.setDirty();
                player.sendSystemMessage(Component.literal("You found signs of a recent " + label(encounter.type) + "."));
                break;
            }
        }
        if (data.nextAttempt == 0) {
            data.nextAttempt = now + 24000;
            data.setDirty();
            return;
        }
        if (now < data.nextAttempt || data.encounters.size() >= MAX_ACTIVE.get()) return;
        List<ServerPlayer> eligible = level.players().stream()
                .filter(player -> !player.isSpectator() && !player.isCreative()).toList();
        if (eligible.isEmpty()) return;
        ServerPlayer player = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
        String type = randomType(level.getDayTime());
        if (type == null) {
            data.result("No encounter types enabled for this world day");
            data.nextAttempt = now + 1200;
            data.setDirty();
            return;
        }
        Outcome outcome = attempt(level, player, type, false, false, data);
        data.result(type + ": " + outcome.message);
        if (outcome.success) {
            data.nextAttempt = now + nextDelay();
            data.setDirty();
            LOGGER.info("Lazarus Events: {}", outcome.message);
        } else {
            data.nextAttempt = now + 1200;
            data.setDirty();
            LOGGER.debug("Lazarus Events deferred {}: {}", type, outcome.message);
        }
    }

    private static long nextDelay() {
        int min = MIN_DAYS.get();
        int max = Math.max(min, MAX_DAYS.get());
        return max == min ? 24000L * min
                : 24000L * min + ThreadLocalRandom.current().nextLong(24000L * (max - min) + 1);
    }

    private static String randomType(long now) {
        List<String> choices = new ArrayList<>();
        if (CACHE_ENABLED.get()) for (int i = 0; i < 9; i++) choices.add("cache");
        if (GUNFIRE_ENABLED.get()) for (int i = 0; i < 6; i++) choices.add("gunfire");
        if (FLARE_ENABLED.get()) for (int i = 0; i < 4; i++) choices.add("flare");
        if (RESCUE_ENABLED.get()) for (int i = 0; i < 4; i++) choices.add(RescueEncounter.TYPE);
        if (LAST_DEFENSE_ENABLED.get() && now / 24000 >= 3) for (int i = 0; i < 3; i++) choices.add(LastDefenseEncounter.TYPE);
        if (FALSE_RADIO_ENABLED.get() && now / 24000 >= 6) for (int i = 0; i < 3; i++) choices.add(FalseRadioEncounter.TYPE);
        if (MILITARY_ENABLED.get() && now / 24000 >= MILITARY_DAY.get()) choices.add("military");
        return choices.isEmpty() ? null : choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lazarusevents")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(context -> {
                    ServerLevel level = context.getSource().getServer().overworld();
                    EventData data = EventData.get(level);
                    context.getSource().sendSuccess(() -> Component.literal("Next attempt: " + Math.max(0,
                            (data.nextAttempt - level.getGameTime()) / 20) + "s; active: "
                            + data.encounters.size() + "; last: " + data.lastResult), false);
                    return data.encounters.size();
                }))
                .then(Commands.literal("test")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(TYPES, builder))
                                .then(Commands.literal("near").executes(context -> test(context.getSource(),
                                        StringArgumentType.getString(context, "type"), true)))
                                .then(Commands.literal("natural").executes(context -> test(context.getSource(),
                                        StringArgumentType.getString(context, "type"), false)))))
                .then(Commands.literal("locate")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> locate(context.getSource(), StringArgumentType.getString(context, "id")))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> cancel(context.getSource(), StringArgumentType.getString(context, "id"))))));
    }

    private static int test(net.minecraft.commands.CommandSourceStack source, String type, boolean near) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!source.getLevel().dimension().equals(Level.OVERWORLD)) {
            source.sendFailure(Component.literal("Test events in the Overworld."));
            return 0;
        }
        if (List.of(TYPES).contains(type) == false) {
            source.sendFailure(Component.literal("Unknown type: " + type));
            return 0;
        }
        EventData data = EventData.get(source.getLevel());
        Outcome outcome = attempt(source.getLevel(), player, type, near, true, data);
        data.result("test " + type + ": " + outcome.message);
        if (outcome.success) source.sendSuccess(() -> Component.literal(outcome.message), false);
        else source.sendFailure(Component.literal(outcome.message));
        return outcome.success ? 1 : 0;
    }

    private static int locate(net.minecraft.commands.CommandSourceStack source, String id) {
        for (EventData.Encounter encounter : EventData.get(source.getServer().overworld()).encounters) {
            if (encounter.id.toString().startsWith(id)) {
                source.sendSuccess(() -> Component.literal(encounter.type + " " + encounter.id + " at " + encounter.pos.toShortString()), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("Encounter not found in this dimension."));
        return 0;
    }

    private static int cancel(net.minecraft.commands.CommandSourceStack source, String id) {
        ServerLevel level = source.getServer().overworld();
        EventData data = EventData.get(level);
        for (EventData.Encounter encounter : List.copyOf(data.encounters)) {
            if (encounter.id.toString().startsWith(id)) {
                data.remove(encounter.id);
                ApiSupport.failTasks(level, encounter.id, ApiSupport.CANCELLED);
                removeActors(level, encounter.id, encounter.pos);
                source.sendSuccess(() -> Component.literal("Cancelled " + encounter.id
                        + ". Event actors removed; loot and blocks remain in the world."), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("Encounter not found in this dimension."));
        return 0;
    }

    private static Outcome attempt(ServerLevel level, ServerPlayer player, String type, boolean near,
            boolean testing, EventData data) {
        Search search = findSpot(level, player, type, near, data);
        if (search.pos == null) return new Outcome(false, "No valid location (" + search.failures + ")");
        BlockPos pos = search.pos;
        UUID id = UUID.randomUUID();
        if (!place(level, pos, type, search.rotation, testing, id)) {
            removeActors(level, id, pos);
            return new Outcome(false, "Placement failed at " + pos.toShortString() + " for " + type);
        }
        EventData.Encounter encounter = data.add(id, type, pos, level.getGameTime());
        String message = "Placed " + type + " " + encounter.id + " at " + pos.toShortString();
        if (near) player.sendSystemMessage(Component.literal(message));
        else radioMessage(level, pos, type);
        return new Outcome(true, message);
    }

    private static Search findSpot(ServerLevel level, ServerPlayer player, String type, boolean near, EventData data) {
        Map<Reject, Integer> failures = new EnumMap<>(Reject.class);
        int attempts = 1024;
        for (int i = 0; i < attempts; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double radius = near ? ThreadLocalRandom.current().nextDouble(14, 34)
                    : ThreadLocalRandom.current().nextDouble(40, 78);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                failures.merge(Reject.UNLOADED, 1, Integer::sum);
                continue;
            }
            BlockPos pos = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (pos.getY() <= level.getMinBuildHeight() + 1
                    || pos.getY() >= level.getMaxBuildHeight() - EncounterScenery.HEIGHT - 1
                    || Math.abs(pos.getY() - player.blockPosition().getY()) > 8) {
                failures.merge(Reject.HEIGHT, 1, Integer::sum);
                continue;
            }
            if (data.near(pos)) {
                failures.merge(Reject.RECENT, 1, Integer::sum);
                continue;
            }
            int rotation = ThreadLocalRandom.current().nextInt(4);
            BlockPos actors = EncounterScenery.offset(pos, rotation, EncounterScenery.RADIUS + 1, 1);
            BlockPos signal = EncounterScenery.offset(pos, rotation, 2, 1);
            String scenery = scenery(type);
            if (!safe(level, pos) || !type.equals("military") && !safe(level, actors)
                    || scenery.equals("flare") && !safe(level, signal)
                    || !EncounterScenery.canPlace(level, pos, scenery, rotation)) {
                failures.merge(Reject.GROUND, 1, Integer::sum);
                continue;
            }
            if (!near) {
                Vec3 toward = Vec3.atCenterOf(pos).subtract(player.getEyePosition()).normalize();
                if (player.getLookAngle().dot(toward) > 0.25) {
                    failures.merge(Reject.VISIBLE, 1, Integer::sum);
                    continue;
                }
            }
            return new Search(pos, rotation, failures);
        }
        return new Search(null, 0, failures);
    }

    static boolean safe(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isLoaded(pos.above()) || !level.isLoaded(pos.below(2))) return false;
        var space = level.getBlockState(pos);
        var above = level.getBlockState(pos.above());
        var ground = level.getBlockState(pos.below());
        if (!EncounterScenery.clearable(space, true) || !EncounterScenery.clearable(above, false)
                || !space.getFluidState().isEmpty() || !ground.getFluidState().isEmpty()
                || ground.is(Blocks.ICE) || ground.is(Blocks.PACKED_ICE)
                || ground.is(Blocks.BLUE_ICE) || ground.is(Blocks.FROSTED_ICE)) return false;
        return EncounterScenery.supported(level, pos.below()) && level.canSeeSky(pos);
    }

    private static boolean place(ServerLevel level, BlockPos pos, String type, int rotation, boolean testing, UUID id) {
        int day = (int) (level.getDayTime() / 24000);
        int tier = Math.min(5, 1 + day / 10);
        BlockPos actors = EncounterScenery.offset(pos, rotation, EncounterScenery.RADIUS + 1, 1);
        if (type.equals(RescueEncounter.TYPE)) return RescueEncounter.place(level, pos, rotation, id);
        if (type.equals(LastDefenseEncounter.TYPE)) return LastDefenseEncounter.place(level, pos, rotation, id);
        if (type.equals("gunfire")) {
            if (!spawn(level, pos, "apocalypsenow:soldiers", id)) return false;
            int spawned = 0;
            for (int i = 0; i < 4; i++) if (spawn(level, actors, "apocalypsenow:walker_" + (i + 1), id)) spawned++;
            if (spawned == 0) return false;
            if (!EncounterScenery.build(level, pos, type, rotation)) return false;
            level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.HOSTILE, 6.0f, 0.55f);
            return true;
        }
        if (type.equals("flare")) {
            int spawned = 0;
            for (int i = 0; i < 3; i++) if (spawn(level, actors, "apocalypsenow:walker_" + (i + 1), id)) spawned++;
            if (spawned == 0 || !EncounterScenery.build(level, pos, type, rotation)) return false;
            BlockPos signal = EncounterScenery.offset(pos, rotation, 2, 1);
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            CompoundTag fireworks = rocket.getOrCreateTagElement("Fireworks");
            CompoundTag burst = new CompoundTag();
            burst.putByte("Type", (byte) 0);
            burst.putIntArray("Colors", new int[] {0xE23131});
            ListTag explosions = new ListTag();
            explosions.add(burst);
            fireworks.put("Explosions", explosions);
            fireworks.putByte("Flight", (byte) 1);
            level.addFreshEntity(new FireworkRocketEntity(level, signal.getX() + 0.5,
                    signal.getY() + 2, signal.getZ() + 0.5, rocket));
            level.sendParticles(ParticleTypes.FLASH, pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5,
                    5, 0.5, 0.4, 0.5, 0.0);
            level.playSound(null, pos, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 6.0f, 1.0f);
            return true;
        }
        if (type.equals("military") && !testing && day < MILITARY_DAY.get()) return false;
        ResourceLocation table = new ResourceLocation("dyairdrop", "chests/"
                + (type.equals("military") ? "weaponairdrop" : "smallairdrop") + tier);
        if (level.getServer().getLootData().getLootTable(table) == LootTable.EMPTY) return false;
        if (!level.setBlock(pos, Blocks.BARREL.defaultBlockState(), 3)) return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof RandomizableContainerBlockEntity chest)) {
            level.removeBlock(pos, false);
            return false;
        }
        chest.setLootTable(table, ThreadLocalRandom.current().nextLong());
        if (type.equals(FalseRadioEncounter.TYPE)) {
            if (FalseRadioEncounter.place(level, pos, rotation, id)) return true;
            level.removeBlock(pos, false);
            return false;
        }
        if (type.equals("military")) {
            if (!EncounterScenery.build(level, pos, type, rotation)) {
                level.removeBlock(pos, false);
                return false;
            }
            stockMilitaryCheckpoint(level, pos, rotation, tier);
            List<Mob> staff = new ArrayList<>();
            spawnMilitary(level, pos, rotation, 5, 9, "apocalypsenow:military_01", id, staff);
            spawnMilitary(level, pos, rotation, 10, 9, "apocalypsenow:military_02", id, staff);
            spawnMilitary(level, pos, rotation, 15, 4, "apocalypsenow:military_01", id, staff);
            spawnMilitary(level, pos, rotation, 17, 4, "apocalypsenow:military_medic", id, staff);
            if (staff.isEmpty()) LOGGER.warn("Military checkpoint at {} has no stationed soldiers", pos);
            else MilitaryBaseEncounter.station(level, MilitaryBaseEncounter.center(pos, rotation), id, staff);
            return true;
        }
        int count = 2;
        int spawned = 0;
        for (int i = 0; i < count; i++) if (spawn(level, actors, "apocalypsenow:walker_" + (i + 1), id)) spawned++;
        if (spawned == 0) {
            level.removeBlock(pos, false);
            return false;
        }
        if (!EncounterScenery.build(level, pos, type, rotation)) {
            level.removeBlock(pos, false);
            return false;
        }
        return true;
    }

    private static void stockMilitaryCheckpoint(ServerLevel level, BlockPos origin, int rotation, int tier) {
        BlockPos supplies = EncounterScenery.militaryPosition(origin, rotation, 8, 3, 2);
        BlockEntity secondBarrel = level.getBlockEntity(supplies);
        ResourceLocation suppliesTable = new ResourceLocation("dyairdrop", "chests/smallairdrop" + tier);
        if (secondBarrel instanceof RandomizableContainerBlockEntity barrel
                && level.getServer().getLootData().getLootTable(suppliesTable) != LootTable.EMPTY) {
            barrel.setLootTable(suppliesTable, ThreadLocalRandom.current().nextLong());
        } else LOGGER.warn("Military supply barrel unavailable at {}", supplies);

        BlockPos fridgePos = EncounterScenery.militaryPosition(origin, rotation, 11, 2, 1);
        BlockEntity fridge = level.getBlockEntity(fridgePos);
        if (fridge instanceof Container inventory && inventory.getContainerSize() >= 4) {
            inventory.setItem(0, new ItemStack(Items.BREAD, 4));
            inventory.setItem(1, new ItemStack(Items.COOKED_BEEF, 3));
            inventory.setItem(2, new ItemStack(Items.CARROT, 6));
            inventory.setItem(3, new ItemStack(Items.APPLE, 3));
            fridge.setChanged();
        } else LOGGER.warn("Military fridge inventory unavailable at {}", fridgePos);

        BlockPos generatorPos = EncounterScenery.militaryPosition(origin, rotation, 14, 1, 1);
        BlockEntity generator = level.getBlockEntity(generatorPos);
        if (generator instanceof Container fuel && fuel.getContainerSize() > 0) {
            fuel.setItem(0, new ItemStack(Items.COAL, 4));
            CompoundTag state = generator.saveWithoutMetadata();
            state.putBoolean("Enabled", true);
            state.putInt("Energy", 1600);
            state.putInt("TotalEnergy", 1600);
            generator.load(state);
            generator.setChanged();
            level.sendBlockUpdated(generatorPos, level.getBlockState(generatorPos),
                    level.getBlockState(generatorPos), 3);
        } else LOGGER.warn("Military generator inventory unavailable at {}", generatorPos);
    }

    private static void spawnMilitary(ServerLevel level, BlockPos origin, int rotation,
            int x, int z, String entityId, UUID encounterId, List<Mob> staff) {
        BlockPos at = EncounterScenery.militaryPosition(origin, rotation, x, 1, z);
        if (spawn(level, at, entityId, encounterId, true) instanceof Mob mob) staff.add(mob);
    }

    private static boolean spawn(ServerLevel level, BlockPos pos, String id, UUID encounterId) {
        return spawn(level, pos, id, encounterId, false) != null;
    }

    /** Creates a tagged encounter actor; null when it could not be placed. */
    static Entity spawn(ServerLevel level, BlockPos pos, String id, UUID encounterId, boolean persistent) {
        if (!level.isLoaded(pos)) return null;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        if (type == null) return null;
        Entity entity = type.create(level);
        if (entity == null) return null;
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                ThreadLocalRandom.current().nextFloat(360), 0);
        if (entity instanceof Mob mob) {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            if (persistent) mob.setPersistenceRequired();
        }
        entity.addTag(ID + ":" + encounterId);
        return level.addFreshEntity(entity) ? entity : null;
    }

    private static void removeActors(ServerLevel level, UUID id, BlockPos pos) {
        String tag = ID + ":" + id;
        for (Entity entity : level.getEntities((Entity) null,
                AABB.ofSize(Vec3.atCenterOf(pos), 48, 24, 48), candidate -> candidate.getTags().contains(tag))) {
            entity.discard();
        }
    }

    private static void radioMessage(ServerLevel level, BlockPos pos, String type) {
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.distanceToSqr(Vec3.atCenterOf(pos)) > 192 * 192) continue;
            boolean radio = false;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (RADIO_ID.equals(ForgeRegistries.ITEMS.getKey(player.getInventory().getItem(slot).getItem()))) {
                    radio = true;
                    break;
                }
            }
            if (radio) player.sendSystemMessage(Component.literal("[Radio] " + radioLine(type) + " near X "
                    + Math.round(pos.getX() / 25f) * 25 + ", Z " + Math.round(pos.getZ() / 25f) * 25 + "."));
        }
    }

    /** Template used by each type; the API scenarios reuse the captured ones. */
    static String scenery(String type) {
        return switch (type) {
            case RescueEncounter.TYPE -> "flare";
            case LastDefenseEncounter.TYPE -> "gunfire";
            case FalseRadioEncounter.TYPE -> "cache";
            default -> type;
        };
    }

    /** What the discovery message calls the encounter; the false radio must not give itself away. */
    static String label(String type) {
        return switch (type) {
            case RescueEncounter.TYPE, FalseRadioEncounter.TYPE -> "distress call";
            case LastDefenseEncounter.TYPE -> "last stand";
            default -> type + " encounter";
        };
    }

    private static String radioLine(String type) {
        return switch (type) {
            case RescueEncounter.TYPE, FalseRadioEncounter.TYPE -> "Someone is begging for help";
            case LastDefenseEncounter.TYPE -> "Defenders under attack, asking for anyone with a gun,";
            default -> type + " signal";
        };
    }

    private enum Reject { UNLOADED, HEIGHT, RECENT, GROUND, VISIBLE }
    private record Search(BlockPos pos, int rotation, Map<Reject, Integer> failures) {}
    private record Outcome(boolean success, String message) {}
}
