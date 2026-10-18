package com.lazarus.events;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

final class EventData extends SavedData {
    private static final String NAME = "lazarus_events";
    long nextAttempt;
    String lastResult = "No attempt yet";
    final List<Encounter> encounters = new ArrayList<>();

    static EventData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(EventData::load, EventData::new, NAME);
    }

    private static EventData load(CompoundTag tag) {
        EventData data = new EventData();
        data.nextAttempt = tag.getLong("NextAttempt");
        data.lastResult = tag.getString("LastResult");
        for (Tag entry : tag.getList("Encounters", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) entry;
            data.encounters.add(new Encounter(saved.getUUID("Id"), saved.getString("Type"),
                    BlockPos.of(saved.getLong("Pos")), saved.getLong("Created"),
                    saved.getBoolean("Discovered")));
        }
        return data;
    }

    Encounter add(UUID id, String type, BlockPos pos, long now) {
        Encounter encounter = new Encounter(id, type, pos.immutable(), now, false);
        encounters.add(encounter);
        setDirty();
        return encounter;
    }

    boolean remove(UUID id) {
        boolean removed = encounters.removeIf(encounter -> encounter.id.equals(id));
        if (removed) setDirty();
        return removed;
    }

    void prune(long now) {
        boolean changed = false;
        Iterator<Encounter> iterator = encounters.iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().created > 168000) {
                iterator.remove();
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    boolean near(BlockPos pos) {
        for (Encounter encounter : encounters) {
            if (encounter.pos.distSqr(pos) < 128 * 128) return true;
        }
        return false;
    }

    void result(String result) {
        lastResult = result;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("NextAttempt", nextAttempt);
        tag.putString("LastResult", lastResult);
        ListTag saved = new ListTag();
        for (Encounter encounter : encounters) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", encounter.id);
            entry.putString("Type", encounter.type);
            entry.putLong("Pos", encounter.pos.asLong());
            entry.putLong("Created", encounter.created);
            entry.putBoolean("Discovered", encounter.discovered);
            saved.add(entry);
        }
        tag.put("Encounters", saved);
        return tag;
    }

    static final class Encounter {
        final UUID id;
        final String type;
        final BlockPos pos;
        final long created;
        boolean discovered;

        Encounter(UUID id, String type, BlockPos pos, long created, boolean discovered) {
            this.id = id;
            this.type = type;
            this.pos = pos;
            this.created = created;
            this.discovered = discovered;
        }
    }
}
