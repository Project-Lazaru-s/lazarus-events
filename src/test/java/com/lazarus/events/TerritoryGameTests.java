package com.lazarus.events;

import java.util.Set;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("lazarusevents")
@PrefixGameTestTemplate(false)
public final class TerritoryGameTests {
    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void heldTorchIgnitesInCreativeButNotSpectator(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos at = h.absolutePos(new BlockPos(8, 1, 4));
        ServerPlayer p = player(h, at);
        h.assertTrue(NaturalGas.release(h.getLevel(), p, at, true), "pocket opens");
        var data = EventData.get(h.getLevel());
        var gas = data.encounters.stream().filter(e -> NaturalGas.TYPE.equals(e.type) && e.pos.equals(at)).findFirst().orElseThrow();
        p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH));
        p.setGameMode(GameType.SPECTATOR);
        NaturalGas.playerIgnition(h.getLevel(), gas, GasZone.cells(gas), p);
        h.assertTrue(!gas.data.contains("Ignites"), "spectator cannot ignite gas");
        p.setGameMode(GameType.CREATIVE);
        NaturalGas.playerIgnition(h.getLevel(), gas, GasZone.cells(gas), p);
        h.assertTrue(gas.data.contains("Ignites"), "creative offhand torch starts warning");
        data.remove(gas.id); p.discard(); h.succeed();
    }
    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void gasBlastMultipliesActualDamage(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos at = h.absolutePos(new BlockPos(8, 1, 4));
        var baseline = net.minecraft.world.entity.EntityType.COW.create(h.getLevel());
        baseline.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
        baseline.setHealth(1000); baseline.setNoAi(true);
        baseline.moveTo(at.getX() + 2.5, at.getY(), at.getZ() + .5, 0, 0);
        h.getLevel().addFreshEntity(baseline);
        h.getLevel().explode(null, at.getX() + .5, at.getY() + .5, at.getZ() + .5,
                TerritoryConfig.BLAST_POWER.get().floatValue(), net.minecraft.world.level.Level.ExplosionInteraction.NONE);
        float normal = 1000 - baseline.getHealth(); baseline.discard();
        var gas = net.minecraft.world.entity.EntityType.COW.create(h.getLevel());
        gas.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
        gas.setHealth(1000); gas.setNoAi(true);
        gas.moveTo(at.getX() + 2.5, at.getY(), at.getZ() + .5, 0, 0);
        h.getLevel().addFreshEntity(gas);
        NaturalGas.explode(h.getLevel(), at);
        h.assertTrue(normal > 0 && 1000 - gas.getHealth() >= normal * 3.99f, "actual gas damage " + (1000 - gas.getHealth()) + " vs vanilla " + normal + " at power " + TerritoryConfig.BLAST_POWER.get());
        gas.discard(); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void everyRegisteredMaskProtectsBothGases(GameTestHelper h) {
        ServerPlayer p = player(h, h.absolutePos(new BlockPos(8, 1, 4)));
        int count = 0;
        for (var item : net.minecraftforge.registries.ForgeRegistries.ITEMS.tags().getTag(GasZone.GAS_MASKS)) {
            ItemStack mask = new ItemStack(item);
            p.setItemSlot(EquipmentSlot.HEAD, mask);
            NaturalGas.breathe(p, 2);
            h.assertTrue(GasZone.breathe(p, 3) == 0 && p.getActiveEffects().isEmpty(), "each tagged mask protects both gases: " + item);
            count++;
        }
        h.assertTrue(count == 24, "all 24 supported Apocalypse Now mask IDs are registered");
        p.discard(); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void functionalCuriosMaskProtectsWithoutHelmet(GameTestHelper h) {
        ServerPlayer p = player(h, h.absolutePos(new BlockPos(8, 1, 4)));
        var inventory = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).resolve().orElseThrow();
        ItemStack mask = new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation("apocalypsenow:mo_4gasmaskfaceadaptation")));
        inventory.setEquippedCurio("head", 0, mask);
        h.assertTrue(!GasCurios.mask(p).isEmpty(), "functional Curios slot supplies mask");
        NaturalGas.breathe(p, 2);
        h.assertTrue(GasZone.breathe(p, 3) == 0 && p.getActiveEffects().isEmpty(), "Curios mask protects both gases");
        inventory.setEquippedCurio("head", 0, ItemStack.EMPTY);
        h.assertTrue(!GasZone.masked(p), "inventory alone supplies no protection");
        p.discard(); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 100)
    public static void placedSoulTorchAutomaticallyIgnitesGas(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos at = h.absolutePos(new BlockPos(8, 1, 4));
        ServerPlayer p = player(h, at);
        h.assertTrue(NaturalGas.release(h.getLevel(), p, at, true), "pocket opens");
        var data = EventData.get(h.getLevel());
        var gas = data.encounters.stream().filter(e -> NaturalGas.TYPE.equals(e.type) && e.pos.equals(at)).findFirst().orElseThrow();
        h.getLevel().setBlock(at, Blocks.SOUL_TORCH.defaultBlockState(), 3);
        h.runAfterDelay(25, () -> h.assertTrue(gas.data.contains("Ignites"), "placed torch ignites in its own gas voxel"));
        h.runAfterDelay(90, () -> {
            h.assertTrue(!data.encounters.contains(gas), "torch consumes pocket after warning");
            p.discard(); h.succeed();
        });
    }

    @GameTest(template = "territory_arena", timeoutTicks = 40)
    public static void premiumRewardsGuaranteeEquipmentAndFitContainer(GameTestHelper h) {
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(h.getLevel())
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                        net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(BlockPos.ZERO)))
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
        for (int tier = 1; tier <= 5; tier++) for (String kind : new String[]{"metro_medical", "infection_medical", "lost_miners"}) {
            var table = h.getLevel().getServer().getLootData().getLootTable(ApiSupport.id("chests/" + kind + tier));
            h.assertTrue(table != net.minecraft.world.level.storage.loot.LootTable.EMPTY, "reward table exists: " + kind + tier);
            for (int sample = 0; sample < 25; sample++) {
                var items = table.getRandomItems(params);
                int slots = items.stream().mapToInt(stack -> (stack.getCount() + stack.getMaxStackSize() - 1) / stack.getMaxStackSize()).sum();
                h.assertTrue(slots <= 27, "reward fits barrel/chest: " + kind + tier + " needs " + slots);
                h.assertTrue(items.stream().anyMatch(stack -> stack.is(GasZone.GAS_MASKS)), "respiratory equipment guaranteed");
                h.assertTrue(items.stream().anyMatch(stack -> net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())
                        .toString().equals("apocalypsenow:breathingfiltermodule")), "filters guaranteed");
                if (kind.equals("lost_miners")) {
                    h.assertTrue(items.stream().filter(stack -> stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum() >= 3, "valuable minerals guaranteed");
                    h.assertTrue(items.stream().anyMatch(stack -> stack.is(Items.DIAMOND_PICKAXE) && stack.isEnchanted()), "enchanted diamond pickaxe guaranteed");
                } else {
                    h.assertTrue(items.stream().anyMatch(stack -> stack.is(GasZone.HAZMAT) && !stack.is(GasZone.GAS_MASKS)), "protective suit piece guaranteed");
                    h.assertTrue(items.stream().filter(stack -> net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())
                            .toString().equals("apocalypsenow:medicalkit")).mapToInt(ItemStack::getCount).sum() >= 2, "multiple medical kits guaranteed");
                }
            }
        }
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void contaminationAndBiohazardSetsProtectWithoutWear(GameTestHelper h) {
        ServerPlayer p = player(h, h.absolutePos(new BlockPos(8, 1, 4)));
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        String[] suffix = {"helmet", "chestplate", "leggings", "boots"};
        for (String set : new String[]{"contamination_suit_white", "contamination_suit_blue", "contamination_suit_green", "biohazard"}) {
            for (int i = 0; i < slots.length; i++) {
                String id = i == 3 && set.startsWith("contamination") ? "contamination_suit_white_boots" : set + "_" + suffix[i];
                p.setItemSlot(slots[i], new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getValue(new net.minecraft.resources.ResourceLocation("apocalypsenow", id))));
            }
            h.assertTrue(GasZone.hazmat(p), "complete protective suit recognized: " + set);
            NaturalGas.breathe(p, 2);
            h.assertTrue(GasZone.breathe(p, 4) == 0 && p.getActiveEffects().isEmpty()
                    && p.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 0, "full suit protects both gases without wear: " + set);
        }
        p.discard(); h.succeed();
    }

    private static void room(GameTestHelper h, int length, int width) {
        for (int x = 0; x <= length + 1; x++) for (int z = 0; z <= width + 1; z++) for (int y = 0; y <= 7; y++)
            h.setBlock(new BlockPos(x, y, z), y == 0 || y == 7 || z == 0 || z == width + 1 ? Blocks.STONE : Blocks.AIR);
    }
    private static ServerPlayer player(GameTestHelper h, BlockPos at) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "GasTest"));
        p.setGameMode(GameType.SURVIVAL);
        p.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        return p;
    }
    private static void actors(GameTestHelper h, UUID id) {
        h.getLevel().getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(64, 10, 20))).inflate(8),
                e -> e.getTags().contains(LazarusEvents.ID + ":" + id)).forEach(net.minecraft.world.entity.Entity::discard);
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void tunnelStaysInsideAndHasTwoCutExits(GameTestHelper h) {
        room(h, 62, 5);
        BlockPos start = h.absolutePos(new BlockPos(31, 1, 3));
        BuildingHideout.Room gas = GasVolumes.tunnel(h.getLevel(), start, p -> true);
        h.assertTrue(gas != null && gas.cells().size() >= 250, "fills a long connected tunnel");
        h.assertTrue(gas.cells().stream().noneMatch(p -> p.getZ() <= start.getZ() - 3 || p.getZ() >= start.getZ() + 3), "walls contain gas");
        h.assertTrue(gas.openings().stream().anyMatch(p -> p.getX() < start.getX() - 29)
                && gas.openings().stream().anyMatch(p -> p.getX() > start.getX() + 29), "both roofed ends are exits");
        var bounded = GasVolumes.tunnel(h.getLevel(), start, p -> p.getX() <= start.getX() + 10);
        h.assertTrue(bounded != null && bounded.cells().stream().noneMatch(p -> p.getX() > start.getX() + 10), "station boundary is not flooded");
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void shelterFurnishesOnlyOnePlatform(GameTestHelper h) {
        room(h, 20, 14);
        BlockPos origin = h.absolutePos(new BlockPos(1, 1, 0));
        UUID id = UUID.randomUUID();
        TerritoryEvents.Scene scene = TerritoryEvents.shelter(origin, true, 1);
        h.assertTrue(TerritoryEvents.build(h.getLevel(), scene, TerritoryEvents.SHELTER, id), "shelter fits platform");
        h.assertTrue(h.getLevel().getBlockEntity(scene.diary()) instanceof LecternBlockEntity l && !l.getBook().isEmpty(), "diary on lectern");
        h.assertTrue(scene.loot().size() == 2 && scene.loot().stream().allMatch(p -> h.getLevel().getBlockEntity(p)
                instanceof RandomizableContainerBlockEntity c && c.saveWithoutMetadata().contains("LootTable")), "two tiered loot tables");
        long tents = scene.blocks().values().stream().filter(s -> s.is(Blocks.GRAY_WOOL)).count();
        h.assertTrue(tents == 36, "four complete tent roofs");
        h.assertTrue(h.getLevel().getBlockState(origin.offset(5, 0, 10)).isAir(), "other platform untouched");
        h.assertTrue(h.getLevel().getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(64, 10, 20))), e -> e.getTags().contains("lazarusevents:" + id)).size() == 4,
                "four civilian infected");
        actors(h, id); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void blockedSceneDoesNotOverwriteExistingLoot(GameTestHelper h) {
        room(h, 20, 14);
        var scene = TerritoryEvents.shelter(h.absolutePos(new BlockPos(1, 1, 0)), true, 1);
        BlockPos obstacle = scene.diary();
        h.getLevel().setBlock(obstacle, Blocks.CHEST.defaultBlockState(), 3);
        h.assertTrue(!TerritoryEvents.build(h.getLevel(), scene, TerritoryEvents.SHELTER, UUID.randomUUID()), "reject occupied scene");
        h.assertTrue(h.getLevel().getBlockState(obstacle).is(Blocks.CHEST), "existing chest preserved");
        h.assertTrue(h.getLevel().getBlockState(scene.loot().get(0)).isAir(), "no partial placement");
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void ruinedPlatformMovesSpawnsAroundMoss(GameTestHelper h) {
        room(h, 20, 14);
        BlockPos origin = h.absolutePos(new BlockPos(1, 1, 0));
        // Observed in the player's saved station, western wing: moss at the third fixed spawn.
        BlockPos moss = origin.offset(8, 0, 4);
        h.getLevel().setBlock(moss, Blocks.MOSS_CARPET.defaultBlockState(), 3);
        for (int a : new int[]{5, 6, 9, 10})
            h.getLevel().setBlock(origin.offset(a, 0, 1), Blocks.SMOOTH_STONE_SLAB.defaultBlockState(), 3);
        var scene = TerritoryEvents.shelterOnPlatform(h.getLevel(), origin, true, 1);
        h.assertTrue(scene != null && scene.spawns().size() == 4 && !scene.spawns().contains(moss), "four free actor positions despite moss");
        UUID id = UUID.randomUUID();
        h.assertTrue(TerritoryEvents.build(h.getLevel(), scene, TerritoryEvents.SHELTER, id), "ruined platform accepts shelter");
        h.assertTrue(h.getLevel().getBlockState(moss).is(Blocks.MOSS_CARPET), "walkway moss need not be removed");
        actors(h, id); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void lostMinersHaveEquipmentDiaryAndOre(GameTestHelper h) {
        room(h, 20, 8);
        var scene = TerritoryEvents.miners(h.absolutePos(new BlockPos(3, 1, 2)), false);
        UUID id = UUID.randomUUID();
        h.assertTrue(TerritoryEvents.build(h.getLevel(), scene, TerritoryEvents.MINERS, id), "mine scene fits");
        var mobs = h.getLevel().getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(64, 10, 20))), m -> m.getTags().contains("lazarusevents:" + id));
        h.assertTrue(mobs.size() == 3 && mobs.stream().allMatch(m -> m.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                && m.getMainHandItem().is(Items.IRON_PICKAXE)), "three equipped miners");
        h.assertTrue(h.getLevel().getBlockEntity(scene.diary()) instanceof LecternBlockEntity l && !l.getBook().isEmpty(), "collapse diary");
        h.assertTrue(h.getLevel().getBlockEntity(scene.loot().get(0)) instanceof RandomizableContainerBlockEntity c
                && java.util.stream.IntStream.range(0, c.getContainerSize()).anyMatch(slot -> c.getItem(slot).is(Items.RAW_IRON)), "extracted ore");
        h.assertTrue(h.getLevel().getBlockState(scene.cart()).is(Blocks.RAIL), "cart rail");
        actors(h, id); h.succeed();
    }

    @GameTest(template = "mine_gallery_regression", timeoutTicks = 60)
    public static void lostMinersFindGalleryFromSavedSlabStaircase(GameTestHelper h) {
        BlockPos entrance = h.absolutePos(new BlockPos(24, 49, 24));
        // Geometry exported from the reported mine; no player data/entities are included in the template.
        var scene = MineScenes.find(h.getLevel(), entrance, 48, p -> true);
        h.assertTrue(scene != null, "finds a usable landing/gallery beyond the slab staircase");
        UUID id = UUID.randomUUID();
        h.assertTrue(TerritoryEvents.build(h.getLevel(), scene, TerritoryEvents.MINERS, id), "scene fits the actual saved mine geometry");
        h.assertTrue(scene.spawns().size() == 3, "three miners remain part of the encounter");
        for (var mob : h.getLevel().getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(entrance).inflate(24),
                m -> m.getTags().contains("lazarusevents:" + id))) mob.discard();
        for (var cart : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.vehicle.Minecart.class,
                new net.minecraft.world.phys.AABB(entrance).inflate(24), m -> m.getTags().contains("lazarusevents:" + id))) cart.discard();
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void minersAdaptToNarrowGalleryAndPreserveSupports(GameTestHelper h) {
        room(h, 16, 2);
        BlockPos start = h.absolutePos(new BlockPos(8, 1, 1));
        BlockPos pillar = h.absolutePos(new BlockPos(4, 1, 1));
        h.getLevel().setBlock(pillar, Blocks.OAK_LOG.defaultBlockState(), 3);
        h.getLevel().setBlock(pillar.above(), Blocks.OAK_LOG.defaultBlockState(), 3);
        var scene = MineScenes.gallery(h.getLevel(), start);
        h.assertTrue(scene != null && !scene.blocks().containsKey(pillar), "scene adapts without removing support");
        UUID id = UUID.randomUUID();
        h.assertTrue(TerritoryEvents.build(h.getLevel(), scene, TerritoryEvents.MINERS, id), "narrow gallery accepts encounter");
        h.assertTrue(h.getLevel().getBlockState(pillar).is(Blocks.OAK_LOG), "support remains intact");
        actors(h, id); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void naturalGasProtectsMasksAndNeverInfects(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos start = h.absolutePos(new BlockPos(8, 1, 4));
        Set<Long> volume = GasVolumes.pocket(h.getLevel(), start);
        h.assertTrue(!volume.isEmpty() && volume.stream().map(BlockPos::of).noneMatch(p -> h.getLevel().getBlockState(p).is(Blocks.STONE)), "air voxels only");
        ServerPlayer p = player(h, start);
        NaturalGas.breathe(p, 1);
        h.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS), "natural gas weakens");
        h.assertTrue(!p.hasEffect(net.minecraft.world.effect.MobEffects.POISON)
                && p.getActiveEffects().size() == 2, "only nausea and weakness; no poison or infection");
        p.removeAllEffects();
        var mask = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("apocalypsenow:gas_mask_helmet_helmet"));
        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(mask));
        NaturalGas.breathe(p, 2);
        h.assertTrue(p.getActiveEffects().isEmpty(), "mask protects");
        h.assertTrue(!p.getItemBySlot(EquipmentSlot.HEAD).isDamageableItem() || p.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 1, "filter wears");
        p.discard(); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 100)
    public static void ignitionWarnsThenDestroysBlocks(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos start = h.absolutePos(new BlockPos(8, 1, 4));
        ServerPlayer p = player(h, start);
        h.assertTrue(NaturalGas.release(h.getLevel(), p, start, true), "forced pocket opens");
        EventData data = EventData.get(h.getLevel());
        var e = data.encounters.stream().filter(v -> NaturalGas.TYPE.equals(v.type) && v.pos.equals(start)).findFirst().orElseThrow();
        h.getLevel().setBlock(start.below(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        var victim = net.minecraft.world.entity.EntityType.COW.create(h.getLevel());
        victim.moveTo(start.getX() + 1.5, start.getY(), start.getZ() + .5, 0, 0);
        victim.setNoAi(true); h.getLevel().addFreshEntity(victim);
        float before = victim.getHealth();
        NaturalGas.spark(h.getLevel(), start);
        long when = e.data.getLong("Ignites");
        NaturalGas.ignite(h.getLevel(), e, start.offset(1, 0, 0));
        h.assertTrue(e.data.getLong("Ignites") == when && when - h.getLevel().getGameTime() == 60, "three-second warning never resets");
        h.runAfterDelay(20, () -> h.assertTrue(data.encounters.contains(e), "no immediate explosion"));
        h.runAfterDelay(65, () -> {
            h.assertTrue(!data.encounters.contains(e), "explosion consumes pocket");
            h.assertTrue(!h.getLevel().getBlockState(start.below()).is(Blocks.OAK_PLANKS), "gas destroys vulnerable terrain");
            h.assertTrue(victim.getHealth() < before || !victim.isAlive(), "explosion actually damages nearby entities");
            victim.discard();
            p.discard(); h.succeed();
        });
    }

    @GameTest(template = "territory_arena", timeoutTicks = 40, batch = "mining_success")
    public static void successfulMiningRollAndCooldown(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos start = h.absolutePos(new BlockPos(8, 1, 4));
        ServerPlayer p = player(h, start.offset(0, 0, 1));
        int oldChance = TerritoryConfig.CHANCE.get(), oldY = TerritoryConfig.MAX_Y.get();
        TerritoryConfig.CHANCE.set(1); TerritoryConfig.MAX_Y.set(320);
        h.getLevel().setBlock(start, Blocks.DEEPSLATE.defaultBlockState(), 3);
        MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(h.getLevel(), start, Blocks.DEEPSLATE.defaultBlockState(), p));
        h.getLevel().setBlock(start, Blocks.AIR.defaultBlockState(), 3);
        h.runAfterDelay(4, () -> {
            TerritoryConfig.CHANCE.set(oldChance); TerritoryConfig.MAX_Y.set(oldY);
            EventData data = EventData.get(h.getLevel());
            var pockets = data.encounters.stream().filter(e -> NaturalGas.TYPE.equals(e.type) && e.pos.equals(start)).toList();
            h.assertTrue(pockets.size() == 1, "chance 1 opens gas after actual break");
            pockets.forEach(e -> data.remove(e.id));
            h.assertTrue(!NaturalGas.release(h.getLevel(), p, start, false), "cooldown blocks repeated mining release");
            p.discard(); h.succeed();
        });
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void cityWorldFallbackAndHeightBands(GameTestHelper h) {
        h.assertTrue(!CityWorldSites.available() && CityWorldSites.subway(h.getLevel(), h.absolutePos(BlockPos.ZERO)).isEmpty(), "no CityWorld is safe");
        var s = new CityWorldSites.Subway(true, true, true, 40, 32);
        h.assertTrue(!CityWorldSites.contains(s, 60) && !CityWorldSites.contains(s, 39), "street and gap between levels are not metro");
        h.assertTrue(CityWorldSites.contains(s, 42) && CityWorldSites.contains(s, 34), "both platform levels work");
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 110)
    public static void burningCampfireAutomaticallyIgnitesPocket(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos start = h.absolutePos(new BlockPos(8, 1, 4));
        ServerPlayer p = player(h, start);
        h.assertTrue(NaturalGas.release(h.getLevel(), p, start, true), "pocket opens");
        var data = EventData.get(h.getLevel());
        var gas = data.encounters.stream().filter(e -> NaturalGas.TYPE.equals(e.type) && e.pos.equals(start)).findFirst().orElseThrow();
        h.getLevel().setBlock(start, Blocks.CAMPFIRE.defaultBlockState(), 3);
        h.runAfterDelay(25, () -> h.assertTrue(gas.data.contains("Ignites"), "lit campfire triggers warning automatically"));
        h.runAfterDelay(90, () -> {
            h.assertTrue(!data.encounters.contains(gas), "fire consumes the pocket");
            h.assertTrue(!h.getLevel().getBlockState(start).is(Blocks.CAMPFIRE), "explosion destroys the fire block");
            p.discard(); h.succeed();
        });
    }

    @GameTest(template = "territory_arena", timeoutTicks = 40, batch = "mining_cancel")
    public static void cancelledMiningCannotReleaseGas(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos start = h.absolutePos(new BlockPos(8, 1, 4));
        ServerPlayer p = player(h, start.offset(0, 0, 1));
        int chance = TerritoryConfig.CHANCE.get(), y = TerritoryConfig.MAX_Y.get();
        TerritoryConfig.CHANCE.set(1); TerritoryConfig.MAX_Y.set(320);
        h.getLevel().setBlock(start, Blocks.DEEPSLATE.defaultBlockState(), 3);
        var event = new BlockEvent.BreakEvent(h.getLevel(), start, Blocks.DEEPSLATE.defaultBlockState(), p);
        event.setCanceled(true); MinecraftForge.EVENT_BUS.post(event);
        // Also queue an uncancelled notification whose block was ultimately not broken.
        MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(h.getLevel(), start, Blocks.DEEPSLATE.defaultBlockState(), p));
        h.runAfterDelay(4, () -> {
            TerritoryConfig.CHANCE.set(chance); TerritoryConfig.MAX_Y.set(y);
            h.assertTrue(EventData.get(h.getLevel()).encounters.stream().noneMatch(e -> NaturalGas.TYPE.equals(e.type) && e.pos.equals(start)),
                    "cancelled/uncompleted break does not open gas");
            p.discard(); h.succeed();
        });
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void taczShotIgnitesGasThroughEventBus(GameTestHelper h) {
        room(h, 16, 8);
        BlockPos start = h.absolutePos(new BlockPos(8, 1, 4));
        ServerPlayer p = player(h, start);
        h.assertTrue(NaturalGas.release(h.getLevel(), p, start, true), "pocket ready");
        var data = EventData.get(h.getLevel());
        var gas = data.encounters.stream().filter(e -> NaturalGas.TYPE.equals(e.type) && e.pos.equals(start)).findFirst().orElseThrow();
        MinecraftForge.EVENT_BUS.post(new com.tacz.guns.api.event.common.GunFireEvent(p, ItemStack.EMPTY, net.minecraftforge.fml.LogicalSide.SERVER));
        h.assertTrue(gas.data.getLong("Ignites") == h.getLevel().getGameTime() + 60, "registered TaCZ hook starts warning");
        data.remove(gas.id); p.discard(); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void originalInfectiousGasKeepsPoisonAndHazmatRules(GameTestHelper h) {
        ServerPlayer p = player(h, h.absolutePos(new BlockPos(8, 1, 4)));
        for (long second = 1; second <= 16; second++) GasZone.breathe(p, second);
        h.assertTrue(p.hasEffect(net.minecraft.world.effect.MobEffects.POISON), "existing Z3 still poisons");
        p.removeAllEffects();
        String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) p.setItemSlot(slots[i], new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(new net.minecraft.resources.ResourceLocation("apocalypsenow:hazmat_suit_" + pieces[i]))));
        h.assertTrue(GasZone.breathe(p, 20) == 0, "hazmat prevents infectious exposure");
        NaturalGas.breathe(p, 21);
        h.assertTrue(p.getActiveEffects().isEmpty() && p.getItemBySlot(EquipmentSlot.HEAD).getDamageValue() == 0,
                "hazmat also protects natural gas without filter wear");
        p.discard(); h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 30)
    public static void saveReloadKeepsGasWarningAndExhaustedSite(GameTestHelper h) throws Exception {
        EventData data = new EventData();
        BlockPos at = h.absolutePos(new BlockPos(8, 1, 4));
        var e = data.add(UUID.randomUUID(), NaturalGas.TYPE, at, h.getLevel().getGameTime());
        e.data.putLongArray("Cells", new long[]{at.asLong()});
        e.data.putLong("Expires", 123456); e.data.putLong("Ignites", 123400);
        data.sites.add("metro_shelter:1:42:2");
        var load = EventData.class.getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class);
        load.setAccessible(true);
        EventData restored = (EventData) load.invoke(null, data.save(new net.minecraft.nbt.CompoundTag()));
        h.assertTrue(restored.sites.equals(data.sites) && restored.encounters.size() == 1, "site reservation and encounter survive reload");
        h.assertTrue(restored.encounters.get(0).data.getLong("Ignites") == 123400
                && restored.encounters.get(0).data.getLongArray("Cells")[0] == at.asLong(), "volume and pending ignition survive reload");
        h.succeed();
    }
}
