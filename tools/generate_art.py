#!/usr/bin/env python3
"""
Generates every texture, block/item/partial model and the Blockbench project for the fuel pump.

    python tools/generate_art.py

Palette and motifs come from docs/art-study-notes.md: the pump body follows Create: Diesel
Generators' panel language in Create copper with brass highlights (no red paint, no hazard
stripes), the base is a Mechanical-Arm-style plinth with Create's wooden cogwheel turning in the
middle of the lower block, fuel enters through a pipe port in the bottom face, and the hose
follows Create: Power Grid's wire textures (standalone 16x16 tile in textures/special,
U = arc length, power-cord black rubber). The nozzle is a dry-break coupler: a wide locking face
with bayonet lugs, a grip ring and a cam lever, meant to seat flat against a tank.

One geometry table drives three outputs so they can never drift apart:
  * assets/westernpump/models/block/*.json and models/item/fuel_pump.json (what the game loads)
  * assets/westernpump/textures/{block,special}/*.png (part faces are packed onto 64x64 / 32x32
    sheets at 1 texture px per block px, like Diesel Generators' sheets)
  * docs/fuel_pump.bbmodel (Blockbench project with the named groups base, cog, body,
    gauge_drum, holster, nozzle, hose_segment_NN)

Two more outputs are rendered from this same geometry table, so nothing can drift:
  * tools/generate_icon.py  -> src/main/resources/westernpump_icon.png (the mod icon)
  * tools/generate_banner.py -> docs/banner.png (README / Modrinth banner; also copies the icon
    to docs/icon.png)
Both import this module and raycast ELEMS directly. Run them after this script.

Requires Pillow.
"""
import base64
import io
import json
import math
import os
import uuid

from PIL import Image

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "westernpump")
TEX_BLOCK = os.path.join(ASSETS, "textures", "block")
TEX_SPECIAL = os.path.join(ASSETS, "textures", "special")
MODELS_BLOCK = os.path.join(ASSETS, "models", "block")
MODELS_ITEM = os.path.join(ASSETS, "models", "item")
DOCS = os.path.join(ROOT, "docs")

# --------------------------------------------------------------------------------------
# Palette (sampled from the real jars, see docs/art-study-notes.md)
# --------------------------------------------------------------------------------------
def hx(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


# Diesel Generators gunmetal (used sparingly now: window, vent, coupler neck)
GM_1 = hx("#1e2126")
GM_2 = hx("#212123")
GM_3 = hx("#272b30")
GM_5 = hx("#303639")
GM_7 = hx("#3a4043")
# Create / DG brass — the highlight metal
BR_0 = hx("#724731")
BR_1 = hx("#9e6947")
BR_2 = hx("#bf8e55")
BR_3 = hx("#cea05a")
BR_4 = hx("#e4b763")
BR_5 = hx("#f7cb6c")
BR_7 = hx("#ffeb8c")
# Create copper — the body metal
CU_0 = hx("#904931")
CU_1 = hx("#9a5038")
CU_2 = hx("#a75a40")
CU_3 = hx("#b26247")
CU_4 = hx("#c26b4c")
CU_5 = hx("#c87456")
CU_6 = hx("#d67b5b")
CU_7 = hx("#e3826c")
# Create paints arrows a couple of shades darker than the body, no outline (see pump.png); this
# is the darkest copper Create itself uses on the Mechanical Pump face.
CU_ARROW = hx("#793b2b")
# DG oxblood recess
OX_1 = hx("#592424")
# DG neutral sheet metal
ST_0 = hx("#353535")
ST_1 = hx("#535555")
ST_2 = hx("#878787")
ST_3 = hx("#b0b0b0")
# DG gauge face
GA_FACE = hx("#a7a7a2")
GA_FACE_L = hx("#cacac7")
GA_BLUE = hx("#677baf")
GA_CYAN = hx("#8bc1cd")
GA_ORANGE = hx("#e49d63")
GA_RED = hx("#a93f4b")
CREAM = hx("#eee4ce")
# Power Grid power-cord rubber + wire-connector contact copper
RB_0 = hx("#181714")
RB_1 = hx("#23201d")
RB_2 = hx("#312d27")
RB_3 = hx("#38332b")
RB_4 = hx("#413c31")
RB_5 = hx("#4a4336")
RB_6 = hx("#514a3d")
RB_7 = hx("#5d574b")
PG_CU_0 = hx("#a75a40")
PG_CU_1 = hx("#c16c4f")
PG_CU_2 = hx("#d17859")
# Create cogwheel wood (stand-in texture for the Blockbench file only; the game uses Create's)
WD_0 = hx("#553a1f")
WD_1 = hx("#614b2e")
WD_2 = hx("#70522e")
WD_3 = hx("#7a5a34")
WD_4 = hx("#82613a")
BLACK = hx("#0e0f0f")
CLEAR = (0, 0, 0, 0)


# --------------------------------------------------------------------------------------
# Pixel helpers
# --------------------------------------------------------------------------------------
def new(w, h, color=CLEAR):
    return Image.new("RGBA", (w, h), color)


def px(img, x, y, c):
    if 0 <= x < img.size[0] and 0 <= y < img.size[1]:
        img.putpixel((int(x), int(y)), c)


def rect(img, x0, y0, x1, y1, c):
    """Inclusive pixel rectangle."""
    for y in range(int(y0), int(y1) + 1):
        for x in range(int(x0), int(x1) + 1):
            px(img, x, y, c)


def outline(img, x0, y0, x1, y1, c):
    for x in range(int(x0), int(x1) + 1):
        px(img, x, y0, c)
        px(img, x, y1, c)
    for y in range(int(y0), int(y1) + 1):
        px(img, x0, y, c)
        px(img, x1, y, c)


def bevel(img, x0, y0, x1, y1, field, hi, lo, edge=None):
    """DG panel bevel: optional 1px outline, highlight top/left, shadow bottom/right."""
    rect(img, x0, y0, x1, y1, field)
    if edge is not None:
        outline(img, x0, y0, x1, y1, edge)
        x0, y0, x1, y1 = x0 + 1, y0 + 1, x1 - 1, y1 - 1
    if x1 - x0 >= 2 and y1 - y0 >= 2:
        for x in range(int(x0), int(x1) + 1):
            px(img, x, y0, hi)
            px(img, x, y1, lo)
        for y in range(int(y0), int(y1) + 1):
            px(img, x0, y, hi)
            px(img, x1, y, lo)
        px(img, x1, y0, field)
        px(img, x0, y1, field)


def copper_panel(img, x0, y0, x1, y1, rivets=False):
    bevel(img, x0, y0, x1, y1, CU_4, CU_6, CU_1, edge=CU_0)
    if rivets and x1 - x0 >= 5 and y1 - y0 >= 5:
        for (x, y) in [(x0 + 1, y0 + 1), (x1 - 1, y0 + 1), (x0 + 1, y1 - 1), (x1 - 1, y1 - 1)]:
            px(img, x, y, BR_5)


def louvre(img, x0, y0, x1, y1):
    """DG exhaust louvre in copper tones: rim on a dark field with one diagonal lighter slash."""
    rect(img, x0, y0, x1, y1, CU_1)
    outline(img, x0, y0, x1, y1, CU_5)
    w = x1 - x0
    h = y1 - y0
    for i in range(1, min(w, h)):
        px(img, x1 - i, y0 + i, CU_5)
        px(img, x1 - i - 1, y0 + i, CU_5)


def nested_boss(img, cx, cy, r):
    """DG cylinder-head bolt boss in brass: darker ring, lighter ring, dark centre."""
    rect(img, cx - r, cy - r, cx + r, cy + r, BR_1)
    rect(img, cx - r + 1, cy - r + 1, cx + r - 1, cy + r - 1, BR_4)
    if r >= 2:
        rect(img, cx - r + 2, cy - r + 2, cx + r - 2, cy + r - 2, BR_2)
    px(img, cx, cy, BR_0)


def cog(img, cx, cy, radius, color, teeth=8):
    """Create cog silhouette: hollow rim with a hub, teeth around the outside. Symmetric about cx."""
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if d <= radius - 1.2:
                if d >= radius - 2.6 or d <= 1.3:
                    px(img, x, y, color)
            elif d <= radius:
                ang = math.atan2(dy, dx)
                if int((ang + math.pi) / (2 * math.pi) * teeth * 2) % 2 == 0:
                    px(img, x, y, color)


def pipe_port(img, x0, y0, x1, y1, ring=BR_2):
    """DG coupling / pumpjack pipe face: metal ring, dark inner ring, black bore, one highlight."""
    brass = ring in (BR_2, BR_3)
    rect(img, x0, y0, x1, y1, ring)
    outline(img, x0, y0, x1, y1, BR_0 if brass else CU_1)
    px(img, x0, y0, BR_5 if brass else CU_6)
    px(img, x1 - 1, y0, BR_5 if brass else CU_6)
    if x1 - x0 >= 3:
        outline(img, x0 + 1, y0 + 1, x1 - 1, y1 - 1, CU_0)
    if x1 - x0 >= 5:
        rect(img, x0 + 2, y0 + 2, x1 - 2, y1 - 2, BLACK)
        px(img, x0 + 2, y0 + 2, GM_7)
    elif x1 - x0 >= 3:
        rect(img, x0 + 1, y0 + 1, x1 - 1, y1 - 1, BLACK)


def gauge(img, x0, y0, size):
    """DG round gauge: dark bezel, brass ring, light face, coloured arc, red needle."""
    x1, y1 = x0 + size - 1, y0 + size - 1
    rect(img, x0, y0, x1, y1, GM_3)
    outline(img, x0, y0, x1, y1, BR_1)
    for (x, y) in [(x0, y0), (x1, y0), (x0, y1), (x1, y1)]:
        px(img, x, y, CU_4)
    rect(img, x0 + 1, y0 + 1, x1 - 1, y1 - 1, GA_FACE)
    rect(img, x0 + 2, y0 + 2, x1 - 2, y1 - 2, GA_FACE_L)
    cx = (x0 + x1) / 2
    cy = (y0 + y1) / 2
    arc = [GA_BLUE, GA_CYAN, GA_ORANGE, GA_RED]
    n = size - 2
    for i in range(n):
        px(img, x0 + 1 + i, y0 + 1, arc[min(3, i * 4 // n)])
    px(img, int(cx), int(cy), OX_1)
    px(img, int(cx) + 1, int(cy) - 1, GA_RED)
    px(img, int(cx), int(cy) + 1, GM_1)


def up_arrow(img, cx, y0, color):
    """5-row chevron pointing up (fuel travels up out of the base), centred on cx-1..cx."""
    rect(img, cx - 1, y0, cx, y0, color)
    rect(img, cx - 2, y0 + 1, cx + 1, y0 + 1, color)
    rect(img, cx - 3, y0 + 2, cx + 2, y0 + 2, color)
    rect(img, cx - 1, y0 + 3, cx, y0 + 4, color)


DIGITS = {
    "0": ["111", "101", "101", "101", "111"],
    "1": ["010", "110", "010", "010", "111"],
    "2": ["111", "001", "111", "100", "111"],
    "3": ["111", "001", "111", "001", "111"],
    "4": ["101", "101", "111", "001", "001"],
    "5": ["111", "100", "111", "001", "111"],
    "6": ["111", "100", "111", "101", "111"],
    "7": ["111", "001", "001", "001", "001"],
    "8": ["111", "101", "111", "101", "111"],
    "9": ["111", "101", "111", "001", "111"],
}


def digit(img, x0, y0, ch, color):
    for r, row in enumerate(DIGITS[ch]):
        for c, bit in enumerate(row):
            if bit == "1":
                px(img, x0 + c, y0 + r, color)


# --------------------------------------------------------------------------------------
# Sheet packer: small part faces are packed at 1 texture px per block px.
# UVs are px * 16 / sheet_size because block-model UVs always span 0..16 whatever the texture size.
# --------------------------------------------------------------------------------------
class Sheet:
    def __init__(self, name, size):
        self.name = name
        self.size = size
        self.img = new(size, size)
        self.x = 0
        self.y = 0
        self.row_h = 0
        self.cache = {}

    def alloc(self, w, h, key=None):
        if key is not None and key in self.cache:
            return self.cache[key], False
        w, h = int(math.ceil(w)), int(math.ceil(h))
        if self.x + w > self.size:
            self.x = 0
            self.y += self.row_h + 1
            self.row_h = 0
        if self.y + h > self.size:
            raise RuntimeError("sheet %s is full" % self.name)
        r = (self.x, self.y, w, h)
        self.x += w + 1
        self.row_h = max(self.row_h, h)
        if key is not None:
            self.cache[key] = r
        return r, True


def paint_material(img, rx, ry, w, h, mat):
    x0, y0, x1, y1 = rx, ry, rx + w - 1, ry + h - 1
    if mat == "copper":
        bevel(img, x0, y0, x1, y1, CU_4, CU_6, CU_1)
    elif mat == "copper_dark":
        bevel(img, x0, y0, x1, y1, CU_1, CU_4, CU_0)
    elif mat == "copper_port":
        pipe_port(img, x0, y0, x1, y1, ring=CU_4)
    elif mat == "brass_port":
        pipe_port(img, x0, y0, x1, y1, ring=BR_2)
    elif mat == "brass":
        bevel(img, x0, y0, x1, y1, BR_3, BR_5, BR_1)
    elif mat == "brass_dark":
        bevel(img, x0, y0, x1, y1, BR_2, BR_3, BR_0)
    elif mat == "gunmetal":
        bevel(img, x0, y0, x1, y1, GM_5, GM_7, GM_3)
    elif mat == "gunmetal_dark":
        rect(img, x0, y0, x1, y1, GM_3)
        if w >= 3 and h >= 3:
            outline(img, x0, y0, x1, y1, GM_1)
    elif mat == "black":
        rect(img, x0, y0, x1, y1, BLACK)
    elif mat == "steel":
        bevel(img, x0, y0, x1, y1, ST_2, ST_3, ST_1)
    elif mat == "rubber":
        bevel(img, x0, y0, x1, y1, RB_3, RB_5, RB_1)
    else:
        raise ValueError(mat)


# --------------------------------------------------------------------------------------
# Geometry table.  Coordinates are block pixels in the NORTH-facing frame (front face at -z).
# --------------------------------------------------------------------------------------
class Elem:
    def __init__(self, name, group, model, frm, to, faces, rotation=None):
        self.name = name
        self.group = group
        self.model = model
        self.frm = frm
        self.to = to
        self.faces = faces  # dir -> ("panel", key) | ("sheet", sheet, material[, painter]) | ("cog", uv) | ("special", tex, i)
        self.rotation = rotation
        self.uuid = str(uuid.uuid4())


ELEMS = []


def add(name, group, model, frm, to, faces, rotation=None):
    e = Elem(name, group, model, frm, to, faces, rotation)
    ELEMS.append(e)
    return e


def all_faces(spec, omit=()):
    return {d: spec for d in ("north", "south", "east", "west", "up", "down") if d not in omit}


SHEET = lambda mat: ("sheet", "parts", mat)
NOZ = lambda mat: ("sheet", "nozzle", mat)
PANEL = lambda key: ("panel", key)

# ---- lower block: plinth, cog gap, short cabinet ----------------------------------------
add("plinth", "base", "lower", [1, 0, 1], [15, 6, 15], {
    "north": PANEL("plinth"), "south": PANEL("plinth"), "east": PANEL("plinth"), "west": PANEL("plinth"),
    "up": PANEL("plinth_top"), "down": PANEL("bottom")})

# y 6..10 is left open for Create's shaftless cogwheel partial (teeth at 6.5..9.5, hub 4..12).

add("body_lower", "body", "lower", [2, 10, 2], [14, 16, 14], {
    "north": PANEL("front_lower"), "south": PANEL("back_lower"),
    "east": PANEL("side_lower"), "west": PANEL("side_lower"), "down": PANEL("plinth_top")})

for (x0, z0, tag) in [(1, 1, "nw"), (13, 1, "ne"), (1, 13, "sw"), (13, 13, "se")]:
    # 10.02, not 10: the cabinet underside is at 10 and would fight the posts' undersides otherwise
    add("post_lower_" + tag, "body", "lower", [x0, 10.02, z0], [x0 + 2, 16, z0 + 2],
        all_faces(PANEL("brass"), omit=("up",)))

# ---- upper block ----------------------------------------------------------------------
add("body_upper", "body", "upper", [2, 0, 2], [14, 13, 14], {
    "north": PANEL("front_upper"), "south": PANEL("back_upper"),
    "east": PANEL("side_upper"), "west": PANEL("side_upper")})

for (x0, z0, tag) in [(1, 1, "nw"), (13, 1, "ne"), (1, 13, "sw"), (13, 13, "se")]:
    add("post_upper_" + tag, "body", "upper", [x0, 0, z0], [x0 + 2, 13, z0 + 2],
        all_faces(PANEL("brass"), omit=("up", "down")))

add("cap", "body", "upper", [1, 13, 1], [15, 15, 15], {
    "north": PANEL("cap_side"), "south": PANEL("cap_side"), "east": PANEL("cap_side"), "west": PANEL("cap_side"),
    "up": PANEL("top"), "down": PANEL("cap_side")})
add("vent", "body", "upper", [6, 15, 6], [10, 16, 10], {
    "up": SHEET("gunmetal_dark"), "north": SHEET("gunmetal"), "south": SHEET("gunmetal"),
    "east": SHEET("gunmetal"), "west": SHEET("gunmetal")})

# spigot boss (hose origin) on the east side of the upper block, towards the front
add("spigot_flange", "body", "upper", [14, 8, 3], [16, 12, 7], {
    "east": SHEET("brass_port"), "north": SHEET("brass"), "south": SHEET("brass"),
    "up": SHEET("brass"), "down": SHEET("brass")})
add("spigot_stub", "body", "upper", [16, 9, 4], [17, 11, 6], all_faces(SHEET("black"), omit=("west",)))

# holster cradle on the east side of the upper block: back plate, shelf, two lips
# Kept clear of the corner posts (z 1..3 / 13..15) and below the spigot (y 8..12) so no faces share
# a plane with them; the coupler seats a quarter pixel above the shelf for the same reason.
add("holster_plate", "holster", "upper", [14, 0, 4], [15, 7, 13], {
    "east": SHEET("brass"), "north": SHEET("brass"), "south": SHEET("brass"), "up": SHEET("brass")})
# The lips stand half a pixel clear of the coupler's bayonet lugs (z 3..4 and 12..13 when
# racked) so neither shares a plane with them.
add("holster_shelf", "holster", "upper", [15, 0, 1.5], [25, 1, 14.5], all_faces(SHEET("brass_dark"), omit=("west",)))
add("holster_lip_n", "holster", "upper", [15, 1, 1.5], [25, 3, 2.5], all_faces(SHEET("brass"), omit=("west", "down")))
add("holster_lip_s", "holster", "upper", [15, 1, 13.5], [25, 3, 14.5], all_faces(SHEET("brass"), omit=("west", "down")))

# drum-counter bezel: four brass bars around an open window, two dividers, decimal point
# The counter is a band across the whole front: three 3px drums (whole buckets, 000..999) with
# 1px dividers, and side bars sized so every digit window is exactly 3px wide. Drums sit at
# z 0.5, in front of the corner posts, and the band stands proud of them so nothing shares a plane.
# The band is 16 wide but the body is only 12 (x 2..14), so the bar ends overhang the sides and
# their backs are visible from behind: they keep their south face. It runs to z 2.01 rather than 2
# so that face sits a hundredth inside the body instead of in the same plane as its front.
add("bezel_top", "gauge_drum", "upper", [0, 11, 0], [16, 12, 2.01], all_faces(SHEET("brass")))
add("bezel_bottom", "gauge_drum", "upper", [0, 5, 0], [16, 6, 2.01], all_faces(SHEET("brass")))
add("bezel_left", "gauge_drum", "upper", [0, 6, 0], [2.5, 11, 2.01], all_faces(SHEET("brass")))
add("bezel_right", "gauge_drum", "upper", [13.5, 6, 0], [16, 11, 2.01], all_faces(SHEET("brass")))
DRUM_X = (2.5, 6.5, 10.5)
for i, x0 in enumerate((5.5, 9.5)):
    add("divider_%d" % i, "gauge_drum", "upper", [x0, 6, 0], [x0 + 1, 11, 1], all_faces(SHEET("brass_dark"), omit=("south",)))
# The digit drums are drawn by the block entity renderer (they roll); the boxes below only exist
# in the Blockbench project so the mechanism is visible there.
for i, x0 in enumerate(DRUM_X):
    add("drum_%d" % i, "gauge_drum", "bbmodel_only", [x0, 6, 0.5], [x0 + 3, 11, 1], {"north": ("special", "drum", i)})

# ---- Create's shaftless cogwheel (item model + Blockbench only; the game renders the partial) ----
# Geometry and UVs are Create's cogwheel_shaftless.json; UVs halved because that model declares
# texture_size 32 and ours address the 32x32 sheet in 0..16 units.
COG_FACES_ALONG = {"north": [3.5, 4, 8, 4.75], "east": [2.5, 4, 3.25, 4.75], "south": [3.5, 4, 8, 4.75],
                   "west": [2.5, 4, 3.25, 4.75], "up": [3.5, 3, 8, 3.75], "down": [3.5, 3, 8, 3.75]}
COG_FACES_ACROSS = {"north": [2.5, 4, 3.25, 4.75], "east": [3.5, 4, 8, 4.75], "south": [2.5, 4, 3.25, 4.75],
                    "west": [3.5, 4, 8, 4.75], "up": [3.5, 3, 8, 3.75], "down": [3.5, 3, 8, 3.75]}
add("cog_gear", "cog", "cog", [-1, 6.5, 6.5], [17, 9.5, 9.5], {d: ("cog", uv) for d, uv in COG_FACES_ALONG.items()})
add("cog_gear_2", "cog", "cog", [-1, 6.5, 6.5], [17, 9.5, 9.5], {d: ("cog", uv) for d, uv in COG_FACES_ALONG.items()},
    rotation={"angle": 45, "axis": "y", "origin": [8, 8, 8]})
add("cog_gear_3", "cog", "cog", [-1, 6.5, 6.5], [17, 9.5, 9.5], {d: ("cog", uv) for d, uv in COG_FACES_ALONG.items()},
    rotation={"angle": -45, "axis": "y", "origin": [8, 8, 8]})
add("cog_gear_4", "cog", "cog", [6.5, 6.5, -1], [9.5, 9.5, 17], {d: ("cog", uv) for d, uv in COG_FACES_ACROSS.items()})
add("cog_case_inner", "cog", "cog", [2, 6.55, 2], [14, 9.45, 14], {
    "north": ("cog", [0, 3, 3, 3.75]), "east": ("cog", [0, 3, 3, 3.75]), "south": ("cog", [0, 3, 3, 3.75]),
    "west": ("cog", [0, 3, 3, 3.75]), "up": ("cog", [2, 0, 5, 3]), "down": ("cog", [2, 0, 5, 3])})
add("cog_case_outer", "cog", "cog", [4, 6, 4], [12, 10, 12], {
    "north": ("cog", [0, 2, 2, 3]), "east": ("cog", [0, 2, 2, 3]), "south": ("cog", [0, 2, 2, 3]),
    "west": ("cog", [0, 2, 2, 3]), "up": ("cog", [0, 0, 2, 2]), "down": ("cog", [0, 0, 2, 2])})

# ---- coupler partial (own model space; locking face points -Y, hose inlet at (8, 12, 8)) -------
add("cpl_face", "nozzle", "nozzle", [4, 0, 4], [12, 2, 12], {
    "down": NOZ("copper_port"), "up": NOZ("copper"), "north": NOZ("copper"), "south": NOZ("copper"),
    "east": NOZ("copper"), "west": NOZ("copper")})
add("cpl_lug_n", "nozzle", "nozzle", [7, 0.5, 3], [9, 1.5, 4], all_faces(NOZ("brass"), omit=("south",)))
add("cpl_lug_s", "nozzle", "nozzle", [7, 0.5, 12], [9, 1.5, 13], all_faces(NOZ("brass"), omit=("north",)))
add("cpl_lug_e", "nozzle", "nozzle", [12, 0.5, 7], [13, 1.5, 9], all_faces(NOZ("brass"), omit=("west",)))
add("cpl_lug_w", "nozzle", "nozzle", [3, 0.5, 7], [4, 1.5, 9], all_faces(NOZ("brass"), omit=("east",)))
add("cpl_body", "nozzle", "nozzle", [5, 2, 5], [11, 8, 11], all_faces(NOZ("copper"), omit=("down",)))
add("cpl_grip", "nozzle", "nozzle", [4.5, 4, 4.5], [11.5, 5.5, 11.5], all_faces(NOZ("brass_dark")))
add("cpl_neck", "nozzle", "nozzle", [6, 8, 6], [10, 10, 10], all_faces(NOZ("gunmetal_dark"), omit=("down",)))
add("cpl_lever", "nozzle", "nozzle", [11, 5.5, 7], [14.5, 6.5, 9], all_faces(NOZ("brass"), omit=("west",)))
add("cpl_ferrule", "nozzle", "nozzle", [6.5, 10, 6.5], [9.5, 12, 9.5], all_faces(NOZ("copper"), omit=("down",)))

NOZZLE_INLET_LOCAL = (8, 12, 8)
# Where the coupler's inlet sits when racked, in lower-block pixels (mirrors PumpGeometry.java).
HOLSTER_INLET_PX = (20.5, 29.25, 8)
# Hose origin: 1px outside the spigot stub, in lower-block pixels (upper block y + 16).
SPIGOT_ANCHOR_PX = (17.5, 26, 5)

# --------------------------------------------------------------------------------------
# Panel textures (16x16, auto-UV'd, designed left/right symmetric)
# --------------------------------------------------------------------------------------
def tex_plinth():
    """Plinth sides (y 0..6 -> rows 10..15): copper with a Create-style dark chevron pointing up."""
    img = new(16, 16, CU_4)
    rect(img, 0, 10, 15, 10, CU_6)
    rect(img, 0, 15, 15, 15, CU_0)
    rect(img, 0, 14, 15, 14, CU_1)
    up_arrow(img, 8, 10, CU_ARROW)
    rect(img, 7, 15, 8, 15, CU_ARROW)
    return img


def tex_plinth_top():
    img = new(16, 16, CU_1)
    outline(img, 0, 0, 15, 15, CU_4)
    rect(img, 2, 2, 13, 13, CU_0)
    return img


def tex_bottom():
    """Underside: the pipe port fuel is pulled through, sized for Create's 4px pipe bore."""
    img = new(16, 16, CU_4)
    outline(img, 0, 0, 15, 15, CU_0)
    rect(img, 1, 1, 14, 14, CU_3)
    pipe_port(img, 4, 4, 11, 11, ring=BR_2)
    return img


def tex_brass():
    """Vertical brass bars so any 2px slice reads as a bevelled post (posts occupy cols 1-2, 13-14)."""
    img = new(16, 16, BR_3)
    for x in range(16):
        c = {0: BR_0, 1: BR_5, 2: BR_3, 3: BR_1}[x % 4]
        rect(img, x, 0, x, 15, c)
    for y in (4, 9, 14):
        for x in range(2, 16, 4):
            px(img, x, y, BR_0)
    return img


def tex_cap_side():
    img = new(16, 16, CU_4)
    rect(img, 0, 1, 15, 1, BR_4)      # cap spans y 13..15 -> rows 1..2: brass lip over copper
    rect(img, 0, 2, 15, 2, CU_1)
    return img


def tex_top():
    img = new(16, 16, CU_4)
    copper_panel(img, 1, 1, 14, 14)
    nested_boss(img, 8, 8, 5)
    rect(img, 4, 4, 11, 11, CU_4)
    cog(img, 8, 8, 4.4, BR_3)
    return img


def tex_side_lower():
    """Short lower cabinet (y 10..16 -> rows 0..5): a plain bevelled copper panel."""
    img = new(16, 16, CU_4)
    copper_panel(img, 2, 0, 13, 5)
    return img


def tex_side_upper():
    """Plain bevelled copper with three shallow grooves."""
    img = new(16, 16, CU_4)
    copper_panel(img, 2, 3, 13, 15)
    for y in (6, 9, 12):
        rect(img, 4, y, 11, y, CU_1)
        px(img, 4, y, CU_6)
    return img


def tex_back_lower():
    return tex_side_lower()


def tex_back_upper():
    return tex_side_upper()


def tex_front_lower():
    return tex_side_lower()


def tex_front_upper():
    img = new(16, 16, CU_4)
    copper_panel(img, 2, 3, 13, 15)
    # counter window (y 6..11 -> rows 5..10): black behind the drums
    rect(img, 1, 5, 14, 10, BLACK)
    # brass nameplate under the window
    rect(img, 5, 12, 10, 13, BR_3)
    outline(img, 5, 12, 10, 13, BR_0)
    return img


PANELS = {
    "plinth": tex_plinth, "plinth_top": tex_plinth_top, "bottom": tex_bottom, "brass": tex_brass,
    "cap_side": tex_cap_side, "top": tex_top, "side_lower": tex_side_lower, "side_upper": tex_side_upper,
    "back_lower": tex_back_lower, "back_upper": tex_back_upper,
    "front_lower": tex_front_lower, "front_upper": tex_front_upper,
}


# --------------------------------------------------------------------------------------
# Special (non-atlas) textures: hose tile, ferrule tile, drum digit strip
# --------------------------------------------------------------------------------------
def tex_hose():
    """
    16x16 tile. U runs along the hose (one tile per block of length), V wraps the circumference
    with the seam (row 0 / row 15) on the underside. Black power-cord rubber with two spiral ribs
    (one row per column, Power Grid's iron_wire rule) so the ribbing winds along the tube.
    """
    img = new(16, 16, RB_3)
    # The tube is square: rows 0-3 underside, 4-7 one flank, 8-11 top, 12-15 the other flank.
    shade = [RB_1, RB_1, RB_1, RB_2, RB_3, RB_3, RB_4, RB_3, RB_6, RB_6, RB_5, RB_5, RB_3, RB_4, RB_3, RB_3]
    for y in range(16):
        rect(img, 0, y, 15, y, shade[y])
    px(img, 0, 9, RB_7)
    px(img, 8, 9, RB_7)
    for x in range(16):
        for start in (0, 8):
            yy = (x + start) % 16
            px(img, x, yy, RB_0 if shade[yy] in (RB_1, RB_2) else RB_2)
            px(img, x, (yy + 1) % 16, RB_1 if shade[(yy + 1) % 16] in (RB_1, RB_2) else RB_3)
    return img


def tex_ferrule():
    img = new(16, 16, PG_CU_1)
    for x in range(16):
        if x % 4 in (0, 1):
            rect(img, x, 0, x, 15, PG_CU_0)
    for y in (6, 7, 8):
        for x in range(16):
            if x % 4 in (2, 3):
                px(img, x, y, PG_CU_2)
    return img


DRUM_CELL = 6


def tex_drum():
    """8 x 60 strip: ten 6px cells, each a 3x5 cream digit on a dark drum face with a 1px seam."""
    img = new(8, DRUM_CELL * 10, GM_1)
    for d in range(10):
        y0 = d * DRUM_CELL
        rect(img, 0, y0, 7, y0, BLACK)
        rect(img, 0, y0 + 1, 7, y0 + 5, GM_3)
        rect(img, 4, y0 + 1, 7, y0 + 5, GM_1)
        digit(img, 0, y0 + 1, str(d), CREAM)
    return img


def tex_cog_standin():
    """Rough copy of Create's cogwheel.png layout, only so the Blockbench file shows a wooden cog."""
    img = new(32, 32, CLEAR)
    rect(img, 0, 0, 9, 11, WD_2)          # hub top/sides
    outline(img, 0, 0, 9, 11, WD_0)
    rect(img, 4, 0, 19, 11, WD_3)         # inner case
    outline(img, 4, 0, 19, 11, WD_1)
    rect(img, 5, 6, 31, 9, WD_1)          # tooth strips
    for x in range(7, 32, 2):
        rect(img, x, 6, x, 9, WD_4)
    rect(img, 5, 8, 31, 9, WD_2)
    return img


# --------------------------------------------------------------------------------------
# Model assembly: resolve every face to a texture + UV, painting sheet faces as we go
# --------------------------------------------------------------------------------------
SHEETS = {"parts": Sheet("parts", 64), "nozzle": Sheet("nozzle", 32)}


def face_size(e, d):
    dx = e.to[0] - e.frm[0]
    dy = e.to[1] - e.frm[1]
    dz = e.to[2] - e.frm[2]
    if d in ("north", "south"):
        return dx, dy
    if d in ("east", "west"):
        return dz, dy
    return dx, dz


def paint_decimal(img, rx, ry, w, h, elem):
    """Decimal point on the bottom bezel bar, under the middle divider (buckets . hundredths)."""
    paint_material(img, rx, ry, w, h, "brass")
    # north face: u grows towards -x, so offset from the element's east edge
    u = elem.to[0] - 8.5
    px(img, rx + int(u), ry, CREAM)


def resolve_face(e, d, spec):
    kind = spec[0]
    if kind == "panel":
        return {"texture": "#" + spec[1]}
    if kind == "cog":
        return {"texture": "#cog", "uv": spec[1]}
    if kind == "special":
        return None
    _, sheet_name, mat = spec[0], spec[1], spec[2]
    painter = spec[3] if len(spec) > 3 else None
    sheet = SHEETS[sheet_name]
    w, h = face_size(e, d)
    key = None if painter else (mat, int(math.ceil(w)), int(math.ceil(h)))
    (rx, ry, rw, rh), fresh = sheet.alloc(w, h, key)
    if fresh:
        if painter == "decimal":
            paint_decimal(sheet.img, rx, ry, rw, rh, e)
        else:
            paint_material(sheet.img, rx, ry, rw, rh, mat)
    k = 16.0 / sheet.size
    return {"texture": "#" + sheet_name, "uv": [rx * k, ry * k, (rx + w) * k, (ry + h) * k]}


def element_json(e, y_offset=0):
    faces = {}
    for d, spec in e.faces.items():
        f = resolve_face(e, d, spec)
        if f is not None:
            faces[d] = f
    out = {
        "name": e.name,
        "from": [e.frm[0], e.frm[1] + y_offset, e.frm[2]],
        "to": [e.to[0], e.to[1] + y_offset, e.to[2]],
        "faces": faces,
    }
    if e.rotation:
        rot = dict(e.rotation)
        rot["origin"] = [rot["origin"][0], rot["origin"][1] + y_offset, rot["origin"][2]]
        out["rotation"] = rot
    return out


def textures_block(prefix="westernpump:block/fuel_pump_", with_cog=False):
    t = {k: prefix + k for k in PANELS}
    t["parts"] = prefix + "parts"
    t["nozzle"] = prefix + "nozzle"
    if with_cog:
        t["cog"] = "create:block/cogwheel"
    t["particle"] = prefix + "side_upper"
    return t


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print("wrote", os.path.relpath(path, ROOT))


# --------------------------------------------------------------------------------------
# Rest-pose hose for the Blockbench preview (same catenary as HoseCurve.java)
# --------------------------------------------------------------------------------------
def catenary_points(p1, p2, length, segments):
    dxv = (p2[0] - p1[0], p2[2] - p1[2])
    dx = math.hypot(*dxv)
    dy = p2[1] - p1[1]
    if dx < 1e-4:
        return [(p1[0], p1[1] + dy * i / segments, p1[2]) for i in range(segments + 1)]
    nx, nz = dxv[0] / dx, dxv[1] / dx
    r = math.sqrt(max(length * length - dy * dy, 1e-9)) / dx
    if r <= 1:
        return [(p1[0] + (p2[0] - p1[0]) * i / segments, p1[1] + dy * i / segments,
                 p1[2] + (p2[2] - p1[2]) * i / segments) for i in range(segments + 1)]
    A = math.sqrt(6 * (r - 1)) if r < 3 else math.log(2 * r) + math.log(math.log(2 * r))
    for _ in range(8):
        A -= (math.sinh(A) - r * A) / (math.cosh(A) - r)
    a = dx / (2 * A)
    z = dy / length
    b = -a * 0.5 * math.log((1 + z) / (1 - z))
    c = 0.5 * (dy - length / math.tanh(A))
    f = lambda x: a * math.cosh((x - b) / a) + c
    base = f(-dx / 2)
    pts = []
    for i in range(segments + 1):
        x = -dx / 2 + dx * i / segments
        along = x + dx / 2
        pts.append((p1[0] + nx * along, p1[1] + f(x) - base, p1[2] + nz * along))
    return pts


def hose_preview_elements():
    p1 = tuple(v / 16.0 for v in SPIGOT_ANCHOR_PX)
    p2 = tuple(v / 16.0 for v in HOLSTER_INLET_PX)
    straight = math.dist(p1, p2)
    pts = catenary_points(p1, p2, straight * 1.25 + 0.15, 12)
    elems = []
    r = 1.4
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        frm = [min(a[k], b[k]) * 16 - r for k in range(3)]
        to = [max(a[k], b[k]) * 16 + r for k in range(3)]
        elems.append(Elem("hose_segment_%02d" % i, "hose_segment_%02d" % i, "bbmodel_only", frm, to,
                          all_faces(("special", "hose", 0))))
    return elems


# --------------------------------------------------------------------------------------
# Blockbench project
# --------------------------------------------------------------------------------------
def png_b64(img):
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")


def bbmodel(textures_imgs, elements_json, groups):
    tex_entries = []
    for i, (name, img) in enumerate(textures_imgs):
        tex_entries.append({
            "path": "", "name": name + ".png", "folder": "block", "namespace": "westernpump",
            "id": str(i), "particle": name == "fuel_pump_side_upper", "render_mode": "default",
            "visible": True, "mode": "bitmap", "saved": True, "uuid": str(uuid.uuid4()),
            "uv_width": 16, "uv_height": 16, "source": png_b64(img),
        })
    return {
        "meta": {"format_version": "4.9", "model_format": "java_block", "box_uv": False},
        "name": "fuel_pump",
        "model_identifier": "fuel_pump",
        "visible_box": [1, 1, 0],
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "timeline_setups": [],
        "unhandled_root_fields": {},
        "resolution": {"width": 16, "height": 16},
        "elements": elements_json,
        "outliner": groups,
        "textures": tex_entries,
    }


def auto_uv(e, d, y_offset=0):
    x0, y0, z0 = e.frm[0], e.frm[1] + y_offset, e.frm[2]
    x1, y1, z1 = e.to[0], e.to[1] + y_offset, e.to[2]
    y0, y1 = y0 % 16, ((y1 - 1) % 16) + 1
    if d == "north":
        return [16 - x1, 16 - y1, 16 - x0, 16 - y0]
    if d == "south":
        return [x0, 16 - y1, x1, 16 - y0]
    if d == "east":
        return [16 - z1, 16 - y1, 16 - z0, 16 - y0]
    if d == "west":
        return [z0, 16 - y1, z1, 16 - y0]
    if d == "up":
        return [x0, z0, x1, z1]
    return [x0, 16 - z1, x1, 16 - z0]


# --------------------------------------------------------------------------------------
def main():
    for d in (TEX_BLOCK, TEX_SPECIAL, MODELS_BLOCK, MODELS_ITEM, DOCS):
        os.makedirs(d, exist_ok=True)

    lower = [e for e in ELEMS if e.model == "lower"]
    upper = [e for e in ELEMS if e.model == "upper"]
    nozzle = [e for e in ELEMS if e.model == "nozzle"]
    cog_elems = [e for e in ELEMS if e.model == "cog"]

    lower_json = [element_json(e) for e in lower]
    upper_json = [element_json(e) for e in upper]
    nozzle_json = [element_json(e) for e in nozzle]
    cog_json = [element_json(e) for e in cog_elems]

    tex = textures_block()
    write_json(os.path.join(MODELS_BLOCK, "fuel_pump_lower.json"),
               {"textures": tex, "elements": lower_json})
    write_json(os.path.join(MODELS_BLOCK, "fuel_pump_upper.json"),
               {"textures": tex, "elements": upper_json})
    write_json(os.path.join(MODELS_BLOCK, "fuel_pump", "nozzle.json"),
               {"textures": {"nozzle": tex["nozzle"], "particle": tex["particle"]}, "elements": nozzle_json})

    # Item: both halves stacked with a static cog, shown small.
    item_elems = lower_json + cog_json + [element_json(e, y_offset=16) for e in upper]
    write_json(os.path.join(MODELS_ITEM, "fuel_pump.json"), {
        "textures": textures_block(with_cog=True),
        "elements": item_elems,
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [0, -3.5, 0], "scale": [0.42, 0.42, 0.42]},
            "ground": {"translation": [0, 0, 0], "scale": [0.25, 0.25, 0.25]},
            "fixed": {"translation": [0, -4, 0], "scale": [0.4, 0.4, 0.4]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 1, 0], "scale": [0.3, 0.3, 0.3]},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 1, 0], "scale": [0.3, 0.3, 0.3]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -2, 0], "scale": [0.3, 0.3, 0.3]},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, -2, 0], "scale": [0.3, 0.3, 0.3]},
        },
    })

    # Textures.
    panel_imgs = {k: fn() for k, fn in PANELS.items()}
    for k, img in panel_imgs.items():
        img.save(os.path.join(TEX_BLOCK, "fuel_pump_%s.png" % k))
    for name, sheet in SHEETS.items():
        sheet.img.save(os.path.join(TEX_BLOCK, "fuel_pump_%s.png" % name))
    hose = tex_hose()
    ferrule = tex_ferrule()
    drum = tex_drum()
    hose.save(os.path.join(TEX_SPECIAL, "fuel_pump_hose.png"))
    ferrule.save(os.path.join(TEX_SPECIAL, "fuel_pump_ferrule.png"))
    drum.save(os.path.join(TEX_SPECIAL, "fuel_pump_drum.png"))
    print("wrote textures to", os.path.relpath(TEX_BLOCK, ROOT), "and", os.path.relpath(TEX_SPECIAL, ROOT))

    # Blockbench project: lower + cog + upper stacked, coupler posed in the holster, hose preview.
    tex_list = [("fuel_pump_" + k, img) for k, img in panel_imgs.items()]
    tex_list += [("fuel_pump_parts", SHEETS["parts"].img), ("fuel_pump_nozzle", SHEETS["nozzle"].img),
                 ("fuel_pump_hose", hose), ("fuel_pump_drum", drum), ("cogwheel", tex_cog_standin())]
    tex_index = {name: i for i, (name, _) in enumerate(tex_list)}

    def bb_elem(e, y_offset=0, xyz_offset=(0, 0, 0)):
        j = element_json(e, y_offset)
        faces = {}
        for d in ("north", "south", "east", "west", "up", "down"):
            spec = e.faces.get(d)
            if spec is None:
                continue
            if spec[0] == "panel":
                faces[d] = {"uv": auto_uv(e, d, y_offset), "texture": tex_index["fuel_pump_" + spec[1]]}
            elif spec[0] == "cog":
                faces[d] = {"uv": spec[1], "texture": tex_index["cogwheel"]}
            elif spec[0] == "special":
                faces[d] = {"uv": [0, 0, 4, 4] if spec[1] == "hose" else [0, spec[2] * 2, 1.5, spec[2] * 2 + 2.5],
                            "texture": tex_index["fuel_pump_" + spec[1]]}
            else:
                faces[d] = {"uv": j["faces"][d]["uv"], "texture": tex_index["fuel_pump_" + spec[1]]}
        out = {
            "name": e.name, "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
            "allow_mirror_modeling": True,
            "from": [j["from"][0] + xyz_offset[0], j["from"][1] + xyz_offset[1], j["from"][2] + xyz_offset[2]],
            "to": [j["to"][0] + xyz_offset[0], j["to"][1] + xyz_offset[1], j["to"][2] + xyz_offset[2]],
            "autouv": 0, "color": 0, "origin": [8, 8, 8], "faces": faces, "type": "cube", "uuid": e.uuid,
        }
        if e.rotation:
            out["rotation"] = [0, e.rotation["angle"], 0]
            out["origin"] = e.rotation["origin"]
        return out

    bb_elements = []
    groups = {}

    def group(name):
        if name not in groups:
            groups[name] = {"name": name, "origin": [8, 8, 8], "color": 0, "uuid": str(uuid.uuid4()),
                            "export": True, "mirror_uv": False, "isOpen": False, "locked": False,
                            "visibility": True, "autouv": 0, "children": []}
        return groups[name]

    for e in lower + cog_elems:
        bb_elements.append(bb_elem(e))
        group(e.group)["children"].append(e.uuid)
    for e in upper:
        bb_elements.append(bb_elem(e, y_offset=16))
        group(e.group)["children"].append(e.uuid)
    for e in ELEMS:
        if e.model == "bbmodel_only":
            bb_elements.append(bb_elem(e, y_offset=16))
            group(e.group)["children"].append(e.uuid)
    noz_off = tuple(HOLSTER_INLET_PX[i] - NOZZLE_INLET_LOCAL[i] for i in range(3))
    for e in nozzle:
        bb_elements.append(bb_elem(e, xyz_offset=noz_off))
        group("nozzle")["children"].append(e.uuid)
    for e in hose_preview_elements():
        bb_elements.append(bb_elem(e))
        group(e.group)["children"].append(e.uuid)

    order = ["base", "cog", "body", "gauge_drum", "holster", "nozzle"] + sorted(g for g in groups if g.startswith("hose_segment"))
    write_json(os.path.join(DOCS, "fuel_pump.bbmodel"),
               bbmodel(tex_list, bb_elements, [groups[g] for g in order if g in groups]))


if __name__ == "__main__":
    main()
