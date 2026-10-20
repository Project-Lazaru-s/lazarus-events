package com.lazarus.events;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Only one temporary faction patrol is scheduled at a time. */
final class FactionExpeditionData extends SavedData {
    private static final String NAME = "lazarus_faction_expeditions";
    long nextAttempt;
    UUID activeId;
    long expires;
    long[] route = new long[0];
    final List<UUID> members = new ArrayList<>();
    final List<UUID> retired = new ArrayList<>();

    static FactionExpeditionData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FactionExpeditionData::load, FactionExpeditionData::new, NAME);
    }

    private static FactionExpeditionData load(CompoundTag tag) {
        FactionExpeditionData data = new FactionExpeditionData();
        data.nextAttempt = tag.getLong("NextAttempt");
        if (tag.hasUUID("ActiveId")) data.activeId = tag.getUUID("ActiveId");
        data.expires = tag.getLong("Expires");
        data.route = tag.getLongArray("Route");
        long[] ids = tag.getLongArray("Members");
        for (int i = 0; i < ids.length / 2; i++) {
            data.members.add(new UUID(ids[i * 2], ids[i * 2 + 1]));
        }
        long[] old = tag.getLongArray("Retired");
        for (int i = 0; i < old.length / 2; i++) data.retired.add(new UUID(old[i * 2], old[i * 2 + 1]));
        return data;
    }

    void retire() {
        if (activeId != null) {
            retired.add(activeId);
            if (retired.size() > 16) retired.remove(0);
        }
        activeId = null;
        members.clear();
        route = new long[0];
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("DataVersion", 1);
        tag.putLong("NextAttempt", nextAttempt);
        if (activeId != null) tag.putUUID("ActiveId", activeId);
        tag.putLong("Expires", expires);
        tag.putLongArray("Route", route);
        long[] ids = new long[members.size() * 2];
        for (int i = 0; i < members.size(); i++) {
            ids[i * 2] = members.get(i).getMostSignificantBits();
            ids[i * 2 + 1] = members.get(i).getLeastSignificantBits();
        }
        tag.putLongArray("Members", ids);
        long[] old = new long[retired.size() * 2];
        for (int i = 0; i < retired.size(); i++) {
            old[i * 2] = retired.get(i).getMostSignificantBits();
            old[i * 2 + 1] = retired.get(i).getLeastSignificantBits();
        }
        tag.putLongArray("Retired", old);
        return tag;
    }
}
