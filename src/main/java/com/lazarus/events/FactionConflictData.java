package com.lazarus.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** One loaded, bounded skirmish; no combat is simulated while the area is unloaded. */
final class FactionConflictData extends SavedData {
    private static final String NAME = "lazarus_faction_conflict";
    long nextAttempt;
    Conflict active;
    String lastResult = "No skirmish yet";
    final List<PendingReward> rewards = new ArrayList<>();

    static FactionConflictData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FactionConflictData::load, FactionConflictData::new, NAME);
    }

    private static FactionConflictData load(CompoundTag tag) {
        FactionConflictData data = new FactionConflictData();
        data.nextAttempt = tag.getLong("NextAttempt");
        data.lastResult = tag.getString("LastResult");
        for (Tag reward : tag.getList("Rewards", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) reward;
            if (entry.hasUUID("Player") && entry.hasUUID("Fight"))
                data.rewards.add(new PendingReward(entry.getUUID("Player"), entry.getUUID("Fight"), entry.getString("Faction")));
        }
        if (tag.contains("Active", Tag.TAG_COMPOUND)) {
            CompoundTag saved = tag.getCompound("Active");
            if (saved.hasUUID("Id") && saved.contains("Pos", Tag.TAG_LONG)) {
                Conflict conflict = new Conflict(saved.getUUID("Id"), BlockPos.of(saved.getLong("Pos")),
                        saved.getLong("Started"), saved.getBoolean("Testing"),
                        saved.contains("FirstFaction") ? saved.getString("FirstFaction") : "rovers",
                        saved.contains("SecondFaction") ? saved.getString("SecondFaction") : "wardens");
                for (Tag member : saved.getList(saved.contains("FirstMembers") ? "FirstMembers" : "Rovers", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) member;
                    if (entry.hasUUID("Id")) conflict.firstMembers.add(entry.getUUID("Id"));
                }
                for (Tag member : saved.getList(saved.contains("SecondMembers") ? "SecondMembers" : "Wardens", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) member;
                    if (entry.hasUUID("Id")) conflict.secondMembers.add(entry.getUUID("Id"));
                }
                for (Tag participant : saved.getList("Participants", Tag.TAG_COMPOUND)) {
                    CompoundTag entry = (CompoundTag) participant;
                    if (entry.hasUUID("Player")) conflict.participants.put(entry.getUUID("Player"), entry.getString("Side"));
                }
                data.active = conflict;
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("DataVersion", 2);
        tag.putLong("NextAttempt", nextAttempt);
        tag.putString("LastResult", lastResult);
        ListTag rewardTags = new ListTag();
        for (PendingReward reward : rewards) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", reward.player);
            entry.putUUID("Fight", reward.fight);
            entry.putString("Faction", reward.faction);
            rewardTags.add(entry);
        }
        tag.put("Rewards", rewardTags);
        if (active != null) {
            CompoundTag saved = new CompoundTag();
            saved.putUUID("Id", active.id);
            saved.putLong("Pos", active.pos.asLong());
            saved.putLong("Started", active.started);
            saved.putBoolean("Testing", active.testing);
            saved.putString("FirstFaction", active.firstFaction);
            saved.putString("SecondFaction", active.secondFaction);
            saved.put("FirstMembers", uuids(active.firstMembers));
            saved.put("SecondMembers", uuids(active.secondMembers));
            ListTag participants = new ListTag();
            active.participants.forEach((player, side) -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("Player", player);
                entry.putString("Side", side);
                participants.add(entry);
            });
            saved.put("Participants", participants);
            tag.put("Active", saved);
        }
        return tag;
    }

    private static ListTag uuids(List<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            list.add(entry);
        }
        return list;
    }

    static final class Conflict {
        final UUID id;
        final BlockPos pos;
        final long started;
        final boolean testing;
        final String firstFaction;
        final String secondFaction;
        final List<UUID> firstMembers = new ArrayList<>();
        final List<UUID> secondMembers = new ArrayList<>();
        final Map<UUID, String> participants = new HashMap<>();

        Conflict(UUID id, BlockPos pos, long started, boolean testing) {
            this(id, pos, started, testing, "rovers", "wardens");
        }

        Conflict(UUID id, BlockPos pos, long started, boolean testing, String firstFaction, String secondFaction) {
            this.id = id;
            this.pos = pos;
            this.started = started;
            this.testing = testing;
            this.firstFaction = firstFaction;
            this.secondFaction = secondFaction;
        }
    }

    record PendingReward(UUID player, UUID fight, String faction) {}
}
