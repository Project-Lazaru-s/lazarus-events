package com.lazarus.events;

import java.util.List;
import java.util.UUID;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjectives;
import com.lazarus.npcs.api.task.TaskState;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The Haven refuge's own life: the leader's request of the day, and rescued survivors who move in as guests.
 * Guests fill the refuge's "none" slots by name and look; a guest who dies frees the slot for the next rescue.
 */
final class HavenRefuge {
    static final String FACTION = "haven", GUEST = "none";
    private static final String REQUEST_DAY = "LazarusEventsHavenRequestDay", RESCUED = "LazarusEventsRescued";
    private static final String LEAVE_AT = "LazarusEventsLeaveForRefuge";
    /** Kind, delivery and reward, in rotation order. */
    record Request(String kind, Component title, Component description, List<ItemStack> wanted, List<ItemStack> reward, int reputation) {
        ResourceLocation id() { return ApiSupport.id("haven_request_" + kind); }
    }

    private HavenRefuge() {}

    static List<Request> requests() {
        return List.of(
                new Request("food", Component.literal("Food for the refuge"),
                        Component.literal("The refuge is short on food. Bring four canned meals to the leader."),
                        ApiSupport.items(ApiSupport.stack("apocalypsenow:canned_food", 4)),
                        ApiSupport.items(ApiSupport.stack("apocalypsenow:antibiotics", 1)), 3),
                new Request("water", Component.literal("Water for the refuge"),
                        Component.literal("The well water needs boiling and there's no fuel. Bring three canned waters."),
                        ApiSupport.items(ApiSupport.stack("apocalypsenow:canned_water", 3)),
                        ApiSupport.items(ApiSupport.stack("apocalypsenow:bandage", 2)), 3),
                new Request("medicine", Component.literal("Medicine for the infirmary"),
                        Component.literal("The medics are out of supplies. Bring two bandages and a medical kit."),
                        ApiSupport.items(ApiSupport.stack("apocalypsenow:bandage", 2), ApiSupport.stack("apocalypsenow:medicalkit", 1)),
                        ApiSupport.items(ApiSupport.stack("apocalypsenow:canned_food", 2)), 4),
                new Request("cloth", Component.literal("Cloth for bedding"),
                        Component.literal("Winter is coming and the bunks have no blankets. Bring six pieces of cloth."),
                        ApiSupport.items(ApiSupport.stack("apocalypsenow:cloth", 6)),
                        ApiSupport.items(new ItemStack(net.minecraft.world.item.Items.WHEAT_SEEDS, 8),
                                ApiSupport.stack("apocalypsenow:canned_food", 1)), 2));
    }

    static void register() {
        for (Request request : requests())
            ApiSupport.log("haven request " + request.kind(), NpcApi.registerTask(new TaskDefinition(request.id(),
                    request.title(), request.description(), TaskObjectives.deliver(request.wanted()),
                    24_000, TaskDefinition.DeathPolicy.PERSIST, false, true, request.reward(), FACTION, request.reputation())));
    }

    static boolean refuge(FactionBaseData.Base base) { return FACTION.equals(base.faction); }

    /** The same request for everyone at a refuge on a given day. */
    static Request today(FactionBaseData.Base base, long day) {
        List<Request> all = requests();
        return all.get(Math.floorMod(day + base.id.hashCode(), all.size()));
    }

    static long day(ServerLevel level) { return level.getDayTime() / 24_000L; }

    static boolean doneToday(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        return persisted.contains(REQUEST_DAY) && persisted.getLong(REQUEST_DAY) == day(player.serverLevel());
    }

    static int rescued(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getInt(RESCUED);
    }

    private static void bump(ServerPlayer player, String key, long value, boolean add) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putLong(key, add ? persisted.getLong(key) + value : value);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    /** Leader dialogue action. */
    static void offer(ServerPlayer player, LivingEntity leader, FactionBaseData.Base base) {
        if (doneToday(player)) {
            player.displayClientMessage(Component.literal("\"That's everything we needed today. Thank you.\""), false);
            return;
        }
        ApiResult result = NpcApi.offerTask(player, leader, today(base, day(player.serverLevel())).id(), base.id,
                GlobalPos.of(player.level().dimension(), leader.blockPosition()));
        if (result.status() == ApiResult.Status.BUSY)
            player.displayClientMessage(Component.literal("Finish or abandon your current encounter task first (journal)."), true);
    }

    static String leaderLine(ServerPlayer player) {
        int saved = rescued(player);
        return saved == 0 ? "Welcome to the refuge. Everyone here pulls their weight, and we're glad of any help."
                : "You've brought " + saved + (saved == 1 ? " person" : " people") + " out alive. The refuge won't forget it.";
    }

    // ---- Guests

    /** Records a rescued survivor as a guest of the refuge; false when there is no refuge or no free bunk. */
    static boolean welcome(ServerLevel level, Mob survivor) {
        FactionBaseData data = FactionBaseData.get(level);
        FactionBaseData.Base base = data.faction(FACTION);
        if (base == null) return false;
        ResourceLocation type = ForgeRegistries.ENTITY_TYPES.getKey(survivor.getType());
        if (type == null || !type.getPath().startsWith("survivor_")) return false;
        String name = NpcApi.identity(survivor).map(view -> view.name()).orElse("");
        for (BaseRoster.Slot slot : BaseRoster.layout(base).slots()) {
            if (!GUEST.equals(slot.profession()) || base.guests.containsKey(slot.id())) continue;
            CompoundTag guest = new CompoundTag();
            guest.putString("Name", name);
            guest.putString("Type", type.toString());
            base.guests.put(slot.id(), guest);
            base.lastStaffCheck = 0; // the guest arrives on the next roster check
            data.setDirty();
            return true;
        }
        return false;
    }

    /** Gives a newly spawned guest the rescued survivor's name; Lazarus NPCs keeps it as the NPC's identity. */
    static void name(Mob guest, CompoundTag entry) {
        CompoundTag identity = guest.getPersistentData().getCompound("LazarusNpcsIdentity");
        identity.putString("Name", entry.getString("Name"));
        guest.getPersistentData().put("LazarusNpcsIdentity", identity);
    }

    static void guestDied(ServerLevel level, UUID id) {
        FactionBaseData data = FactionBaseData.get(level);
        FactionBaseData.Base base = data.faction(FACTION);
        if (base == null) return;
        int index = base.staff.indexOf(id);
        BaseRoster.Slot[] slots = BaseRoster.layout(base).slots();
        if (index < 0 || index >= slots.length || !GUEST.equals(slots[index].profession())) return;
        base.guests.remove(slots[index].id());
        data.setDirty();
    }

    static final class Listener {
        @SubscribeEvent
        public void requestDone(NpcApiEvents.TaskStateChanged event) {
            if (event.getTo() != TaskState.COMPLETED || requests().stream().noneMatch(r -> r.id().equals(event.getTaskId()))) return;
            bump(event.getPlayer(), REQUEST_DAY, day(event.getPlayer().serverLevel()), false);
            event.getPlayer().sendSystemMessage(Component.literal("\"This will keep us going. Come back tomorrow.\"")
                    .withStyle(style -> style.withColor(0x8FCB91)));
        }

        /** A rescued survivor walks off towards the refuge a little after the thanks. */
        @SubscribeEvent
        public void leaving(LivingEvent.LivingTickEvent event) {
            LivingEntity entity = event.getEntity();
            if (entity.tickCount % 20 != 0 || !(entity.level() instanceof ServerLevel level)) return;
            long at = entity.getPersistentData().getLong(LEAVE_AT);
            if (at > 0 && level.getGameTime() >= at) entity.discard();
        }

        @SubscribeEvent
        public void died(LivingDeathEvent event) {
            if (event.getEntity().level() instanceof ServerLevel level && event.getEntity().getTags().contains("lazarusevents:base_guest"))
                guestDied(level, event.getEntity().getUUID());
        }
    }

    /** Called when a rescue succeeds: counts it for the player and sends the survivor to the refuge when there is room. */
    static void rescued(ServerPlayer player, Mob survivor) {
        bump(player, RESCUED, 1, true);
        if (!welcome(player.serverLevel(), survivor)) return;
        survivor.getPersistentData().putLong(LEAVE_AT, player.serverLevel().getGameTime() + 200);
        player.sendSystemMessage(Component.literal(NpcApi.identity(survivor).map(view -> view.name()).orElse("The survivor")
                + " will head for the Haven refuge.").withStyle(style -> style.withColor(0x8FCB91)));
    }
}
