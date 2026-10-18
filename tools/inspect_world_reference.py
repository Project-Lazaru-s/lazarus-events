"""Read-only inspection of a local 1.20.1 region file; never writes the world."""
import collections
import gzip
import json
import math
import pathlib
import struct
import sys
import zlib

sys.stdout.reconfigure(encoding="utf-8")


class Nbt:
    def __init__(self, data):
        self.data = memoryview(data)
        self.pos = 0

    def take(self, size):
        result = self.data[self.pos : self.pos + size]
        self.pos += size
        return result

    def byte(self):
        return self.take(1)[0]

    def short(self):
        return struct.unpack(">H", self.take(2))[0]

    def integer(self):
        return struct.unpack(">i", self.take(4))[0]

    def long(self):
        return struct.unpack(">q", self.take(8))[0]

    def string(self):
        return bytes(self.take(self.short())).decode("utf-8")

    def value(self, kind):
        if kind == 1:
            return struct.unpack(">b", self.take(1))[0]
        if kind == 2:
            return struct.unpack(">h", self.take(2))[0]
        if kind == 3:
            return self.integer()
        if kind == 4:
            return self.long()
        if kind == 5:
            return struct.unpack(">f", self.take(4))[0]
        if kind == 6:
            return struct.unpack(">d", self.take(8))[0]
        if kind == 7:
            return bytes(self.take(self.integer()))
        if kind == 8:
            return self.string()
        if kind == 9:
            subkind, length = self.byte(), self.integer()
            return [self.value(subkind) for _ in range(length)]
        if kind == 10:
            result = {}
            while kind := self.byte():
                key = self.string()
                result[key] = self.value(kind)
            return result
        if kind == 11:
            return [self.integer() for _ in range(self.integer())]
        if kind == 12:
            return [self.long() for _ in range(self.integer())]
        raise ValueError(kind)

    def root(self):
        assert self.byte() == 10
        self.string()
        return self.value(10)


def read_chunks(region_path, wanted):
    data = region_path.read_bytes()
    result = {}
    for lx in range(32):
        for lz in range(32):
            if (lx, lz) not in wanted:
                continue
            index = lx + lz * 32
            entry = data[index * 4 : index * 4 + 4]
            sector = int.from_bytes(entry[:3], "big")
            if not sector:
                continue
            offset = sector * 4096
            length = int.from_bytes(data[offset : offset + 4], "big")
            compression = data[offset + 4]
            blob = data[offset + 5 : offset + 4 + length]
            if compression == 2:
                raw = zlib.decompress(blob)
            elif compression == 1:
                raw = gzip.decompress(blob)
            elif compression == 3:
                raw = blob
            else:
                raise ValueError(compression)
            result[(lx, lz)] = Nbt(raw).root()
    return result


def section_block(section, lx, ly, lz):
    block_states = section.get("block_states", {})
    palette = block_states.get("palette", [])
    if not palette:
        return "minecraft:air"
    if len(palette) == 1:
        state = palette[0]
    else:
        bits = max(4, (len(palette) - 1).bit_length())
        values_per_long = 64 // bits
        index = ly * 256 + lz * 16 + lx
        word = block_states["data"][index // values_per_long] & ((1 << 64) - 1)
        palette_index = (word >> ((index % values_per_long) * bits)) & ((1 << bits) - 1)
        state = palette[palette_index]
    name = state["Name"]
    props = state.get("Properties", {})
    if props:
        name += "[" + ",".join(f"{key}={value}" for key, value in sorted(props.items())) + "]"
    return name


def main():
    world = pathlib.Path(sys.argv[1])
    center_x, center_z = int(sys.argv[2]), int(sys.argv[3])
    min_x, max_x = center_x - 15, center_x + 15
    min_z, max_z = center_z - 15, center_z + 15
    chunks = {}
    regions = collections.defaultdict(set)
    for x in range(min_x >> 4, (max_x >> 4) + 1):
        for z in range(min_z >> 4, (max_z >> 4) + 1):
            rx, rz = x >> 5, z >> 5
            regions[(rx, rz)].add((x & 31, z & 31))
    for (rx, rz), wanted in regions.items():
        region = world / "region" / f"r.{rx}.{rz}.mca"
        if not region.exists():
            continue
        chunks.update({(rx * 32 + lx, rz * 32 + lz): chunk
                       for (lx, lz), chunk in read_chunks(region, wanted).items()})
    output = []
    for x in range(min_x, max_x + 1):
        for z in range(min_z, max_z + 1):
            chunk = chunks.get((x >> 4, z >> 4))
            if not chunk:
                continue
            sections = {s["Y"]: s for s in chunk.get("sections", [])}
            for y in range(63, 78):
                section = sections.get(y >> 4)
                if not section:
                    continue
                state = section_block(section, x & 15, y & 15, z & 15)
                if state.startswith("minecraft:air"):
                    continue
                output.append({"x": x, "y": y, "z": z, "state": state})
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
