package com.lazarus.events;

import java.util.List;
import java.util.UUID;
import com.lazarus.npcs.api.HostileGroups;
import com.lazarus.npcs.api.NpcApi;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Wanted players (bounty B1): gang standing, wanted detection and hunter squads. */
@GameTestHolder("lazarusevents")
@PrefixGameTestTemplate(false)
public final class BountyGameTests {
    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void squadSizeAndGangRecoveryFollowTheStanding(GameTestHelper h) {
        h.assertTrue(Bounties.squadSize(-40) == 2 && Bounties.squadSize(-70) == 3 && Bounties.squadSize(-95) == 4, "squad size by depth");
        h.assertTrue(Bounties.recovered(-48, 0) == -48, "no recovery the same day");
        h.assertTrue(Bounties.recovered(-48, 2) == -38, "5 a day");
        h.assertTrue(Bounties.recovered(-12, 5) == 0, "never past 0");
        h.assertTrue(Bounties.recovered(20, 3) == 20, "positive standing stays");
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void killingLootersMakesTheGangWantYou(GameTestHelper h) {
        floor(h);
        ServerPlayer player = player(h, h.absolutePos(new BlockPos(2, 1, 2)));
        BlockPos at = h.absolutePos(new BlockPos(6, 1, 6));
        String gang = HostileGroups.gang(h.getLevel(), at);
        h.assertTrue(Bounties.wanted(player).isEmpty(), "not wanted at first");
        for (int i = 0; i < 5; i++) {
            Mob looter = (Mob) LazarusEvents.spawn(h.getLevel(), at, "apocalypsenow:looter_1", UUID.randomUUID(), false);
            h.assertTrue(looter != null, "looter spawned");
            looter.hurt(player.damageSources().playerAttack(player), 1000);
        }
        h.assertTrue(Bounties.gangStanding(player, gang) == 5 * Bounties.GANG_KILL, "8 per looter: " + Bounties.gangStanding(player, gang));
        h.assertTrue(Bounties.wanted(player).containsKey(Bounties.gangTarget(gang)), "five kills put a price on your head");
        // standing is kept in the persisted tag, which survives death
        h.assertTrue(player.getPersistentData().getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)
                .contains("LazarusEventsBounty"), "kept through death");
        player.discard();
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void gangHuntersAreTaggedProvokedAndSentAfterThePlayer(GameTestHelper h) {
        floor(h);
        ServerPlayer player = player(h, h.absolutePos(new BlockPos(2, 1, 2)));
        UUID id = UUID.randomUUID();
        List<Mob> squad = Bounties.spawnSquad(h.getLevel(), h.absolutePos(new BlockPos(10, 1, 10)), player,
                Bounties.gangTarget("Rust Dogs"), 3, id);
        h.assertTrue(squad.size() == 3, "three hunters: " + squad.size());
        for (Mob mob : squad) {
            h.assertTrue("Rust Dogs".equals(mob.getPersistentData().getString(Bounties.GANG_TAG)), "hunts for its gang");
            h.assertTrue(Bounties.gangOf(h.getLevel(), mob).equals("Rust Dogs"), "patches name the hunted-for gang");
            CompoundTag state = mob.getPersistentData().getCompound("LazarusHumanEncounter").getCompound(player.getUUID().toString());
            h.assertTrue(state.getBoolean("Provoked"), "attacks without the toll warning");
            h.assertTrue(NpcApi.assignment(mob).isPresent(), "travelling after the player");
            h.assertTrue(mob.isPersistenceRequired(), "does not despawn mid-hunt");
        }
        squad.forEach(Mob::discard);
        player.discard();
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void factionHuntersJoinTheirFaction(GameTestHelper h) {
        floor(h);
        ServerPlayer player = player(h, h.absolutePos(new BlockPos(2, 1, 2)));
        for (String faction : new String[] {"wardens", "rovers", "military"}) {
            List<Mob> squad = Bounties.spawnSquad(h.getLevel(), h.absolutePos(new BlockPos(10, 1, 10)), player,
                    Bounties.factionTarget(faction), 2, UUID.randomUUID());
            h.assertTrue(squad.size() == 2, faction + " squad: " + squad.size());
            for (Mob mob : squad)
                h.assertTrue(NpcApi.identity(mob).map(identity -> faction.equals(identity.faction())).orElse(false),
                        faction + " hunter belongs to " + faction);
            squad.forEach(Mob::discard);
        }
        player.discard();
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void trailStepsTowardThePlayer(GameTestHelper h) {
        floor(h);
        BlockPos from = h.absolutePos(new BlockPos(1, 1, 1)), to = h.absolutePos(new BlockPos(13, 1, 13));
        List<BlockPos> trail = Bounties.trail(h.getLevel(), from, to);
        h.assertTrue(trail.get(trail.size() - 1).equals(to), "ends at the player");
        BlockPos last = from;
        for (BlockPos point : trail) {
            h.assertTrue(Math.sqrt(point.distSqr(last)) <= 24, "steps of about 16 blocks");
            last = point;
        }
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void rivalsPayForDownedSquads(GameTestHelper h) {
        h.assertTrue(Bounties.rewards(Bounties.factionTarget("rovers")).equals(java.util.Map.of("wardens", Bounties.RIVAL_REWARD)), "Wardens pay for Rovers");
        h.assertTrue(Bounties.rewards(Bounties.factionTarget("wardens")).equals(java.util.Map.of("rovers", Bounties.RIVAL_REWARD)), "Rovers pay for Wardens");
        h.assertTrue(Bounties.rewards(Bounties.factionTarget("military")).isEmpty(), "the Military has no rival");
        h.assertTrue(Bounties.rewards(Bounties.gangTarget("Red Tape")).equals(java.util.Map.of("wardens", Bounties.GANG_REWARD)), "Wardens pay for gang hunters");
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void leaderDropsThePosterAndDownedSquadPaysTheWardens(GameTestHelper h) {
        floor(h);
        ServerPlayer player = player(h, h.absolutePos(new BlockPos(2, 1, 2)));
        UUID id = UUID.randomUUID();
        String target = Bounties.gangTarget("Rust Dogs");
        List<Mob> squad = Bounties.spawnSquad(h.getLevel(), h.absolutePos(new BlockPos(10, 1, 10)), player, target, 2, id);
        h.assertTrue(squad.size() == 2, "two hunters");
        Bounties.record(h.getLevel(), player, target, squad, id);
        int before = NpcApi.reputation(player, "wardens");
        Mob leader = squad.get(0);
        BlockPos at = leader.blockPosition();
        leader.hurt(player.damageSources().playerAttack(player), 1000);
        Bounties.hunterDown(h.getLevel(), leader, player); // a test player is not in the server list the listener reads
        List<net.minecraft.world.entity.item.ItemEntity> drops = h.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(3));
        h.assertTrue(drops.stream().anyMatch(item -> item.getItem().hasTag() && item.getItem().getTag().contains("LazarusWanted")
                && item.getItem().getHoverName().getString().contains("BountyTest")), "the leader carries a wanted poster");
        h.assertTrue(Bounties.status(player).stream().anyMatch(line -> line.contains("Active hunt")), "one hunter left: still hunted");
        squad.get(1).hurt(player.damageSources().playerAttack(player), 1000);
        Bounties.hunterDown(h.getLevel(), squad.get(1), player);
        h.assertTrue(Bounties.status(player).stream().noneMatch(line -> line.contains("Active hunt")), "squad down ends the hunt");
        h.assertTrue(NpcApi.reputation(player, "wardens") == before + Bounties.GANG_REWARD,
                "Wardens pay " + Bounties.GANG_REWARD + ": " + NpcApi.reputation(player, "wardens"));
        h.assertTrue(Bounties.status(player).stream().anyMatch(line -> line.contains("squads taken down: 1")), "counted");
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        player.discard();
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void pardonPriceFollowsDepthAndPostersCutIt(GameTestHelper h) {
        h.assertTrue(Bounties.price(-45, 0).equals(new Bounties.Price(4, 2, 0)), "light bounty");
        h.assertTrue(Bounties.price(-75, 0).equals(new Bounties.Price(6, 4, 0)), "heavy bounty");
        h.assertTrue(Bounties.price(-95, 0).equals(new Bounties.Price(8, 6, 0)), "worst bounty");
        h.assertTrue(Bounties.price(-45, 1).equals(new Bounties.Price(2, 1, 1)), "a poster knocks off 2 food and 1 iron");
        h.assertTrue(Bounties.price(-45, 5).equals(new Bounties.Price(0, 0, 2)), "only the posters needed are used");
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void havenClearsAGangBountyOnceEveryFewDays(GameTestHelper h) {
        ServerPlayer player = player(h, h.absolutePos(new BlockPos(2, 1, 2)));
        String target = Bounties.gangTarget("Red Tape");
        Bounties.setGangStanding(player, "Red Tape", -60);
        h.assertTrue(Bounties.pardon(player, target).contains("costs 4 canned food, 2 iron"), "too poor: told the price");
        h.assertTrue(Bounties.gangStanding(player, "Red Tape") == -60, "nothing changes unpaid");
        CompoundTag poster = new CompoundTag();
        poster.putString("Player", "BountyTest");
        poster.putString("Target", target);
        player.getInventory().add(Bounties.poster(poster));
        player.getInventory().add(ApiSupport.stack(Bounties.FOOD, 2));
        player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 1));
        h.assertTrue(Bounties.pardon(player, target).contains("off their list"), "the poster covers half");
        h.assertTrue(Bounties.gangStanding(player, "Red Tape") == Bounties.PARDONED, "back to just above enemy");
        h.assertTrue(!Bounties.wanted(player).containsKey(target), "no longer wanted");
        h.assertTrue(Bounties.posters(player, target) == 0 && player.getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT) == 0,
                "paid with the poster, food and iron");
        Bounties.setGangStanding(player, "Red Tape", -60);
        h.assertTrue(Bounties.pardon(player, target).contains("not long ago"), "one pardon per group every few days");
        player.discard();
        h.succeed();
    }

    @GameTest(template = "territory_arena", timeoutTicks = 20)
    public static void havenClearsAFactionBounty(GameTestHelper h) {
        ServerPlayer player = player(h, h.absolutePos(new BlockPos(2, 1, 2)));
        for (int i = 0; i < 9; i++) NpcApi.adjustReputation(player, "wardens", -10, Bounties.PARDON, null); // -90: a deep bounty
        h.assertTrue(Bounties.wanted(player).containsKey(Bounties.factionTarget("wardens")), "wanted by the Wardens");
        player.getInventory().add(ApiSupport.stack(Bounties.FOOD, 8));
        player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 6));
        h.assertTrue(Bounties.pardon(player, Bounties.factionTarget("wardens")).contains("off their list"), "paid the worst price");
        h.assertTrue(NpcApi.reputation(player, "wardens") == Bounties.PARDONED, "Wardens: " + NpcApi.reputation(player, "wardens"));
        player.discard();
        h.succeed();
    }

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y < 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos at) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "BountyTest"));
        p.setGameMode(GameType.SURVIVAL);
        p.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        return p;
    }
}
