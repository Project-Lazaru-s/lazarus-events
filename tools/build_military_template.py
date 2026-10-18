"""Extract the user's roadside military checkpoint from the Teste Eventos world."""
import gzip
import json
import pathlib
import re

from build_cache_template import compound, integer, list_tag, string, tag

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / "military_reference_blocks.json"
OUTPUT = ROOT / "src/main/resources/data/lazarusevents/structures/military_checkpoint.nbt"
ORIGIN = (-677, 64, -126)
SIZE = (20, 7, 12)
LOOT_BARREL = (-661, 65, -117)
KEEP = {
    "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:cracked_stone_bricks",
    "minecraft:mossy_stone_bricks", "minecraft:green_wool", "minecraft:green_bed",
    "minecraft:oak_log", "minecraft:redstone_wire", "minecraft:stone_brick_stairs",
    "minecraft:stone_brick_slab", "minecraft:stone_brick_wall",
    "minecraft:mossy_stone_brick_stairs", "minecraft:mossy_cobblestone_slab",
    "minecraft:cobblestone_slab", "minecraft:oak_fence", "minecraft:iron_bars",
    "minecraft:lantern", "minecraft:barrel", "minecraft:spruce_trapdoor",
    "minecraft:stone_button", "minecraft:candle",
    "immersive_weathering:cracked_stone_brick_stairs",
    "immersive_weathering:cracked_stone_brick_slab",
    "immersive_weathering:cracked_stone_brick_wall",
    "immersive_weathering:exposed_iron_bars",
    "apocalypsenow:concrete_barrier", "apocalypsenow:construction_barricade",
    "apocalypsenow:spike_barrier", "apocalypsenow:barbed_wire",
    "apocalypsenow:iron_grid", "apocalypsenow:military_door",
    "apocalypsenow:sandbagbarrier", "apocalypsenow:iron_ladder",
    "apocalypsenow:electric_door",
    "refurbished_furniture:oak_table", "refurbished_furniture:computer",
    "refurbished_furniture:light_freezer", "refurbished_furniture:light_fridge",
    "refurbished_furniture:dark_ceiling_light",
    "refurbished_furniture:light_electricity_generator",
}


def main():
    source = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    blocks, palette, indices = [], [], {}
    skipped_barrels = 0
    for entry in source:
        position = [entry[axis] - ORIGIN[i] for i, axis in enumerate(("x", "y", "z"))]
        if any(v < 0 or v >= SIZE[i] for i, v in enumerate(position)):
            continue
        if (entry["x"], entry["y"], entry["z"]) == LOOT_BARREL:
            assert entry["state"].startswith("minecraft:barrel")
            skipped_barrels += 1
            continue  # The event places its tiered loot container here.
        state = entry["state"]
        name = state.split("[", 1)[0]
        if name not in KEEP:
            continue
        properties = dict(re.findall(r"([^=,\[]+)=([^,\]]+)", state))
        properties.pop("weathering", None)
        key = (name, tuple(sorted(properties.items())))
        if key not in indices:
            indices[key] = len(palette)
            fields = [tag(8, "Name", string(name))]
            if properties:
                fields.append(tag(10, "Properties", compound(
                    [tag(8, k, string(v)) for k, v in sorted(properties.items())]
                )))
            palette.append(compound(fields))
        blocks.append(compound([
            tag(9, "pos", list_tag(3, [integer(v) for v in position])),
            tag(3, "state", integer(indices[key])),
        ]))
    assert skipped_barrels == 1
    root = compound([
        tag(3, "DataVersion", integer(3465)),
        tag(9, "size", list_tag(3, [integer(v) for v in SIZE])),
        tag(9, "palette", list_tag(10, palette)),
        tag(9, "blocks", list_tag(10, blocks)),
        tag(9, "entities", list_tag(10, [])),
    ])
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(gzip.compress(tag(10, "", root), mtime=0))
    print(f"{OUTPUT}: {len(blocks)} blocks, {len(palette)} states, size {SIZE}")


if __name__ == "__main__":
    main()
