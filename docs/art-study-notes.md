# Art study notes — Diesel Generators & Power Grid (Phase 4, Step 0)

Written before any modeling. Everything below was sampled from the real jars, not remembered:

| Mod | Jar inspected | Namespace (read off `assets/`) |
|---|---|---|
| Create: Diesel Generators | `libs/createdieselgenerators-1.21.1-1.3.15.jar` | `createdieselgenerators` (plus `everycomp`, `quark` compat) |
| Create: Power Grid | `…/ModrinthApp/profiles/flight school/mods/powergrid-mc1.21.1-0.6.2.jar` | `powergrid` (plus `tis3d` docs) |

Pixels were read at 8×–16× nearest-neighbour; colours were sampled with Pillow, not estimated.

---

## 1. Diesel Generators — block-body reference

### 1.1 Sampled palettes (hex, dark → light)

**Brass** (engine cylinder banks, heads, corner posts; `diesel_engine.png`, `diesel_engine_big.png`, `engine_piston_block.png`):
`#724731 #9e6947 #af794f #bf8e55 #cea05a #d7aa5e #e4b763 #eec168 #f7cb6c #fbdc7d #ffeb8c`
→ `#724731 #9e6947 #cea05a #ffeb8c` are byte-identical to Create's `brass_casing.png`. DG brass **is** Create brass, just with more mid-steps.

**Gunmetal** (engine base plinth, exhaust/louvre panels, radiator; `diesel_engine_base.png`, `diesel_engine_big.png`):
`#191920 #1e2126 #212123 #222227 #272b30 #2a2a2e #2b2b2e #303639 #353438 #39393c #3a4043 #414144`
→ Darker and blue-tinted compared to Create's `industrial_iron_block.png` (`#353438 #3f3e42 #444444 #494848 #4f4f4f #595858 #676161 #757171`). The blue-grey trio `#272b30 / #303639 / #3a4043` is DG's signature "diesel" metal.

**Oxblood recess** (cylinder bore, base centre recess, hot openings): `#3d161e #592424 #5b2924 #68372c`.

**Copper** (pumpjack hole base/pipe, distillation tank, coupling flange): `#904931 #9a5038 #a75a40 #b26247 #c26b4c #c87456 #d67b5b #e3826c` — byte-identical to Create's `fluid_tank.png` / `copper_casing.png` copper.

**Sage galvanised iron** (pumpjack crank & hole structure only): `#2d3739 #3a4445 #525653 #6a6e63 #888b79 #9aa490 #adc6a9 #c1ecc9`. High-contrast greenish ramp, unique to the pumpjack.

**Neutral sheet metal** (canister/jerrycan, `sheet_metal.png`, silencer, turbocharger, distillation gauge): `#272727 #353535 #3f3f3f #484a4a #535555 #6a6c6c #878787 #b0b0b0 #d7d7d7 #ffffff`.

**Andesite-alloy accents** (bolts, small frames, coupling ring): `#505351 #60635f #707570 #828784 #9aa49d` — Create's andesite casing greys.

**Gauge face** (`gauge.png`): light face `#a7a7a2 #cacac7 #dcdcd9`, gunmetal bezel `#272b30 #3a4043`, brass ring `#9e6947 #724731`, a red `#592424` needle. The distillation gauge adds a coloured arc blue `#677baf` → cyan `#8bc1cd` → orange `#e49d63` → red `#a93f4b`.

### 1.2 Recurring structural motifs

- **Panel bevel**: 1 px darkest-of-ramp outline (`#724731` brass / `#1e2126` gunmetal), 1 px highlight along top & left (`#fbdc7d`/`#ffeb8c`), 1 px shadow along bottom & right (`#9e6947`). Standard Create bevel.
- **Nested square boss** in the centre of brass panels (4–6 px): lighter ring inside a darker ring = cylinder-head bolt boss. Used on every brass face of `diesel_engine_big`.
- **Louvre / grille**: gunmetal square, `#3a4043` rim on a `#272b30` field, with one 2 px diagonal lighter slash. Used for exhaust and radiator faces.
- **Pipe cross-section**: 4×4 or 6×6 rounded square, mid-tone ring, 1 px dark inner ring, black bore (`fluid_coupling.png` port, `pumpjack_hole_pipe.png`).
- **Rivets**: single `#ffeb8c` pixel at brass panel corners; 2×2 `#3a4445` squares at sage-iron corners.
- **Cylinder fins**: 2 px vertical brass stripes (`#cea05a` on `#9e6947`) on engine sides.
- **Plinth**: every engine sits on a 16×4×16 gunmetal plinth (`diesel_engine/block.json` element 0) with a `#2b2b2e` rim and a dark `#212123` field.
- **Dither**: diagonal 1 px checker only at brass↔gunmetal boundaries; no dither inside a material.

### 1.3 Material transitions

- Brass meets gunmetal through a 1 px `#724731` line, never black.
- Copper meets gunmetal directly, using copper's own dark ring `#904931`.
- **No warning colours anywhere.** DG has no yellow/black hazard stripes and no red paint. "Danger" is only ever a dark oxblood recess for a hot opening. The current Western Pump red shell is therefore off-brand and gets replaced by gunmetal + brass.

### 1.4 Model conventions (from `models/block/*.json`)

- Sheets are 32×32 PNGs addressed with 0–16 UVs, so one UV unit = 2 px and half-unit UVs (e.g. `9.5`) are exact pixels. Nothing uses `texture_size`.
- `diesel_engine/block.json`: plinth `[0,0,0]→[16,4,16]`, four 4×7×4 brass corner posts at the block corners overhanging by 1 px (`-1…3`, `13…17`), two 2×9×16 cylinder banks, one 6×2×16 top bar.
- `pumpjack_crank/`: base + top plates and side walls in one model, counterweights and rods as **separate partial models** animated by the renderer — the same partial-model split we use.
- `pumpjack_head.json`: horsehead = 16×12×2 plate + two 2×12×12 cheeks + 8×2×2 rod.
- `fluid_coupling/block_*.json`: half-block `[0,0,8]→[16,16,16]` plus a 12×12×3 inner ring. Its port face (copper frame, 1 px sage ring, black bore with a highlight) is **the closest thing DG has to a fuel port**.
- `canister/block.json`: 12×13×12 body, strap panels, and a 4×1×4 spout cap on top — DG's only container that reads as "fuel can". There is no fuel tank block in DG (the Industrial Iron Fuel Tank belongs to Aeronautical Diesel, which is closed source and not on disk).

### 1.5 Finding for the nozzle brief

**Diesel Generators contains no nozzle, dispenser or hose geometry.** The pumpjack head is a horsehead beam, not a spout. So the nozzle's *shape* has no primary reference in DG; what DG *does* give is the port language (coupling: copper flange + black bore) and the material language (gunmetal body, brass guard, copper fittings). The nozzle is built from those, and the target-side "port" look copies the coupling face.

---

## 2. Power Grid — hose reference

### 2.1 Where the wire actually lives

Power Grid's wires are **not block models**. The four cable textures are standalone files in `textures/special/` (not `textures/block/`, so they are never stitched into the atlas):

| file | size | palette | layout |
|---|---|---|---|
| `copper_wire.png` | 16×16 | `#b26247 #b8664a #c26b4c #c67254` | random 4-tone noise, tileable |
| `golden_wire.png` | 16×16 | `#dc9613 #e9b115 #fad64a #fdf55f` | random 4-tone noise, tileable |
| `insulated_wire.png` | 16×16 | `#dcd9d9 #e6e6e6 #ebebeb #f5f5f5` | vertical streaks, tileable |
| `iron_wire.png` | 16×16 | `#5c5c5c #656565 #6c6c6c #7a7a7a` | strict diagonal 4-stripe, each row shifted by 1 → reads as a twisted strand when wrapped |

Connector ends are ordinary blocks (`wire_connector`, `heavy_wire_connector`, `device_connector`, `cord_junction`, `power_plug`) and the power cord's plug is a **partial model** (`plug.json`: one 4×4×3 box, near-black rubber `#23201d #312d27 #38332b #413c31 #4a4336 #514a3d`).

### 2.2 How `HangingWireRenderer` solves "variable distance and angle"

Read from `HangingWireRenderer.java` / `CurveParameters.java` (branch `architectury-1.21.1/dev`, Apache-2.0):

1. **Render type**: `RenderType.entityCutout(texture)` (fancy) or `entityCutoutNoCull` (fast/LOD). Because the texture is a standalone file, UVs are absolute over that file and values beyond 1 wrap (GL default repeat). That is what makes a length-independent texture possible.
2. **U = arc length in blocks.** `runForSegments` walks the catenary in 0.5-block steps and hands each segment `offset` (distance so far) and `length`; the quad gets `u0 = offset`, `u1 = offset + length`. One 16-px tile per block of cable, no stretching whatever the span.
3. **V = a thin band** `v0 = entityId/16` (per-wire variety) to `v0 + thickness`. Every face of the cross-section reuses the same band, so the wire has no "top" or "bottom".
4. **Cross-section** is a square built from `cross1`/`cross2`: two perpendicular vectors of length `thickness·0.5·√2` (i.e. the half-diagonals of a square of side `thickness`). Four side quads + two end caps per segment. The frame is derived once from the chord `t2 − t1`, so it is stable while endpoints move.
5. **Lighting** is sampled per segment at the segment midpoint block (sky + block light packed), not inherited from the entity.
6. **LOD**: 0.5-block segments within 32 blocks, 1.5 beyond 32, 3.0 beyond 64; beyond 64 or on fast graphics the tube collapses to two crossed no-cull quads.
7. **Sag**: catenary with the placed length `L`; when a Sable sub-level moves an endpoint, `renderPose(tickDelta)` re-projects it and `nudge()` re-solves the curve every frame, stretching `L` by up to 1 block before it visibly tautens.
8. **Cord ends** (`CordRenderer`): the segment feeding a plug is drawn at half thickness, then the plug partial is placed on the socket face with `CachedBuffers.partial(PLUG, state)…renderInto(RenderType.solid())`.

### 2.3 What is copied and what is changed for the hose

- Copy: standalone `textures/special/` texture, `entityCutout*` render type, **U = arc length in blocks**, per-segment light sampling, distance LOD, the catenary already in `HoseCurve` (adapted from `CurveParameters`, attribution kept), a partial model at the far end (nozzle ≙ plug) with a thinner stub feeding it.
- Change: V wraps the **full circumference 0→1 with one seam on the underside** (Step 2 requirement) instead of PG's single thin band, so the hose can carry a highlight along the top and a rib/twist pattern. The cross-section stays a ring (8 sides near, 4 far) with the carried orthonormal frame already in the renderer.
- Palette: the hose is the power cord's black rubber (`#23201d #312d27 #38332b #413c31 #4a4336 #514a3d`) laid out with the `iron_wire.png` diagonal-shift rule so the ribbing spirals along the tube. Copper ferrules at both ends use the wire-connector contact copper (`#a75a40 #c16c4f #d17859`).

---

## 3. Diagnosis of the current hose ("glitched half-cylinder")

`FuelPumpRenderer.quad()` writes UV `(0,0)…(1,1)` for **every** quad into `RenderType.solid()` — the block atlas. So each face samples the entire atlas (a smear) tinted `#2A2A2E`, and the U/V never relate to the circumference or the length at all. Fixing it means a real texture, U along the length, V around the ring, which is Step 2 regardless of palette.

---

## 4. Decisions carried into modeling

Revised after the first in-game look (the user asked for copper, a kinetic cog base, bottom feed and a coupler-style nozzle):

- Pump palette = Create copper body with brass highlights only (posts, cap lip, bezel, spigot ring, lugs); DG gunmetal survives only in the counter window, the top vent and the coupler neck. No red, no hazard stripes.
- Base = Mechanical-Arm layout: copper plinth (y 0–6), Create's own shaftless wooden cogwheel turning in the gap (y 6–10, the standard cog height so neighbouring cogwheels mesh), short copper cabinet above. The spinning cog is the Create identity; the brass cog badge stays on the cap top.
- Fuel enters through the bottom only: brass pipe-port ring on the underside, brass down-chevrons on the plinth sides. The old back inlet flange is gone.
- Spigot boss (hose origin) = brass port ring on the upper block's side; holster = brass cradle on the same side; hose loops boss → cradle when racked.
- Nozzle = dry-break coupler: 8×8 copper locking face with a black bore and four brass bayonet lugs, 6×6 copper body with a brass grip ring, cam lever, gunmetal neck, copper hose ferrule. Its face points along the model's −Y so it seats flat against a target face.
- Named groups for Blockbench: `base`, `cog`, `body`, `gauge_drum`, `holster`, `nozzle`, `hose_segment_00…n` (the hose segments are a stair-stepped preview of the procedural rest-pose loop; in game the hose is rendered by code).
