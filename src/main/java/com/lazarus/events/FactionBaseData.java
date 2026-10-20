package com.lazarus.events;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Permanent base records are deliberately separate from seven-day encounter data. */
final class FactionBaseData extends SavedData {
    static final int VERSION = 2;
    private static final String NAME = "lazarus_faction_bases";
    final List<Base> bases = new ArrayList<>();
    /** Preserve records from a temporarily missing faction profile when this save is written again. */
    final List<CompoundTag> unavailableBases = new ArrayList<>();
    final Set<Long> pending = new LinkedHashSet<>();
    /** Not saved: after a restart an unloaded candidate simply gets four new tries. */
    private final java.util.Map<Long, Integer> retries = new java.util.HashMap<>();

    static FactionBaseData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FactionBaseData::load, FactionBaseData::new, NAME);
    }

    private static FactionBaseData load(CompoundTag tag) {
        FactionBaseData data = new FactionBaseData();
        for (long chunk : tag.getLongArray("Pending")) data.pending.add(chunk);
        for (Tag element : tag.getList("Bases", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) element;
            if (!saved.hasUUID("Id") || !saved.contains("Pos", Tag.TAG_LONG)) continue;
            String faction = saved.getString("Faction");
            if (BaseRoster.profile(faction) == null) {
                data.unavailableBases.add(saved.copy());
                continue;
            }
            Base base = new Base(saved.getUUID("Id"), faction, BlockPos.of(saved.getLong("Pos")),
                    Math.floorMod(saved.getInt("Rotation"), 4), saved.getBoolean("Discovered"),
                    saved.contains("Origin") ? saved.getString("Origin") : "legacy");
            base.model = saved.contains("ModelVersion", Tag.TAG_INT) ? saved.getInt("ModelVersion") : 1;
            base.lastStaffCheck = saved.getLong("LastStaffCheck");
            base.leaderReadyAt = saved.getLong("LeaderReadyAt");
            base.stock = Math.max(0, Math.min(100, saved.contains("Stock", Tag.TAG_INT) ? saved.getInt("Stock") : 100));
            int legacySlot = 0;
            for (Tag member : saved.getList("Staff", Tag.TAG_COMPOUND)) {
                CompoundTag entry = (CompoundTag) member;
                BaseRoster.Layout layout = BaseRoster.layout(base);
                int slot = entry.contains("Slot", Tag.TAG_STRING) ? layout.index(entry.getString("Slot")) : legacySlot;
                if (slot >= 0 && slot < layout.slots().length && entry.hasUUID("Id")) {
                    while (base.staff.size() <= slot) base.staff.add(new UUID(0, 0));
                    base.staff.set(slot, entry.getUUID("Id"));
                }
                legacySlot++;
            }
            if (base.staff.size() < BaseRoster.layout(base).slots().length) base.lastStaffCheck = 0;
            data.bases.add(base);
        }
        return data;
    }

    /** Keeps a candidate chunk for a later cycle, at most four times. */
    boolean retry(long chunk) {
        int tries = retries.merge(chunk, 1, Integer::sum);
        if (tries > 4) { retries.remove(chunk); return false; }
        pending.remove(chunk);
        pending.add(chunk);
        return true;
    }

    Base add(String faction, BlockPos pos, int rotation, String origin) {
        Base base = new Base(UUID.randomUUID(), faction, pos.immutable(), rotation, false, origin);
        bases.add(base);
        setDirty();
        return base;
    }

    Base faction(String faction) {
        return bases.stream().filter(base -> base.faction.equals(faction)).findFirst().orElse(null);
    }

    Base near(BlockPos pos, double radius) {
        return bases.stream().filter(base -> base.pos.distSqr(pos) <= radius * radius).findFirst().orElse(null);
    }

    boolean occupiedNear(BlockPos pos, double radius) {
        return near(pos, radius) != null || unavailableBases.stream().anyMatch(saved ->
                saved.contains("Pos", Tag.TAG_LONG) && BlockPos.of(saved.getLong("Pos")).distSqr(pos) <= radius * radius);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("DataVersion", VERSION);
        tag.putLongArray("Pending", pending.stream().mapToLong(Long::longValue).toArray());
        ListTag list = new ListTag();
        for (Base base : bases) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", base.id);
            entry.putString("Faction", base.faction);
            entry.putString("Dimension", "minecraft:overworld");
            entry.putString("Origin", base.origin);
            entry.putString("State", "operational");
            BaseRoster.Layout layout = BaseRoster.layout(base);
            entry.putInt("ModelVersion", base.model);
            entry.putInt("Width", layout.width());
            entry.putInt("Depth", layout.depth());
            entry.putLong("Pos", base.pos.asLong());
            entry.putInt("Rotation", base.rotation);
            entry.putBoolean("Discovered", base.discovered);
            entry.putLong("LastStaffCheck", base.lastStaffCheck);
            entry.putLong("LeaderReadyAt", base.leaderReadyAt);
            entry.putInt("RosterCapacity", BaseRoster.MAX_CAPACITY);
            entry.putInt("Stock", base.stock);
            ListTag staff = new ListTag();
            for (int i = 0; i < base.staff.size(); i++) {
                CompoundTag member = new CompoundTag();
                member.putUUID("Id", base.staff.get(i));
                BaseRoster.Slot[] slots = BaseRoster.layout(base).slots();
                if (i < slots.length) member.putString("Slot", slots[i].id());
                staff.add(member);
            }
            entry.put("Staff", staff);
            list.add(entry);
        }
        for (CompoundTag unavailable : unavailableBases) list.add(unavailable.copy());
        tag.put("Bases", list);
        return tag;
    }

    static final class Base {
        final UUID id;
        final String faction;
        final BlockPos pos;
        final int rotation;
        final String origin;
        final List<UUID> staff = new ArrayList<>();
        boolean discovered;
        long lastStaffCheck;
        long leaderReadyAt;
        int stock = 100;
        int model = BaseRoster.CURRENT_MODEL;

        Base(UUID id, String faction, BlockPos pos, int rotation, boolean discovered, String origin) {
            this.id = id;
            this.faction = faction;
            this.pos = pos;
            this.rotation = rotation;
            this.discovered = discovered;
            this.origin = origin;
        }
    }
}
