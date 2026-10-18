"""Extract the user's roadside gunfire barricade as a vanilla structure."""
import gzip
import json
import pathlib
import re

from build_cache_template import compound, integer, list_tag, string, tag

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / "gunfire_reference_blocks.json"
OUTPUT = ROOT / "src/main/resources/data/lazarusevents/structures/gunfire_barricade.nbt"
ORIGIN = (54, 64, -365)
SIZE = (7, 5, 8)
KEEP = {
    "apocalypsenow:spike_barrier",
    "apocalypsenow:sandbagbarrier",
    "minecraft:oak_fence",
    "minecraft:oak_slab",
    "minecraft:andesite_slab",
    "minecraft:chest",
    "minecraft:mossy_cobblestone",
}


def main():
    source = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    blocks, palette, indices = [], [], {}
    for entry in source:
        position = [entry[axis] - ORIGIN[i] for i, axis in enumerate(("x", "y", "z"))]
        if any(v < 0 or v >= SIZE[i] for i, v in enumerate(position)):
            continue
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
