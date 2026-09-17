#!/usr/bin/env python3
"""
Generates the Western Pump block textures.

Deliberately programmer art: flat Create-ish palette, hand-placed pixels, a cog motif on the
cabinet front so it reads as a Create block. Re-run after editing to regenerate:

    python tools/generate_textures.py

Requires Pillow. Output goes to src/main/resources/assets/westernpump/textures/block/.
"""
import math
import os
from PIL import Image

S = 16  # texture size
OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "src", "main", "resources", "assets", "westernpump", "textures", "block",
)

# Create's industrial palette: gunmetal iron, brass trim, oxidised red for the pump shell.
IRON_DARK = (60, 62, 70)
IRON = (86, 90, 100)
IRON_LIGHT = (116, 121, 133)
BRASS_DARK = (139, 105, 45)
BRASS = (186, 143, 62)
BRASS_LIGHT = (222, 179, 88)
RED_DARK = (118, 40, 38)
RED = (158, 56, 50)
RED_LIGHT = (190, 78, 66)
GLASS_DARK = (44, 58, 62)
GLASS = (96, 148, 152)
BLACK = (28, 28, 32)


def new_img(color=IRON):
    return Image.new("RGBA", (S, S), color + (255,))


def px(img, x, y, color):
    if 0 <= x < S and 0 <= y < S:
        img.putpixel((x, y), color + (255,) if len(color) == 3 else color)


def rect(img, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(img, x, y, color)


def border(img, x0, y0, x1, y1, color):
    for x in range(x0, x1 + 1):
        px(img, x, y0, color)
        px(img, x, y1, color)
    for y in range(y0, y1 + 1):
        px(img, x0, y, color)
        px(img, x1, y, color)


def noise_shade(img, light=IRON_LIGHT, dark=IRON_DARK):
    """A few fixed specks so flat metal is not perfectly uniform."""
    for (x, y) in [(2, 3), (11, 2), (4, 12), (13, 9), (7, 14)]:
        px(img, x, y, dark)
    for (x, y) in [(3, 2), (12, 3), (5, 11), (10, 13)]:
        px(img, x, y, light)


def draw_cog(img, cx, cy, radius, color, teeth=8):
    """Small cog silhouette — the visual cue that this is a Create block."""
    for y in range(S):
        for x in range(S):
            dx, dy = x - cx, y - cy
            d = math.sqrt(dx * dx + dy * dy)
            if d <= radius - 1.2:
                ang = math.atan2(dy, dx)
                # Hollow centre
                if d >= radius - 2.6:
                    px(img, x, y, color)
                elif d <= 1.3:
                    px(img, x, y, color)
            elif d <= radius:
                ang = math.atan2(dy, dx)
                # Teeth around the rim
                if (int((ang + math.pi) / (2 * math.pi) * teeth * 2) % 2) == 0:
                    px(img, x, y, color)


def pump_side():
    img = new_img(IRON)
    noise_shade(img)
    border(img, 0, 0, 15, 15, IRON_DARK)
    # Vertical brass seam suggesting a riveted panel
    rect(img, 7, 1, 8, 14, IRON_LIGHT)
    for y in range(2, 15, 3):
        px(img, 7, y, BRASS)
    return img


def pump_front_lower():
    """Lower cabinet front: cog motif plus the hose collar."""
    img = new_img(RED)
    border(img, 0, 0, 15, 15, RED_DARK)
    rect(img, 1, 1, 14, 3, RED_LIGHT)
    # Cog badge
    draw_cog(img, 8, 7, 4.6, BRASS)
    draw_cog(img, 8, 7, 2.0, RED_DARK)
    # Hose collar at the bottom right, where the renderer anchors the hose
    rect(img, 10, 12, 13, 15, IRON_DARK)
    rect(img, 11, 13, 12, 15, BLACK)
    # Brass footplate
    rect(img, 1, 14, 8, 15, BRASS_DARK)
    return img


def pump_front_upper():
    """Upper cabinet front: the price/volume display."""
    img = new_img(RED)
    border(img, 0, 0, 15, 15, RED_DARK)
    # Display bezel
    rect(img, 2, 3, 13, 10, IRON_DARK)
    rect(img, 3, 4, 12, 9, GLASS_DARK)
    # Readout segments
    for x in range(4, 12, 3):
        rect(img, x, 5, x + 1, 5, GLASS)
        rect(img, x, 8, x + 1, 8, GLASS)
        px(img, x, 6, GLASS)
        px(img, x + 1, 7, GLASS)
    # Brass top trim and a small lamp
    rect(img, 1, 1, 14, 2, BRASS_DARK)
    rect(img, 7, 12, 9, 13, BRASS_LIGHT)
    return img


def pump_top():
    img = new_img(IRON)
    noise_shade(img)
    border(img, 0, 0, 15, 15, IRON_DARK)
    rect(img, 4, 4, 11, 11, IRON_LIGHT)
    draw_cog(img, 8, 8, 3.4, BRASS_DARK)
    return img


def pump_bottom():
    img = new_img(IRON_DARK)
    border(img, 0, 0, 15, 15, BLACK)
    rect(img, 3, 3, 12, 12, IRON)
    return img


def nozzle():
    """Item/partial texture for the nozzle head."""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rect(img, 5, 2, 10, 8, IRON)
    border(img, 5, 2, 10, 8, IRON_DARK)
    rect(img, 6, 3, 9, 4, BRASS)
    # Spout
    rect(img, 7, 9, 8, 14, IRON_LIGHT)
    px(img, 7, 15, BLACK)
    px(img, 8, 15, BLACK)
    # Trigger guard
    rect(img, 3, 6, 4, 7, IRON_DARK)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    images = {
        "fuel_pump_side": pump_side(),
        "fuel_pump_front_lower": pump_front_lower(),
        "fuel_pump_front_upper": pump_front_upper(),
        "fuel_pump_top": pump_top(),
        "fuel_pump_bottom": pump_bottom(),
        "fuel_pump_nozzle": nozzle(),
    }
    for name, img in images.items():
        path = os.path.normpath(os.path.join(OUT, name + ".png"))
        img.save(path)
        print("wrote", path)


if __name__ == "__main__":
    main()
