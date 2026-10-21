package com.lazarus.events;

import com.tacz.guns.api.event.common.GunFireEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

/** Loaded only when TaCZ is installed. A shot ignites gas at the shooter's eyes, including suppressed shots. */
final class GasGunHook {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void fire(GunFireEvent event) {
        if (event.getLogicalSide() == LogicalSide.SERVER && event.getShooter().level() instanceof ServerLevel level) {
            NaturalGas.spark(level, BlockPos.containing(event.getShooter().getEyePosition()));
            NaturalGas.spark(level, event.getShooter().blockPosition());
        }
    }
}
