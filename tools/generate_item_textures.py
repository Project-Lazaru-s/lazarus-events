"""Draws the 16x16 item textures of Lazarus Events (vaccine project). Run from the repository root."""
from pathlib import Path
from PIL import Image

OUT = Path("src/main/resources/assets/lazarusevents/textures/item")

# One character per pixel; '.' is transparent.
PALETTE = {
    "k": (28, 30, 34), "g": (190, 205, 210), "w": (235, 242, 244), "s": (120, 132, 138),
    "r": (122, 30, 34), "R": (168, 44, 46), "p": (205, 92, 96), "y": (214, 196, 92),
    "G": (86, 168, 104), "L": (150, 226, 160), "D": (44, 104, 62), "o": (230, 150, 60),
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
    print("wrote", sorted(p.name for p in OUT.glob("*.png")))
