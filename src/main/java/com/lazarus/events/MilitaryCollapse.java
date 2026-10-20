package com.lazarus.events;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The military fades as the world ages: active checkpoints thin out and abandoned ones take their place. An abandoned
 * checkpoint is the same template with its supplies, a handful of infected soldiers and a log on a lectern.
 */
final class MilitaryCollapse {
    static final String ABANDONED = "abandoned";

    private MilitaryCollapse() {}

    /** Relative draw weights of {active, abandoned} checkpoints for a world day (an ordinary encounter weighs 1). */
    static double[] weights(int day, int militaryDay, int declineDay, int collapseDay) {
        if (day < militaryDay) return new double[] {0, 0};
        if (day < declineDay) return new double[] {1, 0};
        if (day < collapseDay) return new double[] {0.5, 1};
        return new double[] {0.15, 1.5};
    }

    /** Infected soldiers left at the post; their gear rarely drops. */
    static int infest(ServerLevel level, BlockPos origin, int rotation, UUID encounterId) {
        int[][] spots = {{5, 9}, {10, 9}, {15, 4}, {17, 4}};
        int count = 2 + ThreadLocalRandom.current().nextInt(3), spawned = 0;
        for (int i = 0; i < count; i++) {
            BlockPos at = EncounterScenery.militaryPosition(origin, rotation, spots[i][0], 1, spots[i][1]);
            if (!(LazarusEvents.spawn(level, at, "minecraft:zombie", encounterId, false) instanceof Mob zombie)) continue;
            equip(zombie, EquipmentSlot.HEAD, "apocalypsenow:usa_armor_helmet");
            equip(zombie, EquipmentSlot.CHEST, "apocalypsenow:military_bulletprooft_vest_chestplate");
            equip(zombie, EquipmentSlot.LEGS, "apocalypsenow:usa_armor_leggings");
            spawned++;
        }
        return spawned;
    }

    private static void equip(Mob mob, EquipmentSlot slot, String item) {
        ItemStack stack = ApiSupport.stack(item, 1);
        if (stack.isEmpty()) return;
        mob.setItemSlot(slot, stack);
        mob.setDropChance(slot, 0.05f);
    }

    /** Places a lectern with a random duty log in the middle of the post; false when there is no room for it. */
    static boolean log(ServerLevel level, BlockPos origin, int rotation) {
        BlockPos at = EncounterScenery.militaryPosition(origin, rotation, 11, 1, 6);
        if (!level.getBlockState(at).isAir() || !EncounterScenery.supported(level, at.below())) return false;
        BlockState lectern = Blocks.LECTERN.defaultBlockState()
                .setValue(LecternBlock.FACING, Direction.from2DDataValue(rotation));
        if (!level.setBlock(at, lectern, 3)) return false;
        return LecternBlock.tryPlaceBook(null, level, at, lectern, book(ThreadLocalRandom.current().nextInt(LOGS.size())));
    }

    static ItemStack book(int index) { return written(LOGS.get(Math.floorMod(index, LOGS.size()))); }

    /** A signed book from {title, author, page...}. */
    static ItemStack written(String[] log) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", log[0]);
        tag.putString("author", log[1]);
        tag.putBoolean("resolved", true);
        ListTag pages = new ListTag();
        for (int i = 2; i < log.length; i++)
            pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(log[i]))));
        tag.put("pages", pages);
        return book;
    }

    /** Title, author, pages. */
    static final List<String[]> LOGS = List.of(
            new String[] {"Duty Log, Day 14", "Sgt. Hale",
                    "Evac buses left at dawn. We were told a second convoy would come for the stragglers.",
                    "No convoy. Radio says hold position. Hold it with what?"},
            new String[] {"Checkpoint Orders", "Lt. Moreno",
                    "Screen every civilian for bites. Anyone with a fever is turned away. No exceptions.",
                    "Command, I can't keep turning away children. Requesting new orders. (No reply logged.)"},
            new String[] {"Radio Transcript", "Signals",
                    "...all units fall back to the airfield. Repeat, all units...",
                    "That was four days ago. The airfield stopped answering yesterday."},
            new String[] {"Supply Count", "Cpl. Iwata",
                    "Rations: 3 days. Water: 2 days. Ammo: enough to make a point, not enough to win one.",
                    "Two men left last night with the truck. I hope they made it. I hope they rot."},
            new String[] {"Medical Note", "Medic Ruiz",
                    "Private Dunn was scratched on patrol. He says it's from the fence.",
                    "His fever started at noon. I locked the infirmary. God forgive me."},
            new String[] {"Last Entry", "Unknown",
                    "They came over the north fence at night. We fired until the guns went quiet.",
                    "If you're reading this, take what's left. We won't need it."});
}
