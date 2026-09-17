# Create Fuel Station Hose — Project Plan

**Target:** Minecraft 1.21.1  
**Mod loader:** NeoForge 21.1.x  
**Create target:** Create 6.0.x or newer  
**Working title:** Create: Western Pump  
**Status:** Phases 0-5 implemented (see Implementation status below)

> **Implementation status (updated after build):** Phases 0-5 are implemented and verified by
> build + gametests. Several research assumptions below were corrected during implementation;
> see the "Findings that changed the plan" section immediately after this line.

## Executive recommendation

Build this as a standalone Create addon with an optional compatibility layer for Create: Aeronautical Diesel. Do not fork or recreate Aeronautical Diesel for the first release.

The fuel pump can be implemented against NeoForge's standard `IFluidHandler` capability. That lets it accept fuel from a Create fluid network and send fuel to any compatible tank or fill port, including Aeronautical Diesel's tank if its fill port exposes the normal fluid capability. The addon should not depend on Aeronautical Diesel's private Java classes or copy its code.

Add a small, compatible fuel tank or fuel-fill adapter only if testing proves that Aeronautical Diesel's tank cannot be reached through capabilities, or if a standalone experience without Aeronautical Diesel is a deliberate product goal. That fallback should be a separate compatibility/content module, not a second fuel system mixed into the MVP.

The hose should be rendered by our own client renderer. Create's Outliner/connection-preview patterns are useful for selection feedback, and the chain conveyor is useful for segmented connection geometry. However, **Create: Power Grid is the primary visual reference** for this feature because its powerlines better demonstrate endpoint-based connections, diagonal routing, natural sag, segmented curves, and distance-aware rendering. We should study and adapt the visual approach rather than use the Power Grid electrical network as a runtime dependency.


## Findings that changed the plan

These were verified against the real jars (resolved from Maven, inspected with `javap`) and a
running dedicated server, and they supersede parts of the research above.

1. **Sable Companion removes the need for a guarded compat layer.**
   `dev.ryanhcode.sable-companion` (MIT) registers a *no-op default implementation* through
   `META-INF/services`. `SableCompanion.INSTANCE` is therefore non-null even without Sable:
   `getContaining()` returns `null` and `projectOutOfSubLevel()` is the identity function.
   The Sable-aware code path runs unconditionally and degrades to world coordinates by itself, so
   the planned mod-loaded guards, reflection and separate compat module were not needed.

2. **The moving-endpoint problem is smaller than feared.** Sable keeps a vehicle's blocks at fixed
   `BlockPos` values inside a sub-level, so a fuel port's position is *already* stable while the
   vehicle flies. The endpoint stores `BlockPos + face + sub-level UUID`; only the projection into
   world space changes per frame. `ClientSubLevelAccess.renderPose(partialTick)` supplies the
   interpolated pose, and `distanceSquaredWithSubLevels` makes range checks correct across
   sub-levels.

3. **Create 6 moved several classes** referenced by the plan's research:
   `IHaveGoggleInformation` is in `create.api.equipment.goggles`, `SmartFluidTankBehaviour` in
   `foundation.blockEntity.behaviour.fluid`, `LerpedFloat` in Catnip (Ponder jar), and
   `PartialModel` in Flywheel. Catnip also replaced static `Lang.translate` with
   `Lang.builder(modId).translate(...)`.

4. **Power Grid's 1.21.1 work is on `architectury-1.21.1/dev`**, not the repo's default 1.20.1
   branch, and despite the name it is NeoForge-only. Its `forge/build.gradle` doubles as a
   known-good dependency recipe for Create + Sable + Aeronautics on 1.21.1.

5. **The hose uses a real catenary, not a Bezier.** `CurveParameters` solves the curve with
   Newton's method; the adaptation in `HoseCurve` inverts it further so the *sag* is the tuned
   input (0.46 blocks at 1 block out, ~1.0 at 20) rather than an arbitrary slack factor. Verified
   numerically: arc length matches to ~1e-9 and both endpoints land exactly on their anchors.

6. **Aeronautical Diesel was not needed as a dependency, and remains untested.** The pump talks
   only to `Capabilities.FluidHandler.BLOCK`, so it works with any tank exposing the standard
   capability. Whether Aeronautical Diesel's tank does so is *still unverified* - the capability
   spike in Phase 1 was satisfied generically instead, and confirming that specific mod still
   requires an in-game test with its jar installed.

## Product goal

Add a Create-themed gas-station pump that visibly and functionally refuels a stationary or Aeronautics vehicle:

1. A large fuel tank feeds the station through Create fluid pipes and a powered Mechanical Pump.
2. The player right-clicks the pump handle once to take the nozzle.
3. The player right-clicks a compatible Aeronautical Diesel fuel tank/fill port, or our fallback fuel port.
4. The hose extends from the pump to the selected port and remains attached.
5. While the station's source side has fuel and mechanical power, fuel is transferred into the target.
6. The second right-click on the same target disconnects the nozzle and retracts the hose.
7. Transfer also stops automatically when the receiving tank is full, the source is empty, the fluid types do not match, the endpoint is invalid, or the maximum hose distance is exceeded.

The MVP should prioritize the feeling of physically grabbing, extending, and returning the hose. A complex real-time rope simulation is not required; a stable, deterministic sagging curve will look better and be much easier to synchronize in multiplayer.

## Research findings

### Aeronautical Diesel

[Create: Aeronautical Diesel on CurseForge](https://www.curseforge.com/minecraft/mc-mods/aeronautical-diesel) currently targets Minecraft 1.21.1 and NeoForge. Its stated purpose is to bridge Create Aeronautics and Create: Diesel Generators. It adds an Industrial Iron Fuel Tank and a Compact Fuel Fill Port, supports Create wrench behavior and fluid automation, and lists diesel, biodiesel, gasoline, ethanol, crude oil, and plant oil as supported fuels.

The project page marks the mod **All Rights Reserved** and the release is a small closed-source jar. That makes a normal addon boundary important: integrate through capabilities, registry lookups, tags, or a documented API, and avoid copying/decompiling its implementation into this project. The public page also does not expose stable block IDs or a source API, so the exact tank and fill-port behavior must be verified in a local 1.21.1 test instance before implementation is finalized.

Aeronautical Diesel's public description says that fuel automation already works. This is favorable for the addon approach: the first compatibility test should be whether the existing fill port returns `Capabilities.FluidHandler.BLOCK` on the clicked side. If it does, the fuel station does not need to know the implementation class.

### Create fluid and animation systems

Create 6's hose pulley is a strong architecture reference. Its block entity stores a `SmartFluidTank`, exposes an `IFluidHandler`, uses `FluidDrainingBehaviour` and `FluidFillingBehaviour`, synchronizes animated state to clients, and expands its render bounds for the length of its hose. Its renderer uses partial models and animated offsets rather than a general-purpose physics engine. See the [HosePulleyBlockEntity source](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/fluids/hosePulley/HosePulleyBlockEntity.java), [HosePulleyFluidHandler source](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/fluids/hosePulley/HosePulleyFluidHandler.java), and [HosePulleyRenderer source](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/fluids/hosePulley/HosePulleyRenderer.java).

Create's chain conveyor renderer also demonstrates the relevant pattern: interpolate client-visible positions, render long connections as segmented geometry, use distance-aware rendering, and enlarge the block entity's view distance/render bounds for connections that extend beyond one block. See the [ChainConveyorRenderer source](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorRenderer.java).

### Frogport clarification

Create's Package Frogport is a logistics block for addressed packages. Its documented operations concern addresses, package inventory, and send/receive events; it is not a geometric connection or hose system. It should not be used for fuel-tank discovery, range checks, or line rendering. See the [Create Frogport documentation](https://github.com/creators-of-create/wiki/blob/main/src/users/cc-tweaked-integration/logistics/package-frogport.md).

For the requested “preview line in the air,” use the same conceptual approach as Create's connection tools: client-side raycast and validation, then a temporary line/curve preview. Create's train graph visualizer is a useful example of drawing line segments through the Outliner, including curved paths. See the [TrackGraphVisualizer source](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/TrackGraphVisualizer.java).

### Create: Power Grid

[Create: Power Grid](https://github.com/patryk3211/PowerGrid) is open source under the [Apache License 2.0](https://github.com/patryk3211/PowerGrid/blob/architectury-1.20.1/dev/LICENSE). It provides useful precedent for connection endpoints, persistent wiring, segmented cable presentation, sag/shape concerns, chunk-load checks, and distance-aware rendering. Its README describes a physics-based electrical network, and its 1.21.1 NeoForge release is a separate mod from Create's fluid transport.

Use Power Grid as the main hose-rendering reference, but not as a runtime dependency. The implementation should reproduce the useful visual properties: two endpoint anchors, diagonal connections, a sagging curve between anchors, segmented tube/cable geometry, interpolation when endpoints move, camera-distance LOD, and cleanup when an endpoint is removed or unloaded. Do not reuse its electrical simulation, and do not copy code until a specific source file and Apache 2.0 obligations have been reviewed. The fuel hose needs only two endpoints, one fluid transfer path, and a renderer; a graph/network solver would add complexity without improving the feature.

### Capability verification spike

Bucket behavior alone is not proof that Aeronautical Diesel exposes a NeoForge fluid capability. A block can accept a bucket through custom `use`/item-interaction code while still rejecting Create pipes, Mechanical Pumps, and generic `IFluidHandler` queries.

Verify the tank and fill port independently:

1. Place the Aeronautical Diesel tank/fill port in a normal world.
2. Try a Create fluid pipe connected to a Mechanical Pump and a small source tank. Test every side of the target, because the capability may be side-specific.
3. Test the same target with a generic fluid-handler probe block in the development mod.
4. Have the probe query `level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, clickedFace)` on the server and report whether it returns an `IFluidHandler`.
5. If a handler exists, simulate `fill` with the selected fuel, then report the simulated accepted amount. Do not execute the fill in the probe.
6. Repeat the test while the target is inside an Aeronautics/Simulated vehicle. The visible vehicle position and the actual simulation-dimension block position may be different.

The probe should report at least: target block ID, clicked side, handler present/absent, tank count, capacity, current fluid, `isFluidValid`, and simulated fill amount. A positive bucket test plus a positive capability query plus successful Create-pipe filling is enough to use the generic capability path. If only the bucket works, implement an Aeronautical Diesel-specific adapter or ask its author for a public compatibility hook.

### Aeronautics moving-contraption risk

Create Aeronautics/Simulated moves the working blocks of a vehicle into a simulation dimension while presenting the vehicle in the normal world. The [Simulated project README](https://github.com/Creators-of-Aeronautics/Simulated-Project/blob/main/README.md) describes this model. A world raycast against the visible vehicle therefore may not map directly to a normal overworld `BlockPos` or a normal block entity.

This is the primary technical spike. Static tanks and normal Create contraptions should be implemented first. Aeronautics vehicle support should use a stable adapter/port abstraction that can store a local block position plus the owning simulated contraption/entity identity, rather than assuming that a world block position remains valid while the vehicle moves.

## Scope and non-goals

### MVP scope

- One placeable fuel pump block with a Create industrial aesthetic.
- One pump block entity with a small internal buffer, fluid capability, saved connection state, and server-authoritative interaction.
- One integrated hose/nozzle visual.
- Compatibility with any target that exposes a NeoForge block fluid capability.
- Optional Aeronautical Diesel compatibility detection.
- Create mechanical power requirement through an adjacent or connected Mechanical Pump/source-side fluid network.
- Manual two-click nozzle pickup, attach, transfer, detach, and retract flow.
- Preview line/curve while the player is holding the nozzle and aiming at a valid target.
- Fuel type matching, capacity checks, source-empty behavior, maximum range, chunk-load checks, and safe cleanup.
- Ponder scene, recipe, language entries, basic sounds, and server/client tests.

### Deferred scope

- A full flexible-rope physics simulation.
- Hose collision with every block in the world.
- Automatic pathfinding around buildings.
- Payment, fuel pricing, pump UI, permissions, or economy integration.
- A complete Aeronautics vehicle attachment API if the upstream project does not expose a stable hook.
- Multiple nozzles or multi-grade fuel selection.
- A second replacement for all Aeronautical Diesel tanks.

## Proposed architecture

### Modules

```text
core
├── pump block, block entity, registration, recipes, Ponder
├── server interaction/state machine
├── fluid source/receiver capability adapters
└── network packets and saved state

client
├── pump/nozzle animation
├── hose curve renderer and render bounds
├── held-nozzle target preview
└── sounds and particles

compat
├── Create capability helpers
├── Aeronautical Diesel detection and target rules
└── Aeronautics/Simulated moving-endpoint adapter
```

The core module should depend on Create and NeoForge only. The Aeronautical Diesel and Aeronautics integrations should be guarded by mod-loaded checks and should not load classes from absent mods.

### Pump block and fluid topology

The pump has three conceptual connection points:

- **Source inlet:** a side-facing fluid capability that receives fuel from Create pipes. A Create Mechanical Pump must be used in the upstream pipe network. The pump itself does not create fuel and does not replace Create's kinetic pump.
- **Nozzle outlet:** a virtual output that is only active while a nozzle is attached to a valid receiver.
- **Nozzle rack/handle:** the player interaction point and visual rest position.

The block entity should buffer a small amount of fluid, initially 1,000–2,000 mB. The upstream Mechanical Pump fills this buffer through the normal fluid capability. During an active attachment, the server transfers from the buffer to the target capability. If the upstream network is configured correctly, no custom source network is required.

The transfer code must always use simulation before execution:

1. Query the target fluid handler with a simulated `fill`.
2. Drain only the amount the target can accept and the pump buffer contains.
3. Execute the target fill and source drain.
4. Mark both endpoints dirty and synchronize only state changes or throttled progress updates.

This prevents overfilling, fluid duplication, and client-side authority problems.

### Target capability abstraction

Define a small internal interface such as `FuelReceiverEndpoint` with:

- dimension/identity information;
- render attachment position and normal;
- server-side fluid handler lookup;
- validity and loaded checks;
- local/remote transform support for moving vehicles.

The first resolver checks a clicked block face with `Capabilities.FluidHandler.BLOCK`. It should not require a specific block class. The Aeronautical Diesel resolver may add whitelist/tag checks after the generic capability path is proven.

For a moving Aeronautics vehicle, prefer a dedicated nozzle/fill-port adapter block that lives with the vehicle and exposes a stable local coordinate. Store the owning simulated contraption identity and local position in the pump connection state. Resolve its current world render position every client frame and its current server-side fluid handler in the simulation dimension.

## Interaction state machine

```text
RACKED
  └─ right-click pump handle ─> HELD

HELD
  ├─ aim at valid receiver + right-click ─> ATTACHING
  ├─ right-click pump/rack ──────────────> RACKED
  └─ invalid target / range exceeded ────> HELD

ATTACHING
  └─ short interpolation ────────────────> ATTACHED

ATTACHED
  ├─ transfer while source + power + capacity exist
  ├─ receiver full/source empty ─────────> RACKED or STOPPED
  ├─ right-click same receiver ──────────> RETRACTING
  ├─ endpoint removed/unloaded ──────────> RETRACTING
  └─ player dies/logs out/block removed ─> cleanup + RETRACTING

RETRACTING
  └─ short interpolation ────────────────> RACKED
```

Only the server may change the authoritative state. The client can show a prediction/preview, but the server must validate the clicked block, player distance, dimension, endpoint identity, and connection ownership before attaching.

Recommended initial limits:

- maximum hose length: configurable, initially 12 blocks;
- one active nozzle per pump;
- one active player per nozzle;
- connection ownership stored as player UUID plus endpoint identity;
- connection cleared if the pump or endpoint is broken;
- no cross-dimension connections;
- no transfer while the pump is overstressed, unpowered, or its source capability is unavailable.

## Hose and nozzle visuals

### Pump animation

Use Create-style partial models and `LerpedFloat`/tick interpolation. Avoid adding GeckoLib for one pump. The model should have separable parts:

- cabinet/body;
- hose reel or side spool;
- nozzle handle;
- nozzle cradle;
- hose collar at the cabinet;
- optional display/gauge.

Animate the nozzle with a short keyed sequence: lift from cradle, extend along the first part of the hose, latch at the target, then reverse on detach. The block entity stores only compact animation state/timestamps; the renderer derives the visual interpolation.

### Hose curve

Do not try to make the hose a chain of real blocks. Render one client-side tube along a deterministic curve:

- endpoint A: pump hose collar;
- endpoint B: nozzle/receiver attachment point;
- control points: biased toward a shallow downward sag, with a larger sag for longer hoses;
- optional second bend near the nozzle so it feels hand-held;
- 12–24 curve segments depending on distance/LOD;
- 4–6 radial tube sides for the MVP;
- scrolling or subtle animated UV only while fuel is flowing;
- red/green preview tint based on target validity.

The curve should be recomputed from synchronized endpoint data, not from client-only player state once attached. For a moving vehicle, endpoint B must be transformed from the vehicle's current pose. When the endpoint is too far away or the chunk is not loaded, hide the hose safely and invalidate the connection server-side where appropriate.

The first renderer prototype should follow a Power Grid-style endpoint-to-endpoint cable rather than a Hose Pulley-style vertical rope:

- Use the pump collar and nozzle as the two authoritative anchors.
- Build a straight-line baseline between them so horizontal, vertical, and diagonal connections all work.
- Add a downward sag term that is strongest near the middle of the hose and scales with length, while preserving the endpoint heights.
- Use cubic Bezier control points or a sampled catenary-like curve; choose whichever produces the most stable tube frame during endpoint movement.
- Generate a tube around the sampled centerline with a stable orientation frame, rather than rotating independent blocks per segment.
- Use 16 segments for normal range, 8 segments for far range, and more only if close-up testing shows a visible need.

This gives us the visual qualities we want from Power Grid without importing its electrical graph or requiring a physics solver. A later version can add controlled hose sway, but the deterministic sag curve should be the 0.1.0 baseline.

### Preview line

While `HELD`, raycast from the player's camera and search for a receiver within the configured hose range. Draw a temporary thin line or low-segment hose curve from the pump/nozzle to the hit point. Use green for a valid fluid receiver, amber for a receiver with a fluid mismatch, and red for an invalid/out-of-range target.

Create's Outliner is a good first implementation for the preview line. If the required transparency/curve appearance is not sufficient, use a client render-stage event and a custom `VertexConsumer` mesh. The preview must never create world blocks or send repeated packets; send one attach packet only when the player clicks.

## Implementation phases

### Phase 0 — project bootstrap and version lock

- Create a NeoForge 1.21.1 workspace with Java 21.
- Lock Create 6.0.x, NeoForge 21.1.x, Aeronautics, Sable, and Aeronautical Diesel test jars.
- Establish mod ID, package name, license, mappings, Gradle run configurations, and a minimal build.
- Add a local test profile containing Create, Create Aeronautics/Simulated, Create: Diesel Generators, and Aeronautical Diesel.

**Exit criteria:** clean `runClient`, clean dedicated-server startup, and a distributable development jar.

### Phase 1 — capability and compatibility spike

- Inspect the Aeronautical Diesel jar only for runtime registry names, block behavior, and capability behavior needed for compatibility testing.
- In-game test the Industrial Iron Fuel Tank and Compact Fuel Fill Port on every relevant side.
- Verify whether the target accepts Create/Diesel Generator fuels through `Capabilities.FluidHandler.BLOCK`.
- Verify whether the tank/fill port works in the Aeronautics simulated dimension and while the vehicle moves.
- Test static world tank, normal Create contraption, and moving Aeronautics vehicle separately.
- Decide whether an adapter port is needed.

**Exit criteria:** a written compatibility matrix and a chosen target resolver path. This phase must finish before committing to a custom replacement tank.

### Phase 2 — pump block and source-side fluid path

- Register the pump block, item, block entity, model, blockstate, recipe, and language entries.
- It should be two blocks tall like a door where its two blocks tall but one item.
- Implement source-side capability exposure and a small internal buffer.
- Connect a Create Mechanical Pump and large fluid tank.
- Add goggles information for source fluid, buffer amount, connection status, and error state.
- Add server-side persistence and capability invalidation on removal.
- Textures should be programatically generated using code like javascript or python. It should follow create mod aesthetics and feature a cog wheel in the design like the other create blocks.

**Exit criteria:** a powered Mechanical Pump fills the station buffer; no fuel is created without an upstream fluid source.

### Phase 3 — manual nozzle state machine

- Implement pump-handle pickup and rack interaction.
- Implement target selection, range/face validation, endpoint ownership, and attach/detach packets.
- Implement receiver fill simulation/execution and automatic stop conditions.
- Add sounds and feedback messages for invalid targets, mismatch, empty source, full receiver, and out-of-range targets.
- Add cleanup on block break, player logout/death, endpoint invalidation, and dimension changes.

**Exit criteria:** a player can reliably perform the full two-click refueling loop on a static capability-compatible tank in singleplayer and on a dedicated server.

### Phase 4 — pump/nozzle/hose animation

- Build the Create-themed pump model with industrial iron, copper/brass accents, readable hose collar, and a chunky nozzle.
- Add partial models for the nozzle, reel, collar, and handle.
- Implement interpolated rack/held/attached/retracting animation.
- Implement the hose curve renderer, sag, lighting, UV behavior, render bounds, and camera-distance LOD.
- Implement held-nozzle preview line and valid/invalid tint.

**Exit criteria:** the hose looks attached at both ends, does not disappear at normal viewing distance, does not leave ghost geometry, and remains visually stable during movement.

### Phase 5 — Aeronautics moving endpoint

- Add the Aeronautics/Simulated endpoint adapter only after the static path is stable.
- Resolve local vehicle coordinates to current world coordinates for rendering.
- Resolve the actual fluid handler in the simulation dimension for server transfer.
- Validate vehicle assembly/disassembly, chunk unload/reload, dimension changes, and vehicle motion.
- Add a safe fallback: detach and return the nozzle if the simulated endpoint cannot be resolved.

**Exit criteria:** a pump on the ground can refuel a moving Aeronautics vehicle without duping fuel, losing the connection permanently, crashing during assembly/disassembly, or keeping stale render state.

### Phase 6 — polish, documentation, and release

- Add Ponder instructions showing large tank → Mechanical Pump → station → hose → vehicle tank.
- Add configuration for range, buffer size, transfer rate, auto-detach behavior, and allowed fluid tags.
- Add recipe balancing and compatibility notes.
- Add dedicated-server test coverage and a manual regression checklist.
- Publish a clear dependency matrix and note that Aeronautical Diesel is optional unless the release is explicitly branded as an integration addon.

**Exit criteria:** release candidate jar, changelog, known-issues list, and a reproducible test instance.

## Testing plan

### Functional tests

- Pump buffer fills only through an actual Create fluid network.
- Mechanical power is required; an unpowered upstream Mechanical Pump does not transfer fuel.
- Correct fuel transfers to an empty compatible receiver.
- Wrong fuel never enters the receiver.
- Receiver capacity is never exceeded.
- Source depletion stops transfer cleanly.
- Second click detaches exactly once and returns the hose to the rack.
- Full receiver stops transfer and follows the configured auto-detach behavior.
- Only the player who picked up the nozzle can operate it.
- Breaking the pump, source pipe, or target cleans up the connection.
- Server restart preserves or safely clears the connection according to the chosen persistence rule.

### Rendering tests

- Preview line is client-only and never causes server spam.
- Hose renders in front of, behind, above, and below the pump.
- Hose renders across chunk boundaries when both endpoints are loaded.
- Hose render bounds cover the entire curve.
- Hose disappears when the target is invalid and returns after a valid resync.
- No stale hose remains after detach, block break, player logout, or vehicle disassembly.
- Moving Aeronautics endpoints follow the vehicle without visible snapping beyond normal interpolation.
- Far-distance LOD does not produce a large performance cost with many pumps.

### Compatibility matrix

| Scenario | Required for MVP | Notes |
|---|---:|---|
| Static Create tank or fluid handler | Yes | Generic capability path |
| Aeronautical Diesel tank/fill port in world | Yes for integration release | Verify capability side and accepted fluids |
| Normal Create contraption | Recommended | Reuse moving-endpoint abstraction if practical |
| Aeronautics simulated vehicle | Phase 5 | Highest-risk compatibility case |
| Aeronautical Diesel absent | Yes for standalone core | Pump remains a generic Create fluid tool |
| Power Grid absent | Yes | Power Grid is not a dependency |

## Risks and decisions to record

1. **Aeronautical Diesel may not expose a usable capability.** Mitigation: add an explicit adapter/fill-port block or request a small public compatibility hook from its author. Do not hardcode private classes in the core.
2. **Moving vehicle fluid handlers may live in the simulation dimension.** Mitigation: keep server transfer and client render endpoint resolution separate and store local vehicle coordinates.
3. **Long dynamic hose rendering may be expensive.** Mitigation: segment cap, LOD, render-distance checks, and one renderer per active connection rather than one entity per segment.
4. **Create internals may change between 6.0.x releases.** Mitigation: use NeoForge capabilities and vanilla/NeoForge render events wherever possible; isolate Create-specific renderer helpers behind a small client package.
5. **The term “Frogport system” may lead implementation in the wrong direction.** Mitigation: document that Frogport is package logistics; use a custom endpoint state machine and Create connection-preview conventions instead.
6. **Forking Aeronautical Diesel would create licensing and maintenance problems.** Mitigation: ship an addon first and only create an independent tank implementation if the compatibility spike proves necessary.

### Naming filter

- Keep it usable for diesel, gasoline, biodiesel, ethanol, crude oil, and future fluids.
- Prefer two or three memorable words and a searchable “Create” prefix.
- Do not make Power Grid, Frogport, or Aeronautical Diesel a required part of the identity.
- Check CurseForge, Modrinth, GitHub, and common mod IDs before committing.
- Avoid names that sound like an official Create release or an upstream Aeronautics component.

**Selected working title:** use **Create: Western Pump**. It is memorable, fits the gas-station theme, and leaves room for the pump, hose, tank, and airfield use cases. Keep **Create: Brass & Blacktop** as a possible alternate subtitle or future branding direction.

## Definition of done for version 0.1.0

- The mod runs on Minecraft 1.21.1 NeoForge with the locked Create version.
- A player can pick up the pump nozzle, attach it to a capability-compatible fuel port, transfer fuel from a mechanically powered source, detach it with a second click, and see the hose extend/retract.
- The pump works without Aeronautical Diesel when pointed at a generic fluid receiver.
- Aeronautical Diesel compatibility is tested and documented, with no dependency on its private implementation classes.
- Static targets are stable; moving Aeronautics targets either work through the adapter or are explicitly listed as a known limitation.
- The server owns all fuel movement and connection state.
- Hose rendering has valid bounds, cleanup, LOD, and no per-segment entities.
- Ponder, recipes, tooltips, configuration, dedicated-server tests, and release documentation are included.

## Research references

- [Create: Aeronautical Diesel — CurseForge](https://www.curseforge.com/minecraft/mc-mods/aeronautical-diesel)
- [Aeronautical Diesel release details](https://www.curseforge.com/minecraft/mc-mods/aeronautical-diesel/files/8580476)
- [Create 6 source repository](https://github.com/Creators-of-Create/Create/tree/mc1.21.1/dev)
- [Create Hose Pulley block entity](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/fluids/hosePulley/HosePulleyBlockEntity.java)
- [Create Hose Pulley fluid handler](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/fluids/hosePulley/HosePulleyFluidHandler.java)
- [Create Hose Pulley renderer](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/fluids/hosePulley/HosePulleyRenderer.java)
- [Create Chain Conveyor renderer](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/kinetics/chainConveyor/ChainConveyorRenderer.java)
- [Create Track Graph visualizer](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/graph/TrackGraphVisualizer.java)
- [Create Package Frogport documentation](https://github.com/creators-of-create/wiki/blob/main/src/users/cc-tweaked-integration/logistics/package-frogport.md)
- [Create Aeronautics/Simulated source repository](https://github.com/Creators-of-Aeronautics/Simulated-Project)
- [Create: Power Grid source repository](https://github.com/patryk3211/PowerGrid)
- [Create: Power Grid Apache 2.0 license](https://github.com/patryk3211/PowerGrid/blob/architectury-1.20.1/dev/LICENSE)
