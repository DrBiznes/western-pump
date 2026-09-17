# Create: Western Pump

A Create addon for Minecraft 1.21.1 / NeoForge that adds a gas-station fuel pump with a physical,
sagging hose you drag over to a vehicle and leave attached while it fuels.

Status: **phases 0–5 implemented**, pending in-game playtesting.

## What it does

1. Feed the pump from a Create fluid network (tank → Mechanical Pump → pipes). The pump never
   creates fuel; it only buffers what the network gives it.
2. Right-click the pump with an empty hand to take the nozzle.
3. Right-click any block that exposes a fluid handler — a Create tank, an Aeronautical Diesel fuel
   tank, a fuel port on a Sable aircraft — to attach the hose.
4. Fuel flows while the source has fuel, the target has room, and the fluids match.
5. Right-click the same target again (or the pump) to detach and rack the nozzle.

## Creative tab

The pump appears in **Create: Diesel Generators'** creative tab (`createdieselgenerators:cdg_creative_tab`)
when that mod is installed, and falls back to its own tab otherwise. Diesel Generators is an
optional dependency, not a required one.

All tab population happens in a single `BuildCreativeModeTabContentsEvent` handler in
[`ModCreativeTab`](src/main/java/me/jamino/westernpump/registry/ModCreativeTab.java). Registrate's
`setCreativeTab` is deliberately not used: combined with an explicit `displayItems` it contributes
the same stack twice and vanilla throws `Itemstack ... already exists in the tab's list` when the
creative menu is opened. If you add items, use that one handler.

`libs/` holds a copy of the Diesel Generators jar as a `runtimeOnly` dev dependency so this
placement can actually be tested; it is gitignored and not needed to build.

## Build

```bash
./gradlew build      # jar in build/libs/
./gradlew runClient  # dev client
./gradlew runServer  # dev server
```

Textures are generated, not hand-drawn — regenerate with `python tools/generate_textures.py`
(requires Pillow). They are deliberately placeholder art pending a proper art pass.

## How vehicle refuelling works

Sable (used by Create Aeronautics) moves a vehicle's blocks into a **sub-level**: their
`BlockPos` stays constant while the vehicle itself flies around. A fuel port bolted to a plane is
therefore always at the same block position, but its *rendered* position changes every frame.

The pump stores each connection as `BlockPos + clicked face + sub-level UUID`
([`FuelEndpoint`](src/main/java/me/jamino/westernpump/content/hose/FuelEndpoint.java)) and converts
to world space on demand:

| Need | Call |
|---|---|
| Server logic, range checks | `projectOutOfSubLevel` |
| Rendering (smooth, no stutter) | `ClientSubLevelAccess.renderPose(partialTick)` |
| Range across different sub-levels | `distanceSquaredWithSubLevels` |
| "Is this still the same vehicle?" | `SubLevelAccess.getUniqueId()` |

The persisted UUID is what stops a hose silently re-targeting an unrelated block after a vehicle
is disassembled.

### No mod-loaded guards are needed

`sable-companion` is an MIT API shim that registers a **no-op default implementation** through
`META-INF/services`. With Sable absent, `getContaining()` returns `null` and
`projectOutOfSubLevel()` is the identity function, so the same code path degrades cleanly to plain
world coordinates. This is why [`SubLevelSupport`](src/main/java/me/jamino/westernpump/compat/sable/SubLevelSupport.java)
calls the API unconditionally instead of hiding behind `isModLoaded` checks and reflection.

## Compatibility approach

The pump talks to targets **only** through NeoForge's `Capabilities.FluidHandler.BLOCK`. It does
not reference Aeronautical Diesel's classes, so it works with its tanks (assuming they expose the
standard capability, which still needs in-game confirmation) without depending on a closed-source
jar — and works just as well with any other mod's tank.

## Attribution

The hose's catenary solver in
[`HoseCurve`](src/main/java/me/jamino/westernpump/content/hose/HoseCurve.java) is adapted from
[Create: Power Grid](https://github.com/patryk3211/PowerGrid)'s `CurveParameters`
(Apache License 2.0, © 2025 patryk3211). Power Grid's electrical network is not used or depended
on — only the curve mathematics and its approach to Sable sub-level endpoints.

## Verified so far

- `./gradlew build` compiles against Create 6.0.10, Registrate, Catnip and Sable Companion.
- Dedicated server boots with Create and reports `0 mod requirements missing`.
- Dev client reaches the main menu with no missing-texture or model-load errors.
- `./gradlew runGameTestServer` — **6/6 gametests pass**, covering two-block placement, the fluid
  capability on both halves, buffer filling, endpoint NBT round-trip, creative-tab placement, and
  an end-to-end transfer into a real Create fluid tank that asserts fuel is conserved (no duping,
  no voiding). The transfer and creative-tab tests were both confirmed non-vacuous by deliberately
  breaking them and watching them fail.
- Creative-tab placement tested **both** with and without Create: Diesel Generators installed.

## Known gaps

- **Not yet played by hand in a live world.** The two-click loop is covered by server-side logic
  tests, not by a human clicking a pump, and the hose has never been looked at on screen.
- **Sable/Aeronautics is written but untested against the real mods.** The sub-level code paths
  are exercised only in their no-op (Sable-absent) form; nothing here has been run against a
  flying vehicle. Uncomment the Sable runtime dependency in `build.gradle` to try it.
- **Aeronautical Diesel compatibility is unverified.** The generic capability path is the right
  approach, but whether its tank exposes `FluidHandler.BLOCK` was never confirmed in game — that
  was Phase 1's spike and it still needs doing.
- The pump is a textured box: the nozzle is not a separate rendered part, so there is no
  lift/latch animation. The hose curve itself is fully rendered.
- No Ponder scene (Phase 6).
