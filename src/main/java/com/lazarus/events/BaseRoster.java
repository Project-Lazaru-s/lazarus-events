package com.lazarus.events;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Map;
import com.lazarus.npcs.api.NpcFactions;
import com.lazarus.npcs.api.ai.NightDuty;

/** Base geometry and garrison slots. Saved staff entries carry the slot id, so slots may only be appended. */
final class BaseRoster {
    static final int MAX_CAPACITY = 10;
    /** Model placed by new bases; older saves keep the layout they were built with. */
    static final int CURRENT_MODEL = 2;

    private BaseRoster() {}

    /** WAIT stays at the post, SENTRY guards it without moving far, PATROL walks the slot's route. */
    enum Duty { WAIT, SENTRY, PATROL }

    record Slot(String id, String profession, int[] post, Duty duty, int[][] route,
                int[][] activities, NightDuty night, int[] bed) {
        boolean guard() { return duty != Duty.WAIT; }
    }

    /**
     * Template geometry in template x/z (y=1 is the first block above the floor). anchorX/anchorZ is the template
     * cell under the saved base position. Values for model 2 come from tools/generate_faction_bases.py.
     */
    record Layout(ResourceLocation template, int width, int depth, int height, int anchorX, int anchorZ,
                  Slot[] slots, int[] depot, int[][] barrels, int[] shelter, int[][] defensePosts) {
        Layout(ResourceLocation template, int width, int depth, int height, int anchorX, int anchorZ,
               Slot[] slots, int[] depot, int[][] barrels) {
            this(template, width, depth, height, anchorX, anchorZ, slots, depot, barrels,
                    new int[] {anchorX, anchorZ}, new int[][] {{anchorX, anchorZ}});
        }
        BlockPos start(BlockPos anchor) { return anchor.offset(-anchorX, -1, -anchorZ); }
        BlockPos at(BlockPos anchor, int[] cell) { return anchor.offset(cell[0] - anchorX, 0, cell[1] - anchorZ); }

        int index(String slot) {
            for (int i = 0; i < slots.length; i++) if (slots[i].id().equals(slot)) return i;
            return -1;
        }
    }

    private static Slot waits(String id, String profession, int x, int z) {
        return new Slot(id, profession, new int[] {x, z}, Duty.WAIT, null,
                new int[][] {{x, z}, {x + 1, z}, {x, z + 1}}, NightDuty.SHELTER, null);
    }

    private static Slot sleep(Slot slot, int x, int z) {
        return new Slot(slot.id(), slot.profession(), slot.post(), slot.duty(), slot.route(),
                slot.activities(), NightDuty.SLEEP, new int[] {x, z});
    }

    private static Slot sentry(String id, int x, int z) {
        return new Slot(id, "guard", new int[] {x, z}, Duty.SENTRY, null,
                new int[][] {{x, z}}, NightDuty.WATCH, null);
    }

    private static Slot watch(String id, String profession, int x, int z) {
        return new Slot(id, profession, new int[] {x, z}, Duty.SENTRY, null,
                new int[][] {{x, z}}, NightDuty.WATCH, null);
    }

    private static Slot patrol(String id, int x, int z, int[][] route) {
        return new Slot(id, "guard", new int[] {x, z}, Duty.PATROL, route,
                route, NightDuty.WATCH, null);
    }

    private static final Layout ROVERS_V1 = new Layout(ApiSupport.id("rovers_base"), 14, 12, 5, 7, 6, new Slot[] {
            waits("leader", "leader", 6, 4),
            patrol("guard_a", 4, 4, new int[][] {{4, 4}, {4, 6}, {6, 8}, {6, 4}}),
            patrol("guard_b", 9, 4, new int[][] {{9, 4}, {9, 6}, {7, 8}, {7, 4}}),
            waits("armorer", "armorer", 5, 8),
            waits("trader", "trader", 9, 8)},
            new int[] {6, 1}, new int[][] {{6, 1}, {7, 1}, {4, 6}, {11, 6}});
    private static final Layout WARDENS_V1 = new Layout(ApiSupport.id("wardens_base"), 15, 13, 6, 7, 6, new Slot[] {
            waits("leader", "leader", 5, 3),
            patrol("guard_a", 4, 3, new int[][] {{4, 3}, {5, 4}, {6, 3}, {5, 2}}),
            patrol("guard_b", 10, 3, new int[][] {{10, 3}, {9, 4}, {8, 3}, {9, 2}}),
            waits("armorer", "armorer", 5, 7),
            waits("trader", "trader", 9, 7)},
            new int[] {5, 8}, new int[][] {{5, 8}, {6, 8}});
    /** Nomadic scavengers: more hands for trade and travel, a lighter watch. */
    private static final Layout ROVERS_V2 = new Layout(ApiSupport.id("rovers_camp"), 23, 21, 8, 11, 10, new Slot[] {
            sleep(waits("leader", "leader", 7, 4), 5, 3),
            patrol("guard_a", 11, 18, new int[][] {{11, 18}, {8, 15}, {11, 13}, {14, 16}}),
            patrol("guard_b", 14, 7, new int[][] {{14, 7}, {21, 7}, {21, 12}, {14, 12}}),
            sleep(waits("armorer", "armorer", 4, 16), 16, 3),
            waits("trader", "trader", 18, 16),
            sentry("guard_c", 19, 11),
            waits("trader_b", "trader", 20, 16),
            sleep(waits("medic", "medic", 18, 4), 16, 5),
            watch("scout_a", "expedition_soldier", 8, 10),
            watch("scout_b", "expedition_soldier", 15, 10)},
            new int[] {2, 9}, new int[][] {{2, 9}, {4, 4}, {16, 14}, {20, 14}, {19, 19}},
            new int[] {18, 4}, new int[][] {{11, 18}, {19, 11}});
    /** Disciplined militia: a heavier watch on the gate, one trader. */
    private static final Layout WARDENS_V2 = new Layout(ApiSupport.id("wardens_fort"), 25, 23, 9, 12, 11, new Slot[] {
            sleep(waits("leader", "leader", 12, 3), 14, 3),
            patrol("guard_a", 12, 19, new int[][] {{12, 19}, {6, 17}, {12, 15}, {18, 17}}),
            patrol("guard_b", 12, 8, new int[][] {{12, 8}, {16, 11}, {12, 15}, {8, 11}}),
            sleep(waits("armorer", "armorer", 20, 3), 2, 3),
            waits("trader", "trader", 21, 12),
            sentry("guard_c", 9, 19),
            sentry("guard_d", 15, 19),
            sleep(waits("medic", "medic", 4, 4), 6, 3),
            watch("scout_a", "expedition_soldier", 9, 8),
            watch("scout_b", "expedition_soldier", 15, 8)},
            new int[] {2, 12}, new int[][] {{2, 12}, {10, 5}, {18, 11}, {23, 14}},
            new int[] {5, 4}, new int[][] {{12, 19}, {12, 8}});

    /** A palisaded civilian refuge: medics, a trader and a light watch. Appended guest slots come later. */
    private static final Layout HAVEN = new Layout(ApiSupport.id("haven_refuge"), 21, 19, 6, 10, 9, new Slot[] {
            sleep(waits("leader", "leader", 5, 4), 3, 4),
            patrol("guard_a", 10, 14, new int[][] {{10, 14}, {3, 16}, {10, 16}, {18, 8}}),
            sentry("guard_b", 10, 16),
            waits("trader", "trader", 16, 16),
            waits("medic", "medic", 15, 11),
            sleep(waits("medic_b", "medic", 16, 12), 14, 4)},
            new int[] {6, 5}, new int[][] {{6, 5}, {14, 15}, {18, 15}, {16, 3}},
            new int[] {15, 4}, new int[][] {{10, 16}, {10, 14}});

    record Profile(String faction, Layout legacy, Layout current, int bread) {
        Profile {
            if (legacy == null) legacy = current;
            NpcFactions.Definition definition = NpcFactions.get(faction);
            if (definition == null || !definition.professions() || definition.survivorVariants().isEmpty()
                    || legacy.slots().length > MAX_CAPACITY || current.slots().length > MAX_CAPACITY
                    || !valid(legacy) || !valid(current))
                throw new IllegalArgumentException("Invalid base profile for " + faction);
        }
        Layout layout(int model) { return model <= 1 ? legacy : current; }
    }

    private static boolean valid(Layout layout) {
        if (!inside(layout, layout.shelter())) return false;
        for (int[] post : layout.defensePosts()) if (!inside(layout, post)) return false;
        for (Slot slot : layout.slots()) {
            if (!inside(layout, slot.post()) || slot.activities().length == 0
                    || (slot.night() == NightDuty.SLEEP && (slot.bed() == null || !inside(layout, slot.bed())))) return false;
            for (int[] point : slot.activities()) if (!inside(layout, point)) return false;
        }
        return true;
    }

    private static boolean inside(Layout layout, int[] cell) {
        return cell != null && cell.length == 2 && cell[0] >= 0 && cell[0] < layout.width()
                && cell[1] >= 0 && cell[1] < layout.depth();
    }

    private static final List<Profile> PROFILES = List.of(
            new Profile("rovers", ROVERS_V1, ROVERS_V2, 3),
            new Profile("wardens", WARDENS_V1, WARDENS_V2, 2),
            new Profile("haven", null, HAVEN, 4));
    private static final Map<String, Profile> BY_ID = PROFILES.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Profile::faction, profile -> profile));
    private static final List<String> FACTIONS = PROFILES.stream().map(Profile::faction).toList();

    static List<String> factions() { return FACTIONS; }
    static Profile profile(String faction) { return BY_ID.get(faction); }
    static Layout layout(String faction, int model) {
        Profile profile = profile(faction);
        if (profile == null) throw new IllegalArgumentException("No base profile for faction " + faction);
        return profile.layout(model);
    }

    static Layout layout(FactionBaseData.Base base) { return layout(base.faction, base.model); }
}
