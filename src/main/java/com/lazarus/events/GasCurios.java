package com.lazarus.events;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

/** Optional Curios bridge: only equipped functional slots supply respiratory protection. */
final class GasCurios {
    private GasCurios() {}

    static ItemStack mask(ServerPlayer player) {
        return CuriosApi.getCuriosInventory(player).resolve()
                .flatMap(inventory -> inventory.findFirstCurio(stack -> stack.is(GasZone.GAS_MASKS)))
                .map(SlotResult::stack).orElse(ItemStack.EMPTY);
    }
}
