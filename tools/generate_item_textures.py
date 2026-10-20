"""Draws the 16x16 item textures of Lazarus Events (vaccine project and hostile proofs). Run from the repository root."""
from pathlib import Path
from PIL import Image

OUT = Path("src/main/resources/assets/lazarusevents/textures/item")

# One character per pixel; '.' is transparent.
PALETTE = {
    "k": (28, 30, 34), "g": (190, 205, 210), "w": (235, 242, 244), "s": (120, 132, 138),
    "r": (122, 30, 34), "R": (168, 44, 46), "p": (205, 92, 96), "y": (214, 196, 92),
    "G": (86, 168, 104), "L": (150, 226, 160), "D": (44, 104, 62), "o": (230, 150, 60),
    "m": (160, 166, 168), "M": (208, 212, 214), "c": (96, 100, 102), "b": (60, 40, 32),
    "t": (176, 64, 40), "T": (220, 112, 64), "e": (226, 214, 180), "E": (170, 156, 120), "n": (110, 84, 52),
}

SAMPLE = [  # a sealed test tube with a dark red, clotted sample
    "................",
    "..........kk....",
    ".........kyyk...",
    "........kyyk....",
    ".......kwgk.....",
    "......kwgk......",
    ".....kwgk.......",
    "....kpRk........",
    "...kpRrk........",
    "..kRRrk.........",
    "..krrk..........",
    "...kk...........",
    "................",
    "................",
    "................",
    "................",
]

SERUM = [  # an auto-injector: grey body, green serum window, orange cap
    "................",
    "............kk..",
    "...........kook.",
    "..........kook..",
    ".........kssk...",
    "........kgsk....",
    ".......kLGk.....",
    "......kLGDk.....",
    ".....kLGDk......",
    "....kgssk.......",
    "...kgssk........",
    "..kssk..........",
    "..kkk...........",
    ".k..............",
    "................",
    "................",
]


DOG_TAGS = [  # two stamped steel tags on a bead chain
    "................",
    "....c.c.c.c.....",
    "...c........c...",
    "..c..........c..",
    "..c...........c.",
    "...kkkkk.......c",
    "...kMMMk...kkkkk",
    "...kMcMk...kMMMk",
    "...kMMMk...kMcMk",
    "...kMcMk...kMMMk",
    "...kMMMk...kMcMk",
    "...kmmmk...kMMMk",
    "...kkkkk...kmmmk",
    "...........kkkkk",
    "................",
    "................",
]

GANG_PATCH = [  # a frayed cloth patch with a rust-red crossed-bones mark
    "................",
    "................",
    "...bbbbbbbbbb...",
    "..bnnnnnnnnnnb..",
    "..bntnnnnnntnb..",
    "..bnnTnnnnTnnb..",
    "..bnnnTnnTnnnb..",
    "..bnnnnTTnnnnb..",
    "..bnnnnTTnnnnb..",
    "..bnnnTnnTnnnb..",
    "..bnnTnnnnTnnb..",
    "..bntnnnnnntnb..",
    "..bnnnnnnnnnnb..",
    "...bbbbbbbbbb...",
    "................",
    "................",
]

BONE_CHARM = [  # a bone carved into a ring, hung on twine
    "................",
    "......nnnn......",
    ".....n....n.....",
    ".....n....n.....",
    "......n..n......",
    ".......kk.......",
    "......keek......",
    ".....keEEek.....",
    "....keE..Eek....",
    "....keE..Eek....",
    ".....keEEek.....",
    "......keek......",
    ".......kk.......",
    "................",
    "................",
    "................",
]


def draw(rows, name):
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == 16, (name, y)
        for x, cell in enumerate(row):
            if cell != ".":
                image.putpixel((x, y), PALETTE[cell] + (255,))
    OUT.mkdir(parents=True, exist_ok=True)
    image.save(OUT / f"{name}.png")


if __name__ == "__main__":
    draw(SAMPLE, "infected_sample")
    draw(SERUM, "experimental_serum")
    draw(DOG_TAGS, "dog_tags")
    draw(GANG_PATCH, "gang_patch")
    draw(BONE_CHARM, "bone_charm")
    print("wrote", sorted(p.name for p in OUT.glob("*.png")))
