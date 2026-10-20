package com.lazarus.events;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.ApiResult;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.event.NpcApiEvents;
import com.lazarus.npcs.api.task.TaskDefinition;
import com.lazarus.npcs.api.task.TaskObjectives;
import com.lazarus.npcs.api.task.TaskState;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Military vaccine research: special infected drop samples, the outpost pays for every three delivered, and every
 * third delivery also earns an experimental serum. Antibiotics already cure The Hordes infection, so the serum's
 * value is protection: it cures and blocks new infection for one in-game day.
 */
final class VaccineProject {
    static final ResourceLocation TASK = ApiSupport.id("military_vaccine");
    static final ResourceLocation INFECTED = new ResourceLocation("hordes", "infected");
    static final int SAMPLES = 3, SERUM_EVERY = 3;
    static final float DROP_CHANCE = 0.06f;
    static final long IMMUNITY_TICKS = 24_000L;
    private static final String DELIVERIES = "LazarusEventsVaccineDeliveries";
    static final String IMMUNE_UNTIL = "LazarusEventsSerumImmunity";

    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, LazarusEvents.ID);
    static final RegistryObject<Item> SAMPLE = ITEMS.register("infected_sample", () -> new Item(new Item.Properties().stacksTo(16)) {
        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.literal("Military researchers pay for these.").withStyle(style -> style.withColor(0x9A9A9A)));
        }
    });
    static final RegistryObject<Item> SERUM = ITEMS.register("experimental_serum", () -> new Serum(new Item.Properties().stacksTo(4)));

    private VaccineProject() {}

    static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
                event.accept(SAMPLE);
                event.accept(SERUM);
            }
        });
    }

    static void register() {
        ApiSupport.log("vaccine registration", NpcApi.registerTask(new TaskDefinition(TASK, Component.literal("Samples for the lab"),
                Component.literal("The outpost medics are working on a vaccine. Bring three infected samples from the special infected."),
                TaskObjectives.deliver(List.of(new ItemStack(SAMPLE.get(), SAMPLES))),
                72_000, TaskDefinition.DeathPolicy.PERSIST, false, true,
                ApiSupport.items(ApiSupport.stack("apocalypsenow:antibiotics", 1), ApiSupport.stack("apocalypsenow:bandage", 2)),
                "military", 3)));
    }

    /** Dialogue action at the military gate. */
    static void offer(ServerPlayer player, LivingEntity giver, UUID encounterId, BlockPos center) {
        ApiResult offer = NpcApi.offerTask(player, giver, TASK, encounterId, GlobalPos.of(player.level().dimension(), center));
        if (offer.status() == ApiResult.Status.BUSY)
            player.displayClientMessage(Component.literal(offer.message().contains("taken")
                    ? "Someone is already on it." : "Finish or abandon your current encounter task first (journal)."), true);
    }

    static int deliveries(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getInt(DELIVERIES);
    }

    /** Counts a completed delivery; every third also hands over a serum. Returns true when a serum was given. */
    static boolean completed(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        int count = persisted.getInt(DELIVERIES) + 1;
        persisted.putInt(DELIVERIES, count);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        if (count % SERUM_EVERY != 0) return false;
        ItemStack serum = new ItemStack(SERUM.get());
        if (!player.getInventory().add(serum)) player.drop(serum, false);
        return true;
    }

    static boolean special(LivingEntity entity) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && "undead_revamp2".equals(id.getNamespace()) && entity instanceof Monster;
    }

    static boolean immune(LivingEntity entity) {
        return entity.getPersistentData().getLong(IMMUNE_UNTIL) > entity.level().getGameTime();
    }

    static final class Listener {
        @SubscribeEvent
        public void drops(LivingDropsEvent event) {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide() || !(event.getSource().getEntity() instanceof ServerPlayer)
                    || !special(victim) || ThreadLocalRandom.current().nextFloat() >= DROP_CHANCE) return;
            event.getDrops().add(new ItemEntity(victim.level(), victim.getX(), victim.getY() + 0.5, victim.getZ(),
                    new ItemStack(SAMPLE.get())));
        }

        @SubscribeEvent
        public void state(NpcApiEvents.TaskStateChanged event) {
            if (!event.getTaskId().equals(TASK) || event.getTo() != TaskState.COMPLETED) return;
            ServerPlayer player = event.getPlayer();
            player.sendSystemMessage(Component.literal(completed(player)
                    ? "\"The lab cooked up a batch. Take one, it might save your life.\""
                    : "\"Good samples. Keep them coming.\"").withStyle(style -> style.withColor(0xB5C48B)));
        }

        /** The serum's protection: The Hordes cannot apply its infection while it lasts. */
        @SubscribeEvent
        public void applicable(MobEffectEvent.Applicable event) {
            MobEffect infected = ForgeRegistries.MOB_EFFECTS.getValue(INFECTED);
            if (infected != null && event.getEffectInstance().getEffect() == infected && immune(event.getEntity()))
                event.setResult(Event.Result.DENY);
        }
    }

    /** Drunk like a potion: removes the infection, grants a day of immunity, and makes the drinker sick for a while. */
    static final class Serum extends Item {
        Serum(Properties properties) { super(properties); }

        @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.DRINK; }
        @Override public int getUseDuration(ItemStack stack) { return 40; }

        @Override
        public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level, Player player,
                net.minecraft.world.InteractionHand hand) {
            return net.minecraft.world.item.ItemUtils.startUsingInstantly(level, player, hand);
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity user) {
            if (!level.isClientSide()) inject(user);
            if (!(user instanceof Player player) || !player.getAbilities().instabuild) stack.shrink(1);
            return stack;
        }

        static void inject(LivingEntity user) {
            MobEffect infected = ForgeRegistries.MOB_EFFECTS.getValue(INFECTED);
            if (infected != null) user.removeEffect(infected);
            user.getPersistentData().putLong(IMMUNE_UNTIL, user.level().getGameTime() + IMMUNITY_TICKS);
            user.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 600));
            if (user instanceof ServerPlayer player)
                player.displayClientMessage(Component.literal("The serum burns. You feel the fever break.")
                        .withStyle(style -> style.withColor(0xB5C48B)), true);
        }

        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.literal("Cures infection and blocks it for one day.").withStyle(style -> style.withColor(0x7FB27F)));
            lines.add(Component.literal("Side effect: nausea.").withStyle(style -> style.withColor(0x9A9A9A)));
        }
    }
}
