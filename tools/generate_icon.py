#!/usr/bin/env python3
"""
Renders the mod icon: the fuel pump in isometric projection over Create's logo background.

    python tools/generate_icon.py

Geometry, palette and textures are imported from generate_art.py rather than copied, so the icon
can never drift from the model the game actually loads. The pump is assembled as the Blockbench
preview assembles it (lower block + cogwheel + upper block stacked, coupler racked in the holster)
and then raycast in a true isometric camera: for every output pixel a ray is walked through the
scene and the nearest box face is sampled from the same texture the block model points at.

The hose is deliberately NOT drawn. In game it is a swept tube between the spigot and wherever the
nozzle currently is; in a still, three-quarter view it only ever crossed in front of the coupler and
read as a dark smear, so the icon shows the pump with its nozzle racked instead.

The drum counter shows DISPLAY_READING, since the rolling digits are drawn by the block entity
renderer at runtime and the static model has no digits of its own.

Writes src/main/resources/westernpump_icon.png (referenced by logoFile in neoforge.mods.toml).

Requires Pillow.
"""
import math
import os

from PIL import Image, ImageFilter

import generate_art as ga

ROOT = ga.ROOT
BACKGROUND = os.path.join(ga.DOCS, "New_Create_Logo_Background.webp")
OUT = os.path.join(ROOT, "src", "main", "resources", "westernpump_icon.png")

SIZE = 1080          # icon is square, matching the background
SUPERSAMPLE = 3      # rays per pixel per axis
FILL = 0.62          # fraction of the icon's width the pump spans
YAW = 20.0           # degrees; see camera_basis()
PITCH = 26.0

# Directional shading, so the isometric read stays legible the way Minecraft's own item render does.
SHADE = {"up": 1.0, "down": 0.52, "north": 0.80, "south": 0.80, "east": 0.66, "west": 0.66}

# Contact shadow the pump casts onto the logo disc.
SHADOW_ALPHA = 90
SHADOW_BLUR = 10
SHADOW_COLOR = (12, 26, 45, 255)


# --------------------------------------------------------------------------------------
# Scene assembly: the same element set docs/fuel_pump.bbmodel shows, in lower-block pixels.
# --------------------------------------------------------------------------------------
class Box:
    """An axis-aligned box in world pixels, with a texture sampler per face."""

    def __init__(self, frm, to, samplers):
        self.frm = tuple(float(v) for v in frm)
        self.to = tuple(float(v) for v in to)
        self.samplers = samplers  # dir -> callable(u, v) -> RGBA, u/v in 0..1 across the face


def _texture_images():
    """Every texture the pump's faces address, keyed the way the element specs name them."""
    imgs = {"panel:" + k: fn() for k, fn in ga.PANELS.items()}
    imgs["sheet:parts"] = ga.SHEETS["parts"].img
    imgs["sheet:nozzle"] = ga.SHEETS["nozzle"].img
    imgs["special:hose"] = ga.tex_hose()
    imgs["special:drum"] = ga.tex_drum()
    imgs["cog"] = ga.tex_cog_standin()
    return imgs


def _sampler(img, uv):
    """Nearest-neighbour sampler over a texture region given in 0..16 block-model UV units."""
    w, h = img.size
    u0, v0, u1, v1 = [c / 16.0 for c in uv]
    px = img.load()

    def sample(u, v):
        tu = u0 + (u1 - u0) * u
        tv = v0 + (v1 - v0) * v
        x = min(w - 1, max(0, int(tu * w)))
        y = min(h - 1, max(0, int(tv * h)))
        return px[x, y]

    return sample


# A stopped reading for the still image: the drums show DISPLAY_READING buckets. In game these roll
# and the units drum sits between digits; a static picture wants whole digits, so this is a clean
# stop. DRUM_X[0] is the smallest x, which on the front face (seen from -z) is the viewer's RIGHT
# and therefore the least significant digit -- the same order FuelPumpRenderer.odometer() uses.
DISPLAY_READING = 428

# Digit box inside each 6px drum cell, mirroring FuelPumpRenderer.DRUM_DIGIT_W / DRUM_DIGIT_H and
# the 3x5 glyphs generate_art.digit() draws.
DIGIT_W_PX = 3
DIGIT_H_PX = 5


def _drum_sampler(img, drum_index):
    """
    Sample one digit out of the 8x60 strip. tex_drum() draws a 3x5 digit at x 0..2 of each 6px
    cell, with the cell's first row a black seam, so the window is that digit box exactly -- the
    bbmodel preview's full-cell UV shows the seam and the blank right half instead.
    """
    digit = (DISPLAY_READING // (10 ** drum_index)) % 10
    w, h = img.size
    x0, x1 = 0.0, DIGIT_W_PX
    y0 = digit * ga.DRUM_CELL + 1
    y1 = y0 + DIGIT_H_PX
    return _sampler(img, [x0 * 16.0 / w, y0 * 16.0 / h, x1 * 16.0 / w, y1 * 16.0 / h])


def _face_samplers(elem, imgs, y_offset=0, xyz_offset=(0, 0, 0)):
    """Resolve an Elem's faces to samplers, reusing generate_art's own UV resolution."""
    out = {}
    # element_json paints sheet faces and hands back the exact UVs the block model uses.
    resolved = ga.element_json(elem, y_offset)["faces"]
    for d, spec in elem.faces.items():
        kind = spec[0]
        if kind == "panel":
            uv = ga.auto_uv(elem, d, y_offset)
            out[d] = _sampler(imgs["panel:" + spec[1]], uv)
        elif kind == "cog":
            out[d] = _sampler(imgs["cog"], [c * 2 for c in spec[1]])
        elif kind == "special":
            if spec[1] == "hose":
                out[d] = _sampler(imgs["special:hose"], [0, 0, 16, 16])
            else:
                out[d] = _drum_sampler(imgs["special:drum"], spec[2])
        else:
            uv = resolved[d]["uv"]
            out[d] = _sampler(imgs["sheet:" + spec[1]], uv)
    return out


def build_scene():
    """The posed pump as a list of Boxes in world pixels (y=0 is the bottom of the lower block)."""
    imgs = _texture_images()
    boxes = []

    def emit(elem, y_offset=0, xyz_offset=(0, 0, 0)):
        samplers = _face_samplers(elem, imgs, y_offset, xyz_offset)
        if elem.rotation:
            # Only the cogwheel's 45 degree copies are rotated; they read as a round gear in the
            # 4px gap and their exact orientation is invisible at icon scale, so skip them.
            return
        frm = [elem.frm[0] + xyz_offset[0], elem.frm[1] + y_offset + xyz_offset[1], elem.frm[2] + xyz_offset[2]]
        to = [elem.to[0] + xyz_offset[0], elem.to[1] + y_offset + xyz_offset[1], elem.to[2] + xyz_offset[2]]
        boxes.append(Box(frm, to, samplers))

    for e in ELEMS_OF("lower"):
        emit(e)
    for e in ELEMS_OF("cog"):
        emit(e)
    for e in ELEMS_OF("upper"):
        emit(e, y_offset=16)
    for e in ga.ELEMS:
        if e.model == "bbmodel_only":
            emit(e, y_offset=16)
    noz_off = tuple(ga.HOLSTER_INLET_PX[i] - ga.NOZZLE_INLET_LOCAL[i] for i in range(3))
    for e in ELEMS_OF("nozzle"):
        emit(e, xyz_offset=noz_off)
    return boxes


def ELEMS_OF(model):
    return [e for e in ga.ELEMS if e.model == model]


# --------------------------------------------------------------------------------------
# Isometric raycaster
# --------------------------------------------------------------------------------------
def camera_basis():
    """
    Three-quarter isometry looking at the pump's front-right corner, so one copper flank, the
    brass corner posts, the drum counter's bezel and the holstered coupler are all in view at
    once. Pitch is shallower than the item model's 30 degrees because the pump is tall and a
    steeper camera foreshortens the body into the plinth.
    """
    yaw = math.radians(YAW)
    pitch = math.radians(PITCH)
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)
    # Forward points from the camera into the scene.
    fwd = (-sy * cp, -sp, cy * cp)
    right = (cy, 0.0, sy)
    up = (-sy * sp, cp, cy * sp)
    return right, up, fwd


def ray_box(origin, direction, frm, to):
    """Slab test. Returns (t_near, axis, sign) for the entry face, or None."""
    tmin, tmax = -1e30, 1e30
    axis, sign = 0, 1
    for i in range(3):
        d = direction[i]
        if abs(d) < 1e-9:
            if origin[i] < frm[i] or origin[i] > to[i]:
                return None
            continue
        inv = 1.0 / d
        t1 = (frm[i] - origin[i]) * inv
        t2 = (to[i] - origin[i]) * inv
        # The entry slab is decided by the direction alone: a ray going + on this axis enters
        # through the low side, one going - enters through the high side.
        s = 1 if d > 0 else -1
        if t1 > t2:
            t1, t2 = t2, t1
        if t1 > tmin:
            tmin = t1
            axis, sign = i, s
        tmax = min(tmax, t2)
        if tmin > tmax:
            return None
    if tmax < 0:
        return None
    return tmin, axis, sign


# ray_box reports the slab it entered through by the sign of the ray's direction on that axis, so a
# ray travelling +z enters the box's LOW-z side -- the north face. The mapping is therefore the
# opposite of the direction sign, not the same as it.
FACE_OF = {
    (0, 1): "west", (0, -1): "east",
    (1, 1): "down", (1, -1): "up",
    (2, 1): "north", (2, -1): "south",
}


def face_uv(face, p, frm, to):
    """
    Where a hit point lands on a face, in 0..1 with v growing downwards — matching how block-model
    UVs are addressed, so the samplers built from auto_uv/sheet UVs line up with the game.
    """
    x, y, z = p
    if face in ("north", "south"):
        u = (to[0] - x) / (to[0] - frm[0]) if face == "north" else (x - frm[0]) / (to[0] - frm[0])
        v = (to[1] - y) / (to[1] - frm[1])
    elif face in ("east", "west"):
        u = (to[2] - z) / (to[2] - frm[2]) if face == "east" else (z - frm[2]) / (to[2] - frm[2])
        v = (to[1] - y) / (to[1] - frm[1])
    elif face == "up":
        u = (x - frm[0]) / (to[0] - frm[0])
        v = (z - frm[2]) / (to[2] - frm[2])
    else:  # down
        u = (x - frm[0]) / (to[0] - frm[0])
        v = (to[2] - z) / (to[2] - frm[2])
    return min(1.0 - 1e-6, max(0.0, u)), min(1.0 - 1e-6, max(0.0, v))


def scene_bounds(boxes):
    lo = [min(b.frm[i] for b in boxes) for i in range(3)]
    hi = [max(b.to[i] for b in boxes) for i in range(3)]
    return lo, hi


def render(boxes, size, ss):
    right, up, fwd = camera_basis()
    lo, hi = scene_bounds(boxes)
    center = [(lo[i] + hi[i]) / 2.0 for i in range(3)]

    # Fit the scene's projected extent to the requested fill fraction.
    ext_u = ext_v = 0.0
    for cx in (lo[0], hi[0]):
        for cy in (lo[1], hi[1]):
            for cz in (lo[2], hi[2]):
                d = (cx - center[0], cy - center[1], cz - center[2])
                ext_u = max(ext_u, abs(sum(d[i] * right[i] for i in range(3))))
                ext_v = max(ext_v, abs(sum(d[i] * up[i] for i in range(3))))
    span = 2.0 * max(ext_u, ext_v) / FILL
    scale = span / size  # world pixels per output pixel

    # Push the camera outside the scene along -forward.
    radius = math.dist(lo, hi)
    eye = [center[i] - fwd[i] * radius * 2.0 for i in range(3)]

    w = size * ss
    out = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    opx = out.load()
    step = scale / ss

    for py in range(w):
        # +v is up in world space but down in image space.
        ov = (w / 2.0 - py - 0.5) * step
        for px_ in range(w):
            ou = (px_ + 0.5 - w / 2.0) * step
            origin = [eye[i] + right[i] * ou + up[i] * ov for i in range(3)]
            best_t, best = 1e30, None
            for b in boxes:
                hit = ray_box(origin, fwd, b.frm, b.to)
                if hit is None or hit[0] >= best_t:
                    continue
                t, axis, sign = hit
                face = FACE_OF[(axis, sign)]
                sampler = b.samplers.get(face)
                if sampler is None:
                    continue  # face omitted from the model: see through it, as the game does
                p = [origin[i] + fwd[i] * t for i in range(3)]
                u, v = face_uv(face, p, b.frm, b.to)
                rgba = sampler(u, v)
                if rgba[3] == 0:
                    continue
                best_t, best = t, (rgba, face)
            if best is None:
                continue
            (r, g, bl, a), face = best
            k = SHADE[face]
            opx[px_, py] = (int(r * k), int(g * k), int(bl * k), a)
    return out


# --------------------------------------------------------------------------------------
# Composite
# --------------------------------------------------------------------------------------
def composite(pump, background):
    """Pump over Create's logo disc, with a soft contact shadow clipped to the disc."""
    bg = Image.open(background).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)

    # Shadow: the pump's silhouette, blurred and pushed down-right, then masked by the disc's own
    # alpha so it never spills past Create's circle onto the transparent corners.
    silhouette = pump.getchannel("A").point(lambda a: SHADOW_ALPHA if a > 96 else 0)
    shadow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", (SIZE, SIZE), SHADOW_COLOR), (0, 0), silhouette)
    shadow = shadow.filter(ImageFilter.GaussianBlur(SHADOW_BLUR))

    placed = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    placed.paste(shadow, (int(SIZE * 0.015), int(SIZE * 0.020)), shadow)
    clipped = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    clipped.paste(placed, (0, 0), bg.getchannel("A"))

    out = Image.alpha_composite(bg, clipped)
    return Image.alpha_composite(out, pump)


def recenter(img):
    """Nudge the rendered pump so its silhouette is centred in the frame, not its bounding box."""
    bb = img.getbbox()
    if bb is None:
        return img
    dx = int(round(SIZE / 2.0 - (bb[0] + bb[2]) / 2.0))
    dy = int(round(SIZE / 2.0 - (bb[1] + bb[3]) / 2.0))
    if dx == 0 and dy == 0:
        return img
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.paste(img, (dx, dy))
    return out


def main():
    boxes = build_scene()
    print("scene: %d boxes" % len(boxes))
    pump = render(boxes, SIZE, SUPERSAMPLE)
    pump = pump.resize((SIZE, SIZE), Image.LANCZOS)
    pump = recenter(pump)
    icon = composite(pump, BACKGROUND)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    icon.save(OUT)
    print("wrote", os.path.relpath(OUT, ROOT), icon.size)


if __name__ == "__main__":
    main()
