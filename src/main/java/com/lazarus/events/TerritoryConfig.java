package com.lazarus.events;

import net.minecraftforge.common.ForgeConfigSpec;

/** Independent switches: disabling natural mining gas never disables infectious gas. */
final class TerritoryConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue METRO_GAS, SHELTER, MINERS, NATURAL;
    static final ForgeConfigSpec.IntValue MAX_Y, CHANCE, COOLDOWN, DURATION;
    static final ForgeConfigSpec.DoubleValue BLAST_POWER, BLAST_DAMAGE;
    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("territories");
        METRO_GAS = b.define("metroGas", true);
        SHELTER = b.define("metroShelter", true);
        MINERS = b.define("lostMiners", true);
        b.pop().push("naturalGas");
        NATURAL = b.define("enabled", true);
        MAX_Y = b.comment("Mining below this Y can release gas.").defineInRange("belowY", 0, -64, 320);
        CHANCE = b.comment("One chance in this many successfully broken stone/ore blocks.").defineInRange("chanceOneIn", 400, 1, 1000000);
        COOLDOWN = b.comment("Minimum seconds between successful releases per player; persists across relogs.")
                .defineInRange("cooldownSeconds", 600, 0, 86400);
        DURATION = b.defineInRange("durationSeconds", 180, 10, 3600);
        BLAST_POWER = b.comment("Explosion power; previously 2.5. Blocks are destroyed using vanilla resistance rules.")
                .defineInRange("explosionPower", 5.0, 2.5, 12.0);
        BLAST_DAMAGE = b.comment("Damage multiplier for natural gas explosions only; applied before armor.")
                .defineInRange("explosionDamageMultiplier", 4.0, 4.0, 16.0);
        b.pop();
        SPEC = b.build();
    }
    private TerritoryConfig() {}
}
