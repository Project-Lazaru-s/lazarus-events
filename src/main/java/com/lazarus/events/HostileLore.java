package com.lazarus.events;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.lazarus.npcs.api.HostileGroups;
import com.lazarus.npcs.api.NpcApi;
import com.lazarus.npcs.api.dialogue.DialogueContext;
import com.lazarus.npcs.api.dialogue.DialogueDefinition;
import com.lazarus.npcs.api.dialogue.DialogueOption;
import com.lazarus.npcs.api.dialogue.DialoguePage;
import com.lazarus.npcs.api.dialogue.DialogueTrigger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Hostile humans in the world's lore: deserters who left the checkpoints, street gangs that hold territories, and the
 * Hollow cult. Each group leaves a proof item and sometimes a note; the factions that hate them pay for the proofs.
 */
final class HostileLore {
    static final String DESERTER = "deserter", LOOTER = "looter", CULTIST = "cultist";
    static final float NOTE_CHANCE = 0.15f;
    private static final ResourceLocation BOUNTY = ApiSupport.id("hostile_bounty");
    private static final String LATER = "LazarusEventsBountyLater";

    static final RegistryObject<Item> DOG_TAGS = VaccineProject.ITEMS.register("dog_tags", () -> new Item(new Item.Properties()) {
        @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
            lines.add(grey("Taken from a deserter. The military pays for these."));
        }
    });
    static final RegistryObject<Item> GANG_PATCH = VaccineProject.ITEMS.register("gang_patch", () -> new Item(new Item.Properties()) {
        @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
            String gang = stack.getOrCreateTag().getString("Gang");
            if (!gang.isEmpty()) lines.add(Component.literal(gang).withStyle(style -> style.withColor(0xD48968)));
            lines.add(grey("Proof of a gang member down. The Wardens pay for these."));
        }
    });
    static final RegistryObject<Item> BONE_CHARM = VaccineProject.ITEMS.register("bone_charm", () -> new Item(new Item.Properties()) {
        @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
            lines.add(Component.literal(HostileGroups.CULT).withStyle(style -> style.withColor(0xB0A27A)));
            lines.add(grey("The Haven wants to know when the Hollow is pushed back."));
        }
    });

    private HostileLore() {}

    private static Component grey(String text) { return Component.literal(text).withStyle(style -> style.withColor(0x9A9A9A)); }

    /** Called from the mod constructor: loads the registry entries above and adds them to a creative tab. */
    static void init(IEventBus modBus) {
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
                event.accept(DOG_TAGS);
                event.accept(GANG_PATCH);
                event.accept(BONE_CHARM);
            }
        });
    }

    static void register() {
        ApiSupport.log("hostile bounty dialogue", NpcApi.registerDialogue(new DialogueDefinition(
                BOUNTY, Set.of(DialogueTrigger.INTERACT), 60, HostileLore::bounty)));
    }

    /** deserter, looter, cultist, or null for anyone else. */
    static String group(LivingEntity entity) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id == null || !"apocalypsenow".equals(id.getNamespace())) return null;
        String path = id.getPath();
        if (path.equals("soldiers") || path.equals("soldiers_02")) return DESERTER;
        if (path.startsWith("looter_")) return LOOTER;
        if (path.equals("cannibal")) return CULTIST;
        return null;
    }

    static float proofChance(String group) {
        return switch (group) {
            case DESERTER -> 0.6f;
            case LOOTER -> 0.3f;
            default -> 0.4f;
        };
    }

    static ItemStack proof(ServerLevel level, LivingEntity victim, String group) {
        return switch (group) {
            case DESERTER -> new ItemStack(DOG_TAGS.get());
            case LOOTER -> patch(Bounties.gangOf(level, victim));
            default -> new ItemStack(BONE_CHARM.get());
        };
    }

    /** A patch of the gang that holds {@code pos}. */
    static ItemStack patch(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return patch(HostileGroups.gang(level, pos));
    }

    static ItemStack patch(String gang) {
        ItemStack patch = new ItemStack(GANG_PATCH.get());
        patch.getOrCreateTag().putString("Gang", gang);
        return patch;
    }

    static ItemStack note(String group, int index) {
        List<String[]> notes = switch (group) {
            case DESERTER -> DESERTER_NOTES;
            case LOOTER -> GANG_NOTES;
            default -> CULT_NOTES;
        };
        return MilitaryCollapse.written(notes.get(Math.floorMod(index, notes.size())));
    }

    static boolean surrendered(LivingEntity entity) {
        return entity.getPersistentData().getCompound("LazarusHumanEncounter").getBoolean("Surrendered");
    }

    // ---- Bounties

    /** Who pays for which proof: the item, how many per payment, the faction and its reward. */
    record Bounty(String key, Item item, int needed, String faction, int reputation, List<ItemStack> reward, String line, String option) {}

    static Optional<Bounty> bountyFor(LivingEntity npc) {
        var identity = NpcApi.identity(npc).orElse(null);
        if (identity == null) return Optional.empty();
        String profession = NpcApi.profession(npc).map(ResourceLocation::getPath).orElse("");
        if ("military".equals(identity.faction()))
            return Optional.of(new Bounty("tags", DOG_TAGS.get(), 3, "military", 2,
                    ApiSupport.items(MilitaryBaseEncounter.ammo("tacz:556x45", 30)),
                    "Those are our people's tags. Deserters or not, their families deserve to know.", "Turn in dog tags"));
        if ("wardens".equals(identity.faction()) && ("leader".equals(profession) || "guard".equals(profession)))
            return Optional.of(new Bounty("patches", GANG_PATCH.get(), 3, "wardens", 2,
                    ApiSupport.items(ApiSupport.stack("apocalypsenow:canned_food", 2), ApiSupport.stack("apocalypsenow:bandage", 1)),
                    "Gang colours. Every patch you bring is one less toll on our roads.", "Report gang activity"));
        if ("haven".equals(identity.faction()) && "leader".equals(profession))
            return Optional.of(new Bounty("charms", BONE_CHARM.get(), 2, "haven", 3,
                    ApiSupport.items(ApiSupport.stack("apocalypsenow:antibiotics", 1)),
                    "The Hollow... Our people sleep easier knowing someone fights back.", "We dealt with the Hollow"));
        return Optional.empty();
    }

    /** Pays one bounty when the player carries enough proof; returns false otherwise. */
    static boolean turnIn(ServerPlayer player, Bounty bounty) {
        if (player.getInventory().countItem(bounty.item()) < bounty.needed()) return false;
        int owed = bounty.needed();
        for (int slot = 0; slot < player.getInventory().getContainerSize() && owed > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(bounty.item())) continue;
            int taken = Math.min(owed, stack.getCount());
            stack.shrink(taken);
            owed -= taken;
        }
        UUID payment = UUID.randomUUID();
        if ("wardens".equals(bounty.faction()))
            NpcApi.adjustPoliticalReputation(player, bounty.faction(), bounty.reputation(), BOUNTY, payment);
        else NpcApi.adjustReputation(player, bounty.faction(), bounty.reputation(), BOUNTY, payment);
        for (ItemStack stack : bounty.reward()) if (!player.getInventory().add(stack.copy())) player.drop(stack.copy(), false);
        player.displayClientMessage(Component.literal("\"" + bounty.line() + "\""), false);
        return true;
    }

    /** Shown before the faction's usual dialogue, only while the player carries enough proof. */
    private static Optional<DialoguePage> bounty(DialogueContext c) {
        Optional<Bounty> bounty = bountyFor(c.npc());
        if (bounty.isEmpty() || c.player().getInventory().countItem(bounty.get().item()) < bounty.get().needed()) return Optional.empty();
        CompoundTag persisted = c.player().getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (persisted.getLong(LATER) > c.player().level().getGameTime()) return Optional.empty();
        if (NpcApi.reputation(c.player(), bounty.get().faction()) <= -20) return Optional.empty();
        return Optional.of(new DialoguePage(Component.literal("You've got something for us?"), List.of(
                new DialogueOption("bounty", Component.literal(bounty.get().option() + " (" + bounty.get().needed() + ")"),
                        ctx -> bountyFor(ctx.npc()).ifPresent(b -> turnIn(ctx.player(), b))),
                new DialogueOption("later", Component.literal("Something else"), ctx -> {
                    CompoundTag tag = ctx.player().getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
                    tag.putLong(LATER, ctx.player().level().getGameTime() + 1_200);
                    ctx.player().getPersistentData().put(Player.PERSISTED_NBT_TAG, tag);
                    ctx.player().displayClientMessage(Component.literal("Talk to them again for the rest."), true);
                }))));
    }

    static final class Listener {
        // Distinct from VaccineProject.Listener#drops: Forge names its generated handler after class and method.
        @SubscribeEvent
        public void hostileProofDrops(LivingDropsEvent event) {
            LivingEntity victim = event.getEntity();
            if (!(victim.level() instanceof ServerLevel level) || !(event.getSource().getEntity() instanceof ServerPlayer)
                    || surrendered(victim)) return;
            String group = group(victim);
            if (group == null) return;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            if (random.nextFloat() < proofChance(group)) drop(event, victim, proof(level, victim, group));
            if (random.nextFloat() < NOTE_CHANCE) drop(event, victim, note(group, random.nextInt(5)));
        }

        private static void drop(LivingDropsEvent event, LivingEntity victim, ItemStack stack) {
            event.getDrops().add(new ItemEntity(victim.level(), victim.getX(), victim.getY() + 0.5, victim.getZ(), stack));
        }
    }

    // ---- Notes: title, author, pages

    static final List<String[]> DESERTER_NOTES = List.of(
            new String[] {"Folded Letter", "Pvt. Kowalski",
                    "Ma, we left the checkpoint. The orders stopped coming and the radio just hissed.",
                    "Sgt says we're not deserters if there's no army left to desert. I don't believe him."},
            new String[] {"Route Card", "Unknown",
                    "Avoid the highway, the Wardens watch it. Take the rail line east.",
                    "Toll anyone with a backpack. Nobody walks free anymore."},
            new String[] {"Duty Roster (crossed out)", "Cpl. Mendes",
                    "Watch: Mendes, Ortiz, Hale. Hale is gone. Ortiz is gone.",
                    "Next week it will just be my name on this list."},
            new String[] {"Confession", "Lt. Brandt",
                    "I gave the order to take the truck. Thirty civilians were waiting for that truck.",
                    "If anyone finds this: they were at the school on the hill. I'm sorry."},
            new String[] {"Torn Map Note", "Unknown",
                    "Old outpost to the north still has ammo. Military never came back for it.",
                    "Don't go at night. Something else lives there now."});

    static final List<String[]> GANG_NOTES = List.of(
            new String[] {"Toll List", "Unknown",
                    "Main street: 2 cans. Bridge: 3 cans or a weapon. Pharmacy: everything.",
                    "Anyone who argues pays double. Anyone who runs pays with their boots."},
            new String[] {"Turf Warning", "Unknown",
                    "These blocks belong to us. The next crew that paints over our mark loses a finger.",
                    "Tell the Rovers the price went up."},
            new String[] {"Boss's Orders", "The Boss",
                    "Stop hitting the Haven people. They have nothing, and they patch up our wounded.",
                    "Hit the Rover caravans instead. They can afford it."},
            new String[] {"Scribbled Note", "Danny",
                    "Got a patch today. Finally one of them.",
                    "Mom would hate it. Mom isn't here."},
            new String[] {"Stash Record", "Unknown",
                    "Under the stairs of the burned laundromat: food, two pistols, the good whiskey.",
                    "If you're not crew and you read this, keep walking."});

    static final List<String[]> CULT_NOTES = List.of(
            new String[] {"Bloodied Page", "The Hollow",
                    "The sleepers walk because the world is hungry. We feed it so it does not feed on us.",
                    "Hollow out your heart and you will not fear the night."},
            new String[] {"Hunting Marks", "The Hollow",
                    "Old farm: two. Gas station: one, limping. Refuge: too many guns.",
                    "Wait for the moon to hide."},
            new String[] {"Prayer", "Unknown",
                    "Take the flesh, keep the bone. The bone remembers.",
                    "Wear the charm and the dead will pass you by."},
            new String[] {"Child's Drawing", "Unknown",
                    "A drawing of a family, one figure scratched out and redrawn with empty eyes.",
                    "On the back: they said I could stay if I am quiet."},
            new String[] {"Warning", "A Haven scout",
                    "If you find a ring of bones by a fire, leave. Do not look for whoever made it.",
                    "We lost Marta that way."});
}
