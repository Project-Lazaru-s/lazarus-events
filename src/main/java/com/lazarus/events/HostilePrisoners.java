package com.lazarus.events;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.HostileGroups;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjectives;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * What a player can do with a surrendered looter or deserter beyond the usual choices: interrogate them for their
 * group's hideout (placed now, tracked in the journal), or send them to start over at the Haven refuge.
 */
final class HostilePrisoners {
    static final ResourceLocation TASK = ApiSupport.id("hideout_lead");
    private static final ResourceLocation SPARED = ApiSupport.id("prisoner_sent_to_refuge");
    static final int MIN_DISTANCE = 80, MAX_DISTANCE = 160;

    private HostilePrisoners() {}

    static void register() {
        ApiSupport.log("hideout lead task", NpcApi.registerTask(new TaskDefinition(TASK, Component.literal("Find the hideout"),
                Component.literal("A prisoner gave up where their group hides. Reach the place; what you do there is up to you."),
                TaskObjectives.reach(24), 48_000, TaskDefinition.DeathPolicy.PERSIST, false, false, List.of(), null, 0)));
    }

    /** The hideout a prisoner of this kind knows about. */
    static String hideoutType(String kind) {
        if ("soldier".equals(kind)) return HostileHideouts.DESERTER_CAMP;
        return ThreadLocalRandom.current().nextBoolean() ? HostileHideouts.GANG_HIDEOUT : BuildingHideout.TYPE;
    }

    private static void say(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(style -> style.withColor(0xD4C068)));
    }

    static final class Listener {
        @SubscribeEvent
        public void choices(NpcApiEvents.PrisonerChoices event) {
            event.offer(NpcApiEvents.PrisonerChoices.INTERROGATE);
            if (HavenRefuge.hasRoom(event.getPlayer().serverLevel())) event.offer(NpcApiEvents.PrisonerChoices.REFUGE);
        }

        @SubscribeEvent
        public void interrogated(NpcApiEvents.PrisonerInterrogated event) {
            ServerPlayer player = event.getPlayer();
            ServerLevel level = player.serverLevel();
            String type = hideoutType(event.getKind());
            LazarusEvents.Revealed hideout = LazarusEvents.reveal(level, player, type, MIN_DISTANCE, MAX_DISTANCE);
            if (hideout == null) {
                say(player, "\"I don't know anything. The others moved on, I swear.\" (No hideout could be placed nearby.)");
                return;
            }
            String who = HostileHideouts.DESERTER_CAMP.equals(type) ? "the rest of my unit"
                    : "the " + HostileGroups.gang(level, hideout.pos());
            say(player, "\"Alright, alright! " + who + " hole up " + FactionMissions.direction(player.blockPosition(), hideout.pos())
                    + " of here, maybe " + Math.round(Math.sqrt(player.blockPosition().distSqr(hideout.pos())) / 10) * 10 + " blocks.\"");
            ApiResult offer = NpcApi.offerTask(player, event.getNpc(), TASK, hideout.id(), GlobalPos.of(level.dimension(), hideout.pos()));
            if (!offer.ok()) say(player, "The place is near X " + Math.round(hideout.pos().getX() / 25f) * 25
                    + ", Z " + Math.round(hideout.pos().getZ() / 25f) * 25 + ".");
        }

        @SubscribeEvent
        public void sentAway(NpcApiEvents.PrisonerSentAway event) {
            ServerPlayer player = event.getPlayer();
            if (!HavenRefuge.welcomeFormer(player.serverLevel())) return;
            NpcApi.adjustReputation(player, HavenRefuge.FACTION, 2, SPARED, UUID.randomUUID());
            say(player, "They lay down their colours and head for the Haven refuge to start over.");
        }
    }
}
