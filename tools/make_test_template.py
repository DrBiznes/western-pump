#!/usr/bin/env python3
"""
Writes the gametest structure templates (vanilla structure NBT, gzip-compressed).

    python tools/make_test_template.py

Templates are a flat floor with air above; tests place everything else from code. Kept as a
script rather than a checked-in binary so the size can be changed without a structure block.
"""
import gzip
import os
import struct

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "westernpump", "structure")
DATA_VERSION = 3955  # Minecraft 1.21.1


def tag_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def named(tag_type, name, payload):
    return bytes([tag_type]) + tag_string(name) + payload


def compound(entries):
    return b"".join(entries) + b"\x00"


def int_list(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)


def compound_list(items):
    return bytes([10]) + struct.pack(">i", len(items)) + b"".join(items)


def template(size, floor_block="minecraft:smooth_stone"):
    sx, sy, sz = size
    palette = compound_list([compound([named(8, "Name", tag_string(floor_block))]),
                             compound([named(8, "Name", tag_string("minecraft:air"))])])
    blocks = []
    for x in range(sx):
        for y in range(sy):
            for z in range(sz):
                state = 0 if y == 0 else 1
                blocks.append(compound([named(9, "pos", int_list([x, y, z])), named(3, "state", struct.pack(">i", state))]))
    root = compound([
        named(9, "size", int_list([sx, sy, sz])),
        named(9, "entities", compound_list([])),
        named(9, "blocks", compound_list(blocks)),
        named(9, "palette", palette),
        named(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
    ])
    return named(10, "", root)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, size in (("platform", (5, 3, 3)), ("pump_yard", (7, 6, 5))):
        path = os.path.join(OUT, name + ".nbt")
        with gzip.open(path, "wb") as f:
            f.write(template(size))
        print("wrote", os.path.relpath(path, ROOT), size)


if __name__ == "__main__":
    main()
