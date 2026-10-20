"""Regenerate the Rovers camp and Wardens fort structure templates (standard library only).

Run: python tools/generate_faction_bases.py
The layout constants printed at the end must match BaseRoster.Layout in the Java code; the script
fails if a post, patrol point or the theft depot cannot be reached on foot from the gate.
"""

import gzip
import struct
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/data/lazarusevents/structures"
DATA_VERSION = 3465  # 1.20.1


# ---- minimal NBT writer -------------------------------------------------------------------------

def _str(value):
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def _payload(kind, value):
    if kind == "int":
        return struct.pack(">i", value)
    if kind == "string":
        return _str(value)
    if kind == "compound":
        out = b""
        for name, (child_kind, child) in value.items():
            out += bytes([TAG[child_kind]]) + _str(name) + _payload(child_kind, child)
        return out + b"\x00"
    if kind == "list":
        element_kind, items = value
        if not items:
            return b"\x00" + struct.pack(">i", 0)
        return bytes([TAG[element_kind]]) + struct.pack(">i", len(items)) + b"".join(
            _payload(element_kind, item) for item in items)
    raise ValueError(kind)


TAG = {"int": 3, "string": 8, "list": 9, "compound": 10}


# ---- scene builder ------------------------------------------------------------------------------

class Scene:
    def __init__(self, name, width, depth):
        self.name, self.width, self.depth = name, width, depth
        self.blocks = {}

    def put(self, x, y, z, block, **props):
        assert 0 <= x < self.width and 0 <= z < self.depth and y >= 0, (self.name, x, y, z)
        self.blocks[x, y, z] = (block if ":" in block else "minecraft:" + block,
                                tuple(sorted((k, str(v).lower()) for k, v in props.items())))

    def clear(self, x, y, z):
        self.blocks.pop((x, y, z), None)

    def box(self, xa, xb, ya, yb, za, zb, block, **props):
        for x in range(xa, xb + 1):
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    self.put(x, y, z, block, **props)

    def ring(self, xa, xb, ya, yb, za, zb, block, **props):
        for x in range(xa, xb + 1):
            for y in range(ya, yb + 1):
                for z in range(za, zb + 1):
                    if x in (xa, xb) or z in (za, zb):
                        self.put(x, y, z, block, **props)

    def name_at(self, x, y, z):
        entry = self.blocks.get((x, y, z))
        return entry[0] if entry else None

    def height(self):
        return max(y for _, y, _ in self.blocks) + 1

    def write(self):
        palette = sorted(set(self.blocks.values()))
        lookup = {state: i for i, state in enumerate(palette)}

        def state_tag(state):
            name, props = state
            tag = {"Name": ("string", name)}
            if props:
                tag["Properties"] = ("compound", {k: ("string", v) for k, v in props})
            return tag

        root = {
            "DataVersion": ("int", DATA_VERSION),
            "size": ("list", ("int", [self.width, self.height(), self.depth])),
            "palette": ("list", ("compound", [state_tag(s) for s in palette])),
            "blocks": ("list", ("compound", [
                {"pos": ("list", ("int", [x, y, z])), "state": ("int", lookup[state])}
                for (x, y, z), state in sorted(self.blocks.items())])),
            "entities": ("list", ("compound", [])),
        }
        data = bytes([TAG["compound"]]) + _str("") + _payload("compound", root)
        with gzip.GzipFile(ROOT / f"{self.name}.nbt", "wb", mtime=0) as out:
            out.write(data)


def noise(x, z, salt=0):
    h = (x * 73856093) ^ (z * 19349663) ^ (salt * 83492791)
    return (h ^ (h >> 13)) & 0xFFFF


PASSABLE = {None, "minecraft:lantern"}


def validate(scene, gate, layout):
    """Every post, patrol point and the depot must be reachable from the gate, two blocks tall."""
    def walkable(x, z):
        return (0 <= x < scene.width and 0 <= z < scene.depth and scene.name_at(x, 0, z) is not None
                and scene.name_at(x, 1, z) in PASSABLE and scene.name_at(x, 2, z) in PASSABLE)

    seen, queue = {gate}, deque([gate])
    assert walkable(*gate), (scene.name, "gate blocked", gate)
    while queue:
        x, z = queue.popleft()
        for nx, nz in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if (nx, nz) not in seen and walkable(nx, nz):
                seen.add((nx, nz))
                queue.append((nx, nz))
    for label, point in [("post", p) for p in layout["posts"]] + [
            ("patrol", p) for route in layout["patrols"] for p in route]:
        assert tuple(point) in seen, (scene.name, label, point, "unreachable")
    dx, dz = layout["depot"]
    assert scene.name_at(dx, 1, dz) == "minecraft:barrel", (scene.name, "depot is not a barrel")
    assert any((dx + ox, dz + oz) in seen for ox, oz in ((1, 0), (-1, 0), (0, 1), (0, -1))), (
        scene.name, "depot unreachable")
    for bx, bz in layout["barrels"]:
        assert scene.name_at(bx, 1, bz) == "minecraft:barrel", (scene.name, "stock barrel missing", bx, bz)
    for point in layout.get("shelter", ()):
        assert tuple(point) in seen, (scene.name, "shelter unreachable", point)
    for point in layout.get("defense", ()):
        assert tuple(point) in seen, (scene.name, "defense post unreachable", point)
    for point in layout["posts"]:
        x, z = point
        for activity in ((x, z), (x + 1, z), (x, z + 1)):
            assert any((activity[0] + dx, activity[1] + dz) in seen
                       for dx in range(-2, 3) for dz in range(-2, 3)), (
                scene.name, "activity has no reachable standing point", activity)
    for bx, bz in layout.get("beds", ()):
        assert scene.name_at(bx, 1, bz).endswith("_bed"), (scene.name, "sleep bed missing", bx, bz)
        assert any((bx + ox, bz + oz) in seen for ox, oz in ((1, 0), (-1, 0), (0, 1), (0, -1))), (
            scene.name, "sleep bed unreachable", bx, bz)


# ---- Rovers: scavenger caravan camp (23 x 21) ---------------------------------------------------

def rovers_camp():
    s = Scene("rovers_camp", 23, 21)
    for x in range(23):
        for z in range(21):
            n = noise(x, z, 1) % 10
            s.put(x, 0, z, "coarse_dirt" if n < 6 else "dirt" if n < 8 else "packed_mud")
    for x in range(1, 22):          # east-west lane
        for z in range(9, 12):
            s.put(x, 0, z, "gravel" if noise(x, z, 2) % 4 else "dirt_path")
    for x in range(10, 13):         # gate lane
        for z in range(6, 21):
            s.put(x, 0, z, "gravel" if noise(x, z, 3) % 4 else "dirt_path")

    # Scrap perimeter: three-block segments of different salvage, log posts at the corners.
    segments = [
        lambda x, z: (s.put(x, 1, z, "apocalypsenow:scrap_metal_block"), s.put(x, 2, z, "apocalypsenow:scrap_metal_block")),
        lambda x, z: (s.put(x, 1, z, "spruce_fence"), s.put(x, 2, z, "spruce_fence")),
        lambda x, z: (s.put(x, 1, z, "apocalypsenow:pile_of_tires"), s.put(x, 2, z, "spruce_fence")),
        lambda x, z: (s.put(x, 1, z, "cobblestone"), s.put(x, 2, z, "mossy_cobblestone")),
    ]
    edge = [(x, 0) for x in range(23)] + [(22, z) for z in range(1, 21)] + \
           [(x, 20) for x in range(21, -1, -1)] + [(0, z) for z in range(19, 0, -1)]
    for i, (x, z) in enumerate(edge):
        if z == 20 and 10 <= x <= 12:
            continue
        segments[(i // 3 + noise(i, 0, 4)) % 4](x, z)
    for x, z in ((0, 0), (22, 0), (0, 20), (22, 20)):
        s.box(x, x, 1, 3, z, z, "stripped_spruce_log")
        s.put(x, 4, z, "lantern")
    # Gate: tall posts, a plank crossbar and a hanging lantern.
    for x in (9, 13):
        s.box(x, x, 1, 4, 20, 20, "spruce_log")
    s.box(9, 13, 5, 5, 20, 20, "spruce_planks")
    s.put(11, 4, 20, "lantern", hanging=True)
    s.put(8, 1, 19, "apocalypsenow:pile_of_tires")
    s.put(14, 1, 19, "apocalypsenow:red_petrol_barrel")

    # Leader's bus (x 3-12, z 2-5): yellow shell, windows, roof deck with salvage.
    s.ring(3, 12, 1, 3, 2, 5, "yellow_concrete")
    for x in (4, 11):
        for z in (2, 5):
            s.put(x, 1, z, "black_concrete")
    for x in range(4, 12):
        s.put(x, 2, 2, "glass_pane")
        s.put(x, 2, 5, "glass_pane")
    s.put(12, 2, 3, "glass_pane")
    s.put(12, 2, 4, "glass_pane")
    s.box(3, 12, 4, 4, 2, 5, "white_concrete")
    s.box(4, 11, 0, 0, 3, 4, "spruce_planks")
    s.clear(7, 1, 5)
    s.clear(7, 2, 5)
    s.put(4, 1, 3, "red_bed", facing="west", part="head")
    s.put(5, 1, 3, "red_bed", facing="west", part="foot")
    s.put(4, 1, 4, "barrel", facing="up")
    s.put(10, 1, 3, "cartography_table")
    s.put(11, 1, 3, "lectern", facing="west")
    s.put(11, 1, 4, "spruce_stairs", facing="east")
    s.put(7, 3, 3, "lantern", hanging=True)
    s.put(5, 5, 3, "apocalypsenow:cardboard_box")
    s.put(6, 5, 4, "apocalypsenow:pile_of_tires")
    s.put(10, 5, 3, "apocalypsenow:gas_canister")

    # Guards' tent (x 15-20, z 2-6): brown A-frame with two bedrolls.
    for x in range(15, 21):
        s.put(x, 1, 2, "brown_wool")
        s.put(x, 2, 3, "brown_wool")
        s.put(x, 3, 4, "brown_wool")
        s.put(x, 2, 5, "brown_wool")
        s.put(x, 1, 6, "brown_wool")
    s.put(20, 1, 3, "brown_wool")
    s.put(20, 1, 4, "brown_wool")
    s.put(20, 1, 5, "brown_wool")
    s.put(20, 2, 4, "brown_wool")
    for z in (3, 5):
        s.put(16, 1, z, "brown_bed", facing="east", part="foot")
        s.put(17, 1, z, "brown_bed", facing="east", part="head")
    s.put(19, 1, 4, "apocalypsenow:medical_box")  # the medic works in the tent
    s.put(18, 1, 3, "white_carpet")

    # Campfire circle at the lane crossing.
    s.put(11, 1, 10, "campfire", lit=True, facing="north", signal_fire=False)
    s.put(9, 1, 8, "spruce_stairs", facing="north")
    s.put(13, 1, 8, "spruce_stairs", facing="north")
    s.put(9, 1, 12, "spruce_stairs", facing="south")
    s.put(13, 1, 12, "spruce_stairs", facing="south")

    # Supply shack (x 1-5, z 7-11): scrap walls, plank roof, the theft depot at the back.
    s.ring(1, 5, 1, 2, 7, 11, "apocalypsenow:scrap_metal_block")
    s.box(1, 5, 3, 3, 7, 11, "spruce_planks")
    s.box(2, 4, 0, 0, 8, 10, "spruce_planks")
    s.clear(5, 1, 9)
    s.clear(5, 2, 9)
    s.put(2, 1, 9, "barrel", facing="up")
    s.put(2, 1, 8, "apocalypsenow:spruce_crate")
    s.put(2, 1, 10, "apocalypsenow:cardboard_box")
    s.put(3, 1, 10, "apocalypsenow:pallet")

    # Lookout tower (x 18-20, z 8-10) with an outside ladder.
    for x, z in ((18, 8), (20, 8), (18, 10), (20, 10)):
        s.box(x, x, 1, 6, z, z, "spruce_log")
    s.box(18, 18, 1, 4, 9, 9, "spruce_planks")
    s.box(18, 20, 5, 5, 8, 10, "spruce_planks")
    for x, z in ((19, 8), (20, 9), (19, 10)):
        s.put(x, 6, z, "spruce_fence")
    for y in range(1, 6):
        s.put(17, y, 9, "ladder", facing="west")
    s.put(20, 7, 8, "lantern")
    s.put(19, 1, 9, "apocalypsenow:rain_collector")
    s.put(20, 1, 9, "apocalypsenow:petrol_barrel")

    # Armorer's lean-to (x 1-7, z 13-19).
    for x, z in ((1, 13), (7, 13), (7, 19)):
        s.box(x, x, 1, 3, z, z, "stripped_spruce_log")
    s.box(1, 7, 4, 4, 13, 19, "spruce_slab", type="bottom")
    s.box(1, 6, 0, 0, 14, 19, "spruce_planks")
    s.put(2, 1, 18, "anvil", facing="east")
    s.put(3, 1, 19, "grindstone", face="floor", facing="north")
    s.put(4, 1, 19, "smithing_table")
    s.put(5, 1, 19, "crafting_table")
    s.put(6, 1, 19, "apocalypsenow:gas_canister")
    s.put(1, 1, 16, "apocalypsenow:mechanics_tool_chest", facing="east")
    s.put(1, 1, 15, "apocalypsenow:magazine_boxes", facing="east")
    s.put(1, 1, 17, "apocalypsenow:wooden_crate")
    s.put(4, 3, 16, "lantern", hanging=True)

    # Trader's stall (x 15-21, z 13-19): striped awning, counter, shelves.
    for x in (15, 21):
        s.box(x, x, 1, 3, 13, 13, "spruce_fence")
    for x in range(15, 22):
        for z in range(13, 16):
            s.put(x, 4, z, "orange_wool" if (x + z) % 2 else "white_wool")
    s.put(16, 1, 14, "barrel", facing="up")
    s.put(17, 1, 14, "apocalypsenow:pack_of_canned_food")
    s.put(18, 1, 14, "spruce_slab", type="top")
    s.put(19, 1, 14, "apocalypsenow:cardboard_box")
    s.put(20, 1, 14, "barrel", facing="up")
    s.put(16, 1, 19, "apocalypsenow:medicine_boxes", facing="north")
    s.put(17, 1, 19, "apocalypsenow:pack_of_canned_food")
    s.put(18, 1, 19, "apocalypsenow:metal_shelves", facing="north")
    s.put(19, 1, 19, "barrel", facing="up")
    s.put(20, 1, 19, "apocalypsenow:cardboard_box")
    s.put(18, 3, 16, "lantern", hanging=True)
    s.box(18, 18, 4, 4, 16, 16, "spruce_planks")

    layout = {
        # leader, guard_a, guard_b, armorer, trader, guard_c, trader_b, medic, scout_a, scout_b
        "posts": [(7, 4), (11, 18), (14, 7), (4, 16), (18, 16), (19, 11), (20, 16), (18, 4), (8, 10), (15, 10)],
        "patrols": [[(11, 18), (8, 15), (11, 13), (14, 16)], [(14, 7), (21, 7), (21, 12), (14, 12)]],
        "depot": (2, 9),
        "barrels": [(2, 9), (4, 4), (16, 14), (20, 14), (19, 19)],
        "anchor": (11, 10),
        "shelter": [(18, 4)],
        "defense": [(11, 18), (19, 11)],
        "beds": [(5, 3), (16, 3), (16, 5)],
    }
    validate(s, (11, 19), layout)
    return s, layout


# ---- Wardens: walled checkpoint fort (25 x 23) --------------------------------------------------

def wardens_fort():
    s = Scene("wardens_fort", 25, 23)
    for x in range(25):
        for z in range(23):
            n = noise(x, z, 5) % 10
            s.put(x, 0, z, "gravel" if n < 5 else "andesite" if n < 8 else "cobblestone")
    for x in range(11, 14):         # parade lane from gate to command post
        for z in range(7, 23):
            s.put(x, 0, z, "stone_bricks" if noise(x, z, 6) % 5 else "cracked_stone_bricks")

    # Curtain wall with crenels and barbed wire on the walk.
    for x in range(25):
        for z in range(23):
            if x in (0, 24) or z in (0, 22):
                if z == 22 and 11 <= x <= 13:
                    continue
                for y in (1, 2, 3):
                    n = noise(x, z * 7 + y, 7) % 10
                    s.put(x, y, z, "stone_bricks" if n < 7 else "mossy_stone_bricks" if n < 9 else "cracked_stone_bricks")
                s.put(x, 4, z, "stone_brick_wall" if (x + z) % 2 == 0 else "apocalypsenow:barbed_wire")

    # Gatehouse towers (x 8-10 and 14-16, z 20-22) with inside ladders and sandbag parapets.
    for xa, outer in ((8, 8), (14, 16)):
        xb = xa + 2
        mid = xa + 1
        s.ring(xa, xb, 1, 6, 20, 22, "stone_bricks")
        s.put(mid, 6, 21, "stone_bricks")
        s.box(xa, xb, 6, 6, 20, 22, "stone_bricks")
        s.clear(mid, 1, 20)
        s.clear(mid, 2, 20)
        for y in range(1, 7):
            s.put(mid, y, 21, "ladder", facing="north")
        for x in range(xa, xb + 1):
            s.put(x, 7, 22, "apocalypsenow:sandbags")
        for z in (20, 21):
            s.put(outer, 7, z, "apocalypsenow:sandbags")
        s.put(outer, 8, 22, "lantern")
    s.box(11, 13, 4, 5, 22, 22, "stone_bricks")
    s.put(12, 3, 22, "lantern", hanging=True)
    s.put(12, 6, 22, "blue_banner", rotation=8)

    # Sandbag nests inside the gate.
    for x in (8, 9, 10):
        s.put(x, 1, 17, "apocalypsenow:sandbags")
    s.put(8, 1, 18, "apocalypsenow:sandbags")
    for x in (14, 15, 16):
        s.put(x, 1, 17, "apocalypsenow:sandbags")
    s.put(16, 1, 18, "apocalypsenow:sandbags")
    s.put(9, 1, 18, "apocalypsenow:sandbagsslab", type="bottom")
    s.put(15, 1, 18, "apocalypsenow:sandbagsslab", type="bottom")
    s.put(5, 1, 20, "apocalypsenow:tank_trap")
    s.put(19, 1, 20, "apocalypsenow:tank_trap")
    s.put(3, 1, 18, "apocalypsenow:concrete_barrier", facing="south")
    s.put(21, 1, 18, "apocalypsenow:concrete_barrier", facing="south")

    # Command bunker (x 9-15, z 1-6) with radio room and antenna.
    s.ring(9, 15, 1, 3, 1, 6, "polished_andesite")
    s.box(9, 15, 4, 4, 1, 6, "stone_bricks")
    s.box(10, 14, 0, 0, 2, 5, "polished_andesite")
    for x in (9, 15):
        s.put(x, 4, 6, "stone_bricks")
    for x in range(9, 16):
        s.put(x, 5, 6, "apocalypsenow:sandbags")
    s.clear(12, 1, 6)
    s.clear(12, 2, 6)
    s.put(10, 2, 6, "iron_bars")
    s.put(14, 2, 6, "iron_bars")
    s.put(10, 1, 2, "apocalypsenow:military_communication_radio", facing="south")
    s.put(11, 1, 2, "cartography_table")
    s.put(12, 1, 2, "lectern", facing="south")
    s.put(14, 1, 2, "blue_bed", facing="north", part="head")
    s.put(14, 1, 3, "blue_bed", facing="north", part="foot")
    s.put(10, 1, 5, "barrel", facing="up")
    s.put(12, 3, 3, "lantern", hanging=True)
    for y in range(5, 8):
        s.put(14, y, 2, "iron_bars")
    s.put(14, 8, 2, "lightning_rod")

    # Barracks (x 1-7, z 1-6): stone footing, plank walls, three bunks.
    s.ring(1, 7, 1, 1, 1, 6, "stone_bricks")
    s.ring(1, 7, 2, 3, 1, 6, "spruce_planks")
    s.box(1, 7, 4, 4, 1, 6, "spruce_slab", type="bottom")
    s.box(2, 6, 0, 0, 2, 5, "spruce_planks")
    s.clear(4, 1, 6)
    s.clear(4, 2, 6)
    for x in (2, 6):
        s.put(x, 1, 2, "blue_bed", facing="north", part="head")
        s.put(x, 1, 3, "blue_bed", facing="north", part="foot")
    s.put(4, 1, 2, "barrel", facing="up")
    s.put(3, 1, 2, "apocalypsenow:iron_locker")
    s.put(5, 1, 2, "apocalypsenow:iron_locker")
    s.put(4, 3, 4, "lantern", hanging=True)
    s.put(6, 1, 5, "apocalypsenow:medical_box")  # infirmary corner for the medic
    s.put(5, 1, 5, "white_carpet")

    # Armory (x 17-23, z 1-6): barred windows, racks, repair bench.
    s.ring(17, 23, 1, 3, 1, 6, "stone_bricks")
    s.box(17, 23, 4, 4, 1, 6, "smooth_stone_slab", type="bottom")
    s.box(18, 22, 0, 0, 2, 5, "stone_bricks")
    s.clear(20, 1, 6)
    s.clear(20, 2, 6)
    s.put(18, 2, 6, "iron_bars")
    s.put(22, 2, 6, "iron_bars")
    s.put(18, 1, 2, "apocalypsenow:military_storage", facing="south")
    s.put(19, 1, 2, "apocalypsenow:military_crate")
    s.put(20, 1, 2, "apocalypsenow:magazine_boxes", facing="south")
    s.put(21, 1, 2, "apocalypsenow:metal_shelves", facing="south")
    s.put(22, 1, 2, "apocalypsenow:metal_shelves", facing="south")
    s.put(22, 1, 3, "smithing_table")
    s.put(22, 1, 4, "anvil", facing="north")
    s.put(20, 3, 4, "lantern", hanging=True)

    # Supply post (x 18-23, z 10-15): roofed counter for the trader.
    for x, z in ((18, 10), (18, 15)):
        s.box(x, x, 1, 3, z, z, "spruce_log")
    s.box(18, 23, 4, 4, 10, 15, "spruce_slab", type="bottom")
    for z in (11, 12, 14):
        s.put(18, 1, z, "barrel" if z == 11 else "apocalypsenow:pack_of_canned_food")
    s.put(18, 1, 13, "spruce_slab", type="top")
    s.put(23, 1, 11, "apocalypsenow:medical_storage", facing="west")
    s.put(23, 1, 12, "apocalypsenow:medicine_boxes", facing="west")
    s.put(23, 1, 13, "apocalypsenow:pack_of_canned_food")
    s.put(23, 1, 14, "barrel", facing="up")
    s.put(21, 3, 12, "lantern", hanging=True)

    # Fenced supply cage (x 1-6, z 10-15) holding the theft depot.
    s.ring(1, 6, 1, 2, 10, 15, "iron_bars")
    for x, z in ((1, 10), (6, 10), (1, 15), (6, 15)):
        s.box(x, x, 1, 2, z, z, "stone_bricks")
    s.clear(6, 1, 12)
    s.clear(6, 2, 12)
    s.put(2, 1, 12, "barrel", facing="up")
    s.put(2, 1, 11, "apocalypsenow:military_crate")
    s.put(2, 1, 13, "apocalypsenow:military_crate")
    s.put(3, 1, 14, "apocalypsenow:pallet")
    s.put(4, 1, 14, "apocalypsenow:petrol_barrel")
    s.put(3, 1, 11, "apocalypsenow:pallet")

    # Flagpole and courtyard lights.
    s.box(7, 7, 1, 7, 8, 8, "spruce_fence")
    s.box(7, 7, 6, 7, 9, 10, "blue_wool")
    for x in (7, 17):
        s.put(x, 1, 16, "cobblestone_wall")
        s.put(x, 2, 16, "lantern")

    layout = {
        # leader, guard_a, guard_b, armorer, trader, guard_c, guard_d, medic, scout_a, scout_b
        "posts": [(12, 3), (12, 19), (12, 8), (20, 3), (21, 12), (9, 19), (15, 19), (4, 4), (9, 8), (15, 8)],
        "patrols": [[(12, 19), (6, 17), (12, 15), (18, 17)], [(12, 8), (16, 11), (12, 15), (8, 11)]],
        "depot": (2, 12),
        "barrels": [(2, 12), (10, 5), (18, 11), (23, 14)],
        "anchor": (12, 11),
        "shelter": [(5, 4)],
        "defense": [(12, 19), (12, 8)],
        "beds": [(14, 3), (2, 3), (6, 3)],
    }
    validate(s, (12, 21), layout)
    return s, layout


if __name__ == "__main__":
    for build in (rovers_camp, wardens_fort):
        scene, layout = build()
        scene.write()
        print(scene.name, scene.width, scene.height(), scene.depth, len(scene.blocks), "blocks", layout)
