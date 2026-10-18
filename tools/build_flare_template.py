"""Capture the user's ground flare marker, replacing its barrel with a Medical Drop Box."""
import gzip
import json
import pathlib
import re

from build_cache_template import compound, integer, list_tag, string, tag

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / "flare_reference_blocks.json"
OUTPUT = ROOT / "src/main/resources/data/lazarusevents/structures/flare_marker.nbt"
ORIGIN = (114, 64, -285)
SIZE = (6, 2, 8)
KEEP = {
    "minecraft:white_wool",
    "minecraft:yellow_wool",
    "minecraft:red_wool",
    "minecraft:light_gray_wool",
    "minecraft:white_carpet",
    "minecraft:yellow_carpet",
    "minecraft:red_carpet",
    "minecraft:mangrove_slab",
    "minecraft:oak_fence",
    "minecraft:barrel",
}
BOX = (117, 65, -282)


def main():
    source = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    blocks, palette, indices = [], [], {}
    box_count = 0
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
        if name == "minecraft:barrel":
            assert (entry["x"], entry["y"], entry["z"]) == BOX
            name = "apocalypsenow:medicalairdropbox"
            properties = {}
            box_count += 1
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
    assert box_count == 1
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
