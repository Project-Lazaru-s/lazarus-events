package com.lazarus.events;

import com.lazarus.stealth.Perception;
import com.lazarus.stealth.StealthApi;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/** Lazarus Stealth perception for bounty hunters. Only loaded when the mod is present (see Bounties#perceives). */
final class StealthHook {
    private StealthHook() {}

    /** The hunter has heard or seen the player: investigating a noise, alerted or in pursuit. */
    static boolean onTrail(Mob hunter, Player player) {
        return StealthApi.state(hunter, player).ordinal() >= Perception.State.INVESTIGATING.ordinal();
    }
}
