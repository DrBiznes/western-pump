#!/usr/bin/env python3
"""
Renders the README / Modrinth banner: the pump in isometric on the left, feature text on the right.

    python tools/generate_banner.py

Layout follows the Create-addon banner convention (see the Cassette Deck banner): a wide plate in
the mod's own metal colours, a thin light rail top and bottom, the item rendered large on the left,
and a hand-lettered title over a short bullet list on the right.

The pump is rendered by generate_icon.py's raycaster, so the banner, the icon and the block model
in game all come from the one geometry table in generate_art.py.

Writes docs/banner.png (1024x320) and copies the icon beside it.

Requires Pillow.
"""
import math
import os
import shutil

from PIL import Image

import generate_art as ga
import generate_icon as gi

ROOT = ga.ROOT
DOCS = ga.DOCS
OUT = os.path.join(DOCS, "banner.png")
ICON_SRC = os.path.join(ROOT, "src", "main", "resources", "westernpump_icon.png")
ICON_COPY = os.path.join(DOCS, "icon.png")

W, H = 1024, 320
SS = 3  # supersample for the whole banner, so the lettering gets clean edges

# The plate is a warm brown well below the pump's copper, so the pump's own CU_4..CU_7 body reads
# as the bright object against it — the reference banner leans on exactly this value gap.
PLATE = ga.hx("#5d3a2a")
PLATE_DARK = ga.hx("#46291d")
RAIL = ga.hx("#c97c4e")
INK = ga.hx("#f6dcb4")          # warm cream lettering, the way Create sets type on dark panels
INK_SOFT = ga.hx("#cfae83")     # body text, a touch down from the title
INK_HI = ga.hx("#ffd54a")       # the accent Create's _highlight_ markers render in

TITLE = "WESTERN PUMP"

# The lines below are the item tooltip, trimmed to banner length. `_word_` is Create's own
# highlight syntax (see the .summary / .behaviour1 keys in lang/en_us.json and FuelPumpTooltip),
# and draw_text renders those runs in INK_HI exactly as the in-game tooltip accents them.
# The range matches PumpConfig.maxHoseLength's default of 12.
BULLETS = [
    "PUMPS _FUEL_ INTO _ANY_ _TANK_ YOU ATTACH THE NOZZLE TO",
    "ACTS AS ITS OWN _MECHANICAL_ _PUMP_ - NEEDS ONLY _ROTATIONAL_ _FORCE_",
    "REACHES _12_ _BLOCKS_, AND STAYS ATTACHED TO _AERONAUTICS_ AIRCRAFT",
]


# --------------------------------------------------------------------------------------
# A chunky 5x7 pixel alphabet, drawn in the blocky hand of the reference banner.
# --------------------------------------------------------------------------------------
GLYPHS = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01111", "10000", "10000", "10000", "10000", "10000", "01111"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "F": ["11111", "10000", "10000", "11110", "10000", "10000", "10000"],
    "G": ["01111", "10000", "10000", "10011", "10001", "10001", "01111"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "J": ["00111", "00010", "00010", "00010", "00010", "10010", "01100"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10101", "10011", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "X": ["10001", "10001", "01010", "00100", "01010", "10001", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "Z": ["11111", "00001", "00010", "00100", "01000", "10000", "11111"],
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00110", "01000", "10000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["01110", "10000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00001", "01110"],
    "+": ["00000", "00100", "00100", "11111", "00100", "00100", "00000"],
    "-": ["00000", "00000", "00000", "11111", "00000", "00000", "00000"],
    ".": ["00000", "00000", "00000", "00000", "00000", "01100", "01100"],
    ",": ["00000", "00000", "00000", "00000", "01100", "01100", "01000"],
    "!": ["00100", "00100", "00100", "00100", "00100", "00000", "00100"],
    ":": ["00000", "01100", "01100", "00000", "01100", "01100", "00000"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    "'": ["00100", "00100", "00000", "00000", "00000", "00000", "00000"],
    " ": ["00000", "00000", "00000", "00000", "00000", "00000", "00000"],
}

GLYPH_W, GLYPH_H = 5, 7


def parse_runs(s):
    """
    Split Create's `_highlight_` markup into (text, highlighted) runs. Underscores only open or
    close a run, so they never take up space; everything else is literal.
    """
    runs = []
    buf = ""
    hot = False
    for ch in s:
        if ch == "_":
            if buf:
                runs.append((buf, hot))
                buf = ""
            hot = not hot
        else:
            buf += ch
    if buf:
        runs.append((buf, hot))
    return runs


def text_width(s, scale, tracking):
    n = sum(len(t) for t, _ in parse_runs(s))
    return n * (GLYPH_W * scale + tracking) - tracking if n else 0


def draw_text(img, x, y, s, scale, colour, tracking, highlight=None):
    """
    Blit a string at `scale` pixels per glyph pixel, honouring `_highlight_` runs. Returns the
    width drawn.
    """
    d = img.load()
    cx = x
    for text, hot in parse_runs(s.upper()):
        c = highlight if (hot and highlight is not None) else colour
        for ch in text:
            rows = GLYPHS.get(ch, GLYPHS[" "])
            for ry, row in enumerate(rows):
                for rx, bit in enumerate(row):
                    if bit != "1":
                        continue
                    for sy in range(scale):
                        for sx in range(scale):
                            px, py = cx + rx * scale + sx, y + ry * scale + sy
                            if 0 <= px < img.size[0] and 0 <= py < img.size[1]:
                                d[px, py] = c
            cx += GLYPH_W * scale + tracking
    return cx - x


# --------------------------------------------------------------------------------------
# Plate
# --------------------------------------------------------------------------------------
def build_plate(w, h):
    """The background: flat copper with a lighter rail top and bottom, like the reference."""
    img = Image.new("RGBA", (w, h), PLATE)
    d = img.load()
    rail = max(2, h // 26)
    edge = max(1, h // 64)
    for y in range(h):
        if y < rail or y >= h - rail:
            c = RAIL
        elif y < rail + edge or y >= h - rail - edge:
            c = PLATE_DARK
        else:
            continue
        for x in range(w):
            d[x, y] = c
    return img


def render_pump(height_px):
    """The pump on transparent background, sized to `height_px` tall."""
    boxes = gi.build_scene()
    # Render square then crop to the silhouette, so we control the final height exactly.
    img = gi.render(boxes, height_px, 1)
    bb = img.getbbox()
    return img.crop(bb) if bb else img


# --------------------------------------------------------------------------------------
def main():
    w, h = W * SS, H * SS
    img = build_plate(w, h)

    # ---- pump on the left -------------------------------------------------------------
    pump = render_pump(int(h * 1.30))
    target_h = int(h * 0.72)
    scale = target_h / pump.size[1]
    pump = pump.resize((max(1, int(pump.size[0] * scale)), target_h), Image.LANCZOS)
    px = int(w * 0.045)
    py = (h - pump.size[1]) // 2
    img.paste(pump, (px, py), pump)

    # ---- text on the right ------------------------------------------------------------
    tx = px + pump.size[0] + int(w * 0.055)

    avail = w - tx - int(w * 0.035)

    title_scale = max(1, int(h * 0.150) // GLYPH_H)
    title_track = title_scale * 2
    tw = text_width(TITLE, title_scale, title_track)

    # The tooltip lines are long, so size the body type to the widest one rather than to a fixed
    # fraction of the height: pick the largest scale whose longest line still fits the column.
    bullets = ["- " + b for b in BULLETS]
    bullet_scale = max(1, int(h * 0.068) // GLYPH_H)
    while bullet_scale > 1:
        track = max(1, bullet_scale // 2)
        if max(text_width(b, bullet_scale, track) for b in bullets) <= avail:
            break
        bullet_scale -= 1
    bullet_track = max(1, bullet_scale // 2)
    bw = max(text_width(b, bullet_scale, bullet_track) for b in bullets)

    block_w = max(tw, bw)
    ty = int(h * 0.145)
    draw_text(img, tx + (block_w - tw) // 2, ty, TITLE, title_scale, INK, title_track)

    # Underline, hand-drawn thickness, like the reference's stroke under the title.
    ul_y = ty + GLYPH_H * title_scale + title_scale * 2
    ul_h = max(1, title_scale)
    d = img.load()
    for yy in range(ul_y, ul_y + ul_h):
        for xx in range(tx + (block_w - tw) // 2, tx + (block_w - tw) // 2 + tw):
            d[xx, yy] = INK

    by = ul_y + ul_h + int(h * 0.12)
    line_h = GLYPH_H * bullet_scale + int(h * 0.050)
    for i, b in enumerate(bullets):
        draw_text(img, tx, by + i * line_h, b, bullet_scale, INK_SOFT, bullet_track, INK_HI)

    out = img.resize((W, H), Image.LANCZOS)
    out.save(OUT)
    print("wrote", os.path.relpath(OUT, ROOT), out.size)

    if os.path.exists(ICON_SRC):
        shutil.copyfile(ICON_SRC, ICON_COPY)
        print("copied", os.path.relpath(ICON_COPY, ROOT))
    else:
        print("icon not found at", ICON_SRC, "- run tools/generate_icon.py first")


if __name__ == "__main__":
    main()
