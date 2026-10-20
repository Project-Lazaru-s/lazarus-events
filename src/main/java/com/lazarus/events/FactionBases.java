package com.lazarus.events;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.ScenarioAssignment;
import com.lazarus.npcs.api.ScenarioOrder;
import com.lazarus.npcs.api.ai.Activity;
import com.lazarus.npcs.api.ai.Routine;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.phys.Vec3;

/** New chunks may host one permanent base per faction. Existing chunks are never modified automatically. */
final class FactionBases {
    static final ResourceLocation OWNER = ApiSupport.id("faction_base");
    private static final long STAFF_RESPAWN = 24_000L;
    private static final long ORDER_RENEWAL = 120_000L;
    private static final UUID VACANT = new UUID(0, 0);

    @SubscribeEvent
    public void chunkLoaded(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD) return;
        int cx = event.getChunk().getPos().x, cz = event.getChunk().getPos().z;
        String faction = candidateFaction(level.getSeed(), cx, cz);
        if (faction == null) return;
        long chunk = event.getChunk().getPos().toLong();
        level.getServer().execute(() -> {
            FactionBaseData data = FactionBaseData.get(level);
            if (data.faction(faction) == null && data.pending.size() < 128 && data.pending.add(chunk)) data.setDirty();
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ServerLevel level = event.getServer().overworld();
        long now = level.getGameTime();
        if (now % 200 != 0) return;
        FactionBaseData data = FactionBaseData.get(level);
        for (long packed : List.copyOf(data.pending)) {
            int cx = (int) packed;
            int cz = (int) (packed >>> 32);
            if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
            String faction = candidateFaction(level.getSeed(), cx, cz);
            if (faction != null && data.faction(faction) == null) {
                BlockPos center = new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8);
                String problem = globalProblem(level, data, center, faction);
                Search search = problem == null ? search(level, center, 12, 6, faction) : null;
                if (search != null && search.best() != null) create(level, data, search.best(), faction, "new_chunk");
                else if (search != null && search.onlyUnloaded() && data.retry(packed)) {
                    // A large footprint spans neighbouring chunks that the world has not generated yet.
                    data.setDirty();
                    break;
                } else ApiSupport.LOGGER.debug("Base candidate {} {} {} rejected: {}", faction, cx, cz,
                        problem != null ? problem : search.summary());
            }
            data.pending.remove(packed);
            data.setDirty();
            break; // one full template validation per cycle
        }
        if (now % 2400 == 1200) fallback(level, data);
        for (FactionBaseData.Base base : data.bases) {
            if (!level.isLoaded(base.pos)) continue;
            if (now - base.lastStaffCheck >= STAFF_RESPAWN) staff(level, data, base);
            else if (now % 1200 == 0) renewOrders(level, base);
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator() || player.distanceToSqr(Vec3.atCenterOf(base.pos)) > 28 * 28) continue;
                if (!base.discovered) { base.discovered = true; data.setDirty(); }
                String seenKey = seenKey(base);
                if (player.getPersistentData().getBoolean(seenKey)) continue;
                player.getPersistentData().putBoolean(seenKey, true);
                int rep = NpcApi.reputation(player, base.faction);
                String tone = rep <= -20 ? "Keep your distance." : rep >= 20 ? "Welcome back." : "State your business.";
                player.sendSystemMessage(Component.literal("[" + base.faction + " outpost] " + tone));
            }
        }
    }

    /** One attempt per missing faction: a random spot 72-128 blocks from a random player, behind them. */
    private static void fallback(ServerLevel level, FactionBaseData data) {
        int day = LazarusEvents.baseFallbackDay();
        if (day <= 0 || level.getDayTime() / 24_000L + 1 < day) return;
        List<ServerPlayer> players = level.players().stream().filter(p -> !p.isSpectator() && p.isAlive()).toList();
        if (players.isEmpty()) return;
        for (String faction : BaseRoster.factions()) {
            if (data.faction(faction) != null) continue;
            ServerPlayer player = players.get(level.random.nextInt(players.size()));
            Vec3 look = player.getLookAngle().multiply(1, 0, 1).normalize();
            double angle = Math.atan2(-look.z, -look.x) + (level.random.nextDouble() - 0.5) * Math.PI;
            double distance = 72 + level.random.nextInt(57);
            BlockPos center = BlockPos.containing(player.getX() + Math.cos(angle) * distance, 64,
                    player.getZ() + Math.sin(angle) * distance);
            if (globalProblem(level, data, center, faction) != null || players.stream().anyMatch(p ->
                    p.distanceToSqr(Vec3.atCenterOf(center).with(net.minecraft.core.Direction.Axis.Y, p.getY())) < 56 * 56))
                continue;
            Search search = search(level, center, 12, 6, faction);
            if (search.best() != null) create(level, data, search.best(), faction, "explored_fallback");
            else ApiSupport.LOGGER.debug("Fallback base {} near {} rejected: {}", faction, center, search.summary());
        }
    }

    static String seenKey(FactionBaseData.Base base) { return "LazarusFactionBaseSeen_" + base.id; }

    private static String candidateFaction(long seed, int x, int z) {
        long h = seed ^ (x * 341873128712L) ^ (z * 132897987541L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return Math.floorMod(h, 8) == 0 ? BaseRoster.factions().get(Math.floorMod((int) (h >>> 8), BaseRoster.factions().size())) : null;
    }

    /** A surveyed anchor: {@code anchor} stands one block above the levelled floor; problem is "reason: detail". */
    record Site(BlockPos anchor, int floor, int cost, String problem) {
        static Site fail(BlockPos hint, String problem) { return new Site(hint, hint.getY() - 1, Integer.MAX_VALUE, problem); }
    }

    /** Best site of an anchor grid plus a tally of why the other anchors failed. */
    record Search(Site best, java.util.Map<String, Integer> reasons, int tried, Site closest) {
        boolean onlyUnloaded() { return best == null && (tried == 0 || reasons.keySet().equals(Set.of("chunk not loaded"))); }

        String summary() {
            StringBuilder text = new StringBuilder("no valid site among " + tried + " positions:");
            reasons.forEach((reason, count) -> text.append(' ').append(reason).append(" x").append(count).append(';'));
            return text + " here -> " + closest.problem();
        }
    }

    private static final int MAX_CUT = 3;
    private static final int MAX_FILL = 4;
    /** Average terraform budget, in blocks per hundred columns (a 23x21 camp may move up to 386 blocks). */
    private static final int MAX_TERRAFORM_PER_100 = 80;
    /** Natural tree blocks (trunks and mushroom caps) a site may fell, per hundred columns. */
    private static final int MAX_TREES_PER_100 = 30;
    private static final int TERRAFORM_FLAGS = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;

    private static String globalProblem(ServerLevel level, FactionBaseData data, BlockPos pos, String faction) {
        if (data.faction(faction) != null) return "faction already has a base";
        if (pos.distSqr(level.getSharedSpawnPos()) < 256 * 256) return "too close to world spawn";
        if (data.occupiedNear(pos, 256)) return "too close to another base";
        return null;
    }

    /** Tries a square grid of anchors around center and keeps the one needing the least terraforming. */
    static Search search(ServerLevel level, BlockPos center, int radius, int step, String faction) {
        return search(level, center, radius, step, faction, true);
    }

    /** heightmap=false surveys every anchor at center's height (GameTest arenas sit under foreign terrain). */
    static Search search(ServerLevel level, BlockPos center, int radius, int step, String faction, boolean heightmap) {
        java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
        Site best = null, closest = null;
        int tried = 0;
        for (int dx = -radius; dx <= radius; dx += step) for (int dz = -radius; dz <= radius; dz += step) {
            int x = center.getX() + dx, z = center.getZ() + dz;
            if (!level.isLoaded(new BlockPos(x, center.getY(), z))) continue;
            int y = heightmap ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) : center.getY();
            Site site = survey(level, new BlockPos(x, y, z), faction);
            tried++;
            if (dx == 0 && dz == 0) closest = site;
            if (site.problem() == null) { if (best == null || site.cost() < best.cost()) best = site; }
            else reasons.merge(site.problem().split(":", 2)[0], 1, Integer::sum);
        }
        if (closest == null) closest = Site.fail(center, "chunk not loaded: center");
        return new Search(best, reasons, tried, closest);
    }

    /** Kept for tests: the footprint rules alone, anchored at pos. */
    static String footprintProblem(ServerLevel level, BlockPos pos, String faction) {
        return survey(level, pos, faction).problem();
    }

    /**
     * Levels the footprint to the median surface: columns may be cut by {@link #MAX_CUT} natural blocks or
     * filled by {@link #MAX_FILL}; plants, snow, carpets, slabs and natural leaves are cleared. Logs, block
     * entities, fluids and built blocks still reject the site. hint is the first air block above the centre.
     */
    static Site survey(ServerLevel level, BlockPos hint, String faction) {
        BaseRoster.Layout layout = BaseRoster.layout(faction, BaseRoster.CURRENT_MODEL);
        int width = layout.width(), depth = layout.depth(), height = layout.height();
        int top = hint.getY() + 8, bottom = Math.max(hint.getY() - 12, level.getMinBuildHeight() + 1);
        if (hint.getY() - MAX_FILL - 2 < level.getMinBuildHeight() || top + height >= level.getMaxBuildHeight())
            return Site.fail(hint, "invalid height: " + hint.getY());
        if (level.getStructureManager().get(layout.template()).isEmpty())
            return Site.fail(hint, "missing structure template: " + layout.template());
        int x0 = hint.getX() - layout.anchorX(), z0 = hint.getZ() - layout.anchorZ();
        int[] surface = new int[width * depth];
        int trees = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            int s = Integer.MIN_VALUE;
            for (int y = top; y >= bottom; y--) {
                pos.set(x0 + x, y, z0 + z);
                if (!level.isLoaded(pos)) return Site.fail(hint, "chunk not loaded: " + pos.toShortString());
                var state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) return Site.fail(hint, "water or lava: " + pos.toShortString());
                if (tree(state)) { trees++; continue; }
                if (removable(level, pos, state)) continue;
                if (y == top || state.hasBlockEntity() || state.is(net.minecraft.tags.BlockTags.LOGS)) return Site.fail(hint, "obstacle: " + describe(state) + " at " + pos.toShortString());
                if (!state.isFaceSturdy(level, pos, Direction.UP) || state.is(net.minecraft.tags.BlockTags.ICE))
                    return Site.fail(hint, "unsupported ground: " + describe(state) + " at " + pos.toShortString());
                s = y;
                break;
            }
            if (s == Integer.MIN_VALUE) return Site.fail(hint, "no ground: " + (x0 + x) + ", " + (z0 + z));
            surface[x * depth + z] = s;
        }
        int[] sorted = surface.clone();
        java.util.Arrays.sort(sorted);
        int floor = sorted[sorted.length / 2];
        int cost = 0;
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            int s = surface[x * depth + z], d = s - floor;
            // Built blocks above the floor are obstacles even when they also make the column look steep.
            for (int y = floor + 1; y <= s; y++) {
                var state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                if (!naturalTerrain(state)) return Site.fail(hint, "obstacle: " + describe(state) + " at " + pos.toShortString());
            }
            if (d > MAX_CUT || d < -MAX_FILL) return Site.fail(hint, "too steep: " + (x0 + x) + ", " + s + ", "
                    + (z0 + z) + " is " + d + " from floor " + floor);
            // Whatever hangs above the scan window (tall trees, overhangs) must still clear the walls.
            for (int y = Math.max(s, top) + 1; y < floor + height; y++) {
                var state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                if (tree(state)) { trees++; continue; }
                if (!removable(level, pos, state)) return Site.fail(hint, "obstacle: " + describe(state) + " at " + pos.toShortString());
            }
            cost += Math.abs(d);
        }
        if (trees > width * depth * MAX_TREES_PER_100 / 100)
            return Site.fail(hint, "too many trees: " + trees + " trunk blocks");
        if (cost > width * depth * MAX_TERRAFORM_PER_100 / 100) return Site.fail(hint, "too much terraforming: " + cost + " blocks");
        return new Site(new BlockPos(hint.getX(), floor + 1, hint.getZ()), floor, cost, null);
    }

    private static boolean removable(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (state.isAir()) return true;
        if (!state.getFluidState().isEmpty() || state.hasBlockEntity() || state.getDestroySpeed(level, pos) < 0) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)
            return !state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
        return state.canBeReplaced() || state.getCollisionShape(level, pos).isEmpty()
                || state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                || state.is(net.minecraft.tags.BlockTags.WOOL_CARPETS) || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.SNOW) || state.is(Blocks.CACTUS) || state.is(Blocks.PUMPKIN)
                || state.is(Blocks.MELON) || state.is(Blocks.BAMBOO);
    }

    /** Natural trunks and huge mushrooms; stripped or placed planks are never trees. */
    private static boolean tree(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS) || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.RED_MUSHROOM_BLOCK);
    }

    private static boolean naturalTerrain(net.minecraft.world.level.block.state.BlockState state) {
        return !state.hasBlockEntity() && (state.is(net.minecraft.tags.BlockTags.DIRT)
                || state.is(net.minecraft.tags.BlockTags.SAND) || state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                || state.is(net.minecraft.tags.BlockTags.TERRACOTTA) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.DIRT_PATH));
    }

    private static String describe(net.minecraft.world.level.block.state.BlockState state) {
        return String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    /** Clears and levels a surveyed footprint; validation already proved every touched block is safe. */
    static void terraform(ServerLevel level, Site site, String faction) {
        BaseRoster.Layout layout = BaseRoster.layout(faction, BaseRoster.CURRENT_MODEL);
        int width = layout.width(), depth = layout.depth(), height = layout.height();
        int floor = site.floor();
        int x0 = site.anchor().getX() - layout.anchorX(), z0 = site.anchor().getZ() - layout.anchorZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            // Two extra layers catch the tops of tall flowers and grass without touching anything solid.
            for (int y = floor + height + 1; y > floor; y--) {
                var state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                if (!state.isAir() && (y < floor + height || removable(level, pos, state) || tree(state)))
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), TERRAFORM_FLAGS);
            }
            // Leftover trunks above the roofline; leaves cut off from their trunk decay on their own.
            for (int y = floor + height + 2; y < floor + height + 24; y++) {
                var state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                if (tree(state) || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                        && !state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT))
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), TERRAFORM_FLAGS);
                else if (!state.isAir()) break;
            }
            for (int y = floor; y >= floor - MAX_FILL; y--) {
                var state = level.getBlockState(pos.set(x0 + x, y, z0 + z));
                if (!removable(level, pos, state) && !tree(state)) break;
                level.setBlock(pos, Blocks.DIRT.defaultBlockState(), TERRAFORM_FLAGS);
            }
        }
    }

    private static FactionBaseData.Base create(ServerLevel level, FactionBaseData data, Site site, String faction,
                                                String origin) {
        BlockPos pos = site.anchor();
        BaseRoster.Layout layout = BaseRoster.layout(faction, BaseRoster.CURRENT_MODEL);
        BlockPos start = layout.start(pos);
        var template = level.getStructureManager().get(layout.template());
        if (template.isEmpty()) return null;
        terraform(level, site, faction);
        if (!template.get().placeInWorld(level, start, start,
                new StructurePlaceSettings().setIgnoreEntities(true), level.getRandom(), 3)) return null;
        FactionBaseData.Base base = data.add(faction, pos, 0, origin);
        stock(level, base);
        staff(level, data, base);
        ApiSupport.LOGGER.info("Faction base {} {} placed at {}", base.id, faction, pos);
        return base;
    }

    private static void stock(ServerLevel level, FactionBaseData.Base base) {
        BaseRoster.Layout layout = BaseRoster.layout(base);
        for (int[] spot : layout.barrels()) {
            BlockEntity block = level.getBlockEntity(layout.at(base.pos, spot));
            if (!(block instanceof Container container)) continue;
            block.getPersistentData().putBoolean("LazarusNpcNoScavenge", true);
            container.setItem(0, new ItemStack(Items.BREAD, BaseRoster.profile(base.faction).bread()));
            container.setItem(1, new ItemStack(Items.IRON_INGOT, 2));
            ItemStack bandage = ApiSupport.stack("apocalypsenow:bandage", 1);
            if (!bandage.isEmpty()) container.setItem(2, bandage);
            block.setChanged();
        }
    }

    private static void staff(ServerLevel level, FactionBaseData data, FactionBaseData.Base base) {
        long now = level.getGameTime();
        if (base.lastStaffCheck != 0 && now - base.lastStaffCheck < STAFF_RESPAWN) return;
        BaseRoster.Slot[] slots = BaseRoster.layout(base).slots();
        // A patrolling resident may stand outside the footprint; reconcile only when its whole leash is loaded.
        for (int cx = (base.pos.getX() - 24) >> 4; cx <= (base.pos.getX() + 24) >> 4; cx++)
            for (int cz = (base.pos.getZ() - 24) >> 4; cz <= (base.pos.getZ() + 24) >> 4; cz++)
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return;
        String prefix = "apocalypsenow:survivor_";
        NpcFactions.Definition faction = NpcFactions.get(base.faction);
        if (faction == null || faction.survivorVariants().isEmpty()) return;
        while (base.staff.size() < slots.length) base.staff.add(VACANT);
        HashSet<UUID> seen = new HashSet<>();
        for (int i = 0; i < slots.length; i++) {
            UUID resident = base.staff.get(i);
            if (!VACANT.equals(resident)) {
                if (seen.add(resident) && level.getEntity(resident) instanceof Mob mob && mob.isAlive()
                        && NpcApi.identity(mob).filter(identity -> base.faction.equals(identity.faction())).isPresent()) {
                    profession(base, mob, i);
                    routine(base, mob, i);
                    continue;
                }
                base.staff.set(i, VACANT);
            }
            if (!VACANT.equals(base.staff.get(i))) continue;
            BlockPos at = BaseRoster.layout(base).at(base.pos, slots[i].post());
            Mob mob = null;
            // A rejected spawn attempt must not leave a post vacant for a full day.
            for (int attempt = 0; attempt < 3 && mob == null; attempt++)
                if (LazarusEvents.spawn(level, at, prefix + faction.variant(i), base.id, true) instanceof Mob spawned)
                    mob = spawned;
            if (mob == null) {
                ApiSupport.LOGGER.warn("Could not spawn {} resident at {} in base {}", slots[i].id(), at, base.id);
                continue;
            }
            ApiResult bound = NpcApi.assignFaction(mob, base.faction);
            if (!bound.ok()) {
                ApiSupport.LOGGER.warn("Could not assign {} resident at {} to {}: {}", slots[i].id(), at,
                        base.faction, bound.message());
                mob.discard();
                continue;
            }
            base.staff.set(i, mob.getUUID());
            profession(base, mob, i);
            routine(base, mob, i);
            mob.addTag("lazarusevents:base_" + (i == 0 ? "contact" : slots[i].profession()));
            order(level, base, mob);
        }
        base.lastStaffCheck = now;
        data.setDirty();
    }

    private static void renewOrders(ServerLevel level, FactionBaseData.Base base) {
        BaseRoster.Slot[] slots = BaseRoster.layout(base).slots();
        for (int i = 0; i < Math.min(base.staff.size(), slots.length); i++) {
            UUID id = base.staff.get(i);
            if (!(level.getEntity(id) instanceof Mob mob) || !mob.isAlive()) continue;
            profession(base, mob, i);
            routine(base, mob, i);
            var assignment = NpcApi.assignment(mob);
            BlockPos anchor = BaseRoster.layout(base).at(base.pos, slots[i].post());
            ScenarioOrder expected = slots[i].guard() ? ScenarioOrder.GUARD_AREA : ScenarioOrder.WAIT;
            if (assignment.isEmpty() || !assignment.get().owner().equals(OWNER)
                    || !assignment.get().encounterId().equals(base.id) || assignment.get().order() != expected
                    || !assignment.get().anchor().pos().equals(anchor)) order(level, base, mob);
            else if (slots[i].duty() == BaseRoster.Duty.PATROL) patrol(base, mob, i);
        }
    }

    private static void profession(FactionBaseData.Base base, Mob mob, int slot) {
        String profession = BaseRoster.layout(base).slots()[slot].profession();
        ApiResult result = NpcApi.assignProfession(mob, new ResourceLocation("lazarusnpcs", profession), base.id);
        if (!result.ok()) ApiSupport.LOGGER.warn("Cannot assign {} to base {} member {}: {}",
                profession, base.id, mob.getUUID(), result.message());
    }

    private static void routine(FactionBaseData.Base base, Mob mob, int index) {
        BaseRoster.Layout layout = BaseRoster.layout(base);
        BaseRoster.Slot slot = layout.slots()[index];
        List<Activity> activities = new java.util.ArrayList<>();
        Activity.Kind[] kinds = slot.guard()
                ? new Activity.Kind[] {Activity.Kind.LOOKOUT, Activity.Kind.PATROL, Activity.Kind.LOOKOUT}
                : new Activity.Kind[] {Activity.Kind.WORK, Activity.Kind.SOCIAL, Activity.Kind.EAT};
        for (int i = 0; i < slot.activities().length; i++)
            activities.add(new Activity(kinds[i % kinds.length], layout.at(base.pos, slot.activities()[i]),
                    i == 0 ? 5 : 2));
        int[] defense = layout.defensePosts()[index % layout.defensePosts().length];
        ApiResult result = NpcApi.setRoutine(mob, new Routine(base.id, activities, slot.night(),
                slot.bed() == null ? null : layout.at(base.pos, slot.bed()),
                layout.at(base.pos, layout.shelter()), layout.at(base.pos, defense)));
        if (!result.ok()) ApiSupport.LOGGER.warn("Routine rejected for base {} slot {}: {}", base.id, slot.id(), result.message());
    }

    private static void order(ServerLevel level, FactionBaseData.Base base, Mob mob) {
        BaseRoster.Slot[] slots = BaseRoster.layout(base).slots();
        int index = Math.min(Math.max(0, base.staff.indexOf(mob.getUUID())), slots.length - 1);
        BaseRoster.Slot slot = slots[index];
        BlockPos anchor = BaseRoster.layout(base).at(base.pos, slot.post());
        // Sentries keep a short leash on their post; patrols need room for the whole route.
        int radius = slot.duty() == BaseRoster.Duty.SENTRY ? 5 : 10;
        NpcApi.assign(mob, new ScenarioAssignment(OWNER, base.id,
                slot.guard() ? ScenarioOrder.GUARD_AREA : ScenarioOrder.WAIT,
                GlobalPos.of(level.dimension(), anchor), null, radius, ORDER_RENEWAL, 4));
        if (slot.duty() == BaseRoster.Duty.PATROL) patrol(base, mob, index);
    }

    private static void patrol(FactionBaseData.Base base, Mob mob, int slot) {
        BaseRoster.Layout layout = BaseRoster.layout(base);
        ApiResult result = NpcApi.setPatrolRoute(mob, OWNER, base.id, java.util.Arrays.stream(layout.slots()[slot].route())
                .map(point -> layout.at(base.pos, point)).toList());
        if (!result.ok()) ApiSupport.LOGGER.warn("Patrol route rejected for base {} slot {}: {}", base.id, slot, result.message());
    }

    @SubscribeEvent
    public void residentLoaded(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !(event.getEntity() instanceof Mob mob)) return;
        for (FactionBaseData.Base base : FactionBaseData.get(level).bases) {
            int index = base.staff.indexOf(mob.getUUID());
            if (index < 0 || index >= BaseRoster.layout(base).slots().length) continue;
            level.getServer().execute(() -> {
                if (mob.isAlive() && NpcApi.identity(mob)
                        .filter(identity -> base.faction.equals(identity.faction())).isPresent())
                    routine(base, mob, index);
            });
            return;
        }
    }

    @SubscribeEvent
    public void died(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        FactionBaseData data = FactionBaseData.get(level);
        for (FactionBaseData.Base base : data.bases) {
            int slot = base.staff.indexOf(event.getEntity().getUUID());
            if (slot >= 0) {
                base.staff.set(slot, VACANT);
                if (slot == 0) promote(level, base);
                base.lastStaffCheck = level.getGameTime();
                data.setDirty();
                return;
            }
        }
    }

    /** The first living guard, in slot order, becomes interim leader; the guard slot is refilled later. */
    private static void promote(ServerLevel level, FactionBaseData.Base base) {
        if (base.staff.isEmpty() || !VACANT.equals(base.staff.get(0))) return;
        BaseRoster.Slot[] slots = BaseRoster.layout(base).slots();
        for (int successor = 1; successor < Math.min(slots.length, base.staff.size()); successor++) {
            if (!slots[successor].guard()) continue;
            UUID candidate = base.staff.get(successor);
            if (!(level.getEntity(candidate) instanceof Mob mob) || !mob.isAlive()) continue;
            BlockPos anchor = BaseRoster.layout(base).at(base.pos, slots[0].post());
            if (!NpcApi.assign(mob, new ScenarioAssignment(OWNER, base.id, ScenarioOrder.WAIT,
                    GlobalPos.of(level.dimension(), anchor), null, 9, ORDER_RENEWAL, 4)).ok()) continue;
            base.staff.set(0, candidate);
            base.staff.set(successor, VACANT);
            base.leaderReadyAt = level.getGameTime() + 24_000L;
            profession(base, mob, 0);
            routine(base, mob, 0);
            return;
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lazarusbase").requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(c -> list(c.getSource())))
                .then(Commands.literal("locate").then(Commands.argument("faction", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(BaseRoster.factions(), b))
                        .executes(c -> locate(c.getSource(), StringArgumentType.getString(c, "faction")))))
                .then(Commands.literal("inspect").executes(c -> inspect(c.getSource())))
                .then(Commands.literal("roles").executes(c -> roles(c.getSource())))
                .then(Commands.literal("expedition").executes(c -> expedition(c.getSource())))
                .then(Commands.literal("mission")
                        .then(Commands.literal("recon").executes(c -> mission(c.getSource(), false)))
                        .then(Commands.literal("theft").executes(c -> mission(c.getSource(), true))))
                .then(Commands.literal("check").then(Commands.argument("faction", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(BaseRoster.factions(), b))
                        .executes(c -> check(c.getSource(), StringArgumentType.getString(c, "faction")))))
                .then(Commands.literal("create").then(Commands.argument("faction", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(BaseRoster.factions(), b))
                        .executes(c -> createCommand(c.getSource(), StringArgumentType.getString(c, "faction"))))));
    }

    private static int list(CommandSourceStack source) {
        FactionBaseData data = FactionBaseData.get(source.getServer().overworld());
        if (data.bases.isEmpty()) source.sendSuccess(() -> Component.literal("No faction bases yet."), false);
        for (FactionBaseData.Base base : data.bases) source.sendSuccess(() -> Component.literal(base.faction + " " + base.id
                + " at " + base.pos.toShortString() + "; garrison " + base.staff.stream().filter(id -> !VACANT.equals(id)).count()
                + "; stock " + base.stock), false);
        return data.bases.size();
    }

    private static int locate(CommandSourceStack source, String faction) {
        FactionBaseData.Base base = FactionBaseData.get(source.getServer().overworld()).faction(faction);
        if (base == null) { source.sendFailure(Component.literal("No registered base for " + faction + ".")); return 0; }
        source.sendSuccess(() -> Component.literal(faction + " base " + base.id + " at " + base.pos.toShortString()), false);
        return 1;
    }

    private static int inspect(CommandSourceStack source) {
        FactionBaseData.Base base = FactionBaseData.get(source.getServer().overworld())
                .near(BlockPos.containing(source.getPosition()), 32);
        if (base == null) { source.sendFailure(Component.literal("No base within 32 blocks.")); return 0; }
        long present = base.staff.stream().filter(id -> !VACANT.equals(id)
                && source.getServer().overworld().getEntity(id) instanceof Mob).count();
        source.sendSuccess(() -> Component.literal(base.faction + " " + base.id + "; template v" + base.model + "; position "
                + base.pos.toShortString() + "; origin " + base.origin + "; assigned " + base.staff.stream().filter(id -> !VACANT.equals(id)).count()
                + "; loaded " + present + "; stock " + base.stock + "; leader ready at " + base.leaderReadyAt), false);
        return 1;
    }

    private static int roles(CommandSourceStack source) {
        FactionBaseData.Base base = FactionBaseData.get(source.getServer().overworld())
                .near(BlockPos.containing(source.getPosition()), 32);
        if (base == null) { source.sendFailure(Component.literal("No base within 32 blocks.")); return 0; }
        BaseRoster.Slot[] slots = BaseRoster.layout(base).slots();
        for (int i = 0; i < slots.length; i++) {
            int slot = i;
            UUID resident = i < base.staff.size() ? base.staff.get(i) : VACANT;
            source.sendSuccess(() -> Component.literal(slots[slot].id() + " (" + slots[slot].profession()
                    + "): " + (VACANT.equals(resident) ? "vacant" : resident)), false);
        }
        return 1;
    }

    private static int expedition(CommandSourceStack source) {
        try { return FactionExpeditions.force(source.getPlayerOrException()) ? 1 : 0; }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("A player must run this command."));
            return 0;
        }
    }

    private static int mission(CommandSourceStack source, boolean theft) {
        try { return FactionMissions.offerTest(source.getPlayerOrException(), theft) ? 1 : 0; }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("A player must run this command."));
            return 0;
        }
    }

    private static int createCommand(CommandSourceStack source, String faction) {
        if (BaseRoster.profile(faction) == null) {
            source.sendFailure(Component.literal("Choose a faction with a base profile: " + BaseRoster.factions()));
            return 0;
        }
        ServerLevel level = source.getLevel();
        if (level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.literal("Bases only belong in the Overworld."));
            return 0;
        }
        BlockPos here = BlockPos.containing(source.getPosition());
        FactionBaseData data = FactionBaseData.get(level);
        String problem = globalProblem(level, data, here, faction);
        if (problem != null) {
            source.sendFailure(Component.literal("Base rejected at " + here.toShortString() + ": " + problem));
            return 0;
        }
        Search search = search(level, here, 16, 4, faction);
        if (search.best() == null) {
            source.sendFailure(Component.literal("Base rejected near " + here.toShortString() + ": " + search.summary()));
            return 0;
        }
        FactionBaseData.Base base = create(level, data, search.best(), faction, "operator");
        if (base == null) {
            source.sendFailure(Component.literal("Structure placement failed; no base was registered."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Created " + faction + " base " + base.id + " at "
                + base.pos.toShortString() + " (terraformed " + search.best().cost() + " blocks)"), false);
        return 1;
    }

    private static int check(CommandSourceStack source, String faction) {
        if (BaseRoster.profile(faction) == null) {
            source.sendFailure(Component.literal("Choose a faction with a base profile: " + BaseRoster.factions()));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos here = BlockPos.containing(source.getPosition());
        String problem = level.dimension() != Level.OVERWORLD ? "bases only belong in the Overworld"
                : globalProblem(level, FactionBaseData.get(level), here, faction);
        Search search = search(level, here, 16, 4, faction);
        if (problem != null) source.sendFailure(Component.literal("Placement blocked: " + problem));
        if (search.best() == null) {
            source.sendFailure(Component.literal("Terrain near " + here.toShortString() + ": " + search.summary()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Best " + faction + " site at " + search.best().anchor().toShortString()
                + ": terraform " + search.best().cost() + " blocks; " + search.reasons().values().stream()
                        .mapToInt(Integer::intValue).sum() + " of " + search.tried() + " positions rejected "
                + search.reasons()), false);
        return problem == null ? 1 : 0;
    }
}
