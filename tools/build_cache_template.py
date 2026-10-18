"""Extract the user's cache camp from a read-only region scan as a vanilla structure."""
import gzip
import json
import pathlib
import re
import struct

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / "reference_blocks.json"
OUTPUT = ROOT / "src/main/resources/data/lazarusevents/structures/cache_camp.nbt"
ORIGIN = (62, 64, -390)
SIZE = (13, 5, 12)
KEEP = {
    "minecraft:white_wool",
    "minecraft:oak_slab",
    "minecraft:oak_planks",
    "minecraft:dirt_path",
    "apocalypsenow:spike_barrier",
    "minecraft:oak_fence",
    "minecraft:cobblestone",
    "minecraft:mossy_cobblestone",
    "minecraft:lantern",
    "minecraft:red_bed",
    "minecraft:cobweb",
    "minecraft:campfire",
    "minecraft:cobblestone_slab",
    "minecraft:oak_leaves",
    "minecraft:vine",
}


def string(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def tag(kind, name, payload):
    return bytes([kind]) + string(name) + payload


def integer(value):
    return struct.pack(">i", value)


def compound(fields):
    return b"".join(fields) + b"\x00"


def list_tag(kind, values):
    return bytes([kind]) + integer(len(values)) + b"".join(values)


def main():
    source = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    blocks = []
    palette = []
    indices = {}
    for entry in source:
        state = entry["state"]
        name = state.split("[", 1)[0]
        if name not in KEEP or name == "minecraft:barrel":
            continue
        properties = dict(re.findall(r"([^=,\[]+)=([^,\]]+)", state))
        properties.pop("weathering", None)  # Optional mod-added property.
        key = (name, tuple(sorted(properties.items())))
        if key not in indices:
            indices[key] = len(palette)
            fields = [tag(8, "Name", string(name))]
            if properties:
                fields.append(tag(10, "Properties", compound(
                    [tag(8, k, string(v)) for k, v in sorted(properties.items())]
                )))
            palette.append(compound(fields))
        position = [entry[axis] - ORIGIN[index] for index, axis in enumerate(("x", "y", "z"))]
        if any(v < 0 or v >= SIZE[i] for i, v in enumerate(position)):
            raise ValueError(entry)
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
