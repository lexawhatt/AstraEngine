# Consumer ship rendering

AstraEngine renders immutable visual assemblies supplied by a client consumer. It does not
provide a construction editor, component catalog, engineering calculations, inventory, or
spacecraft simulation. No SolarTech module is included or required. The former constructor
and its stored data are covered in [the removal guide](ROCKET_EDITOR.md).

## Ownership and lifecycle

The common `dev.lexawhatt.astraengine.api.ship` package contains immutable values only:

- `ShipVisualPart`: analytic geometry, surface treatment, local position, size, yaw and color.
- `ShipVisual`: a copied list of primitives and optional preview marker positions.
- `ShipBounds`: conservative visual bounds, not an authoritative collision shape.
- `ShipRenderInstance`: a visual, world-block origin, assembly quaternion and selection index.

Consumers own their simulation, persistence, networking, interpolation, interaction and collision
rules. These values contain no part identifiers or engineering properties. They may be built on
either logical side; constructing one neither creates a world entity nor transmits anything.

World submission uses `CollectShipsEvent` in `dev.lexawhatt.astraengine.client.ship` on the
NeoForge game event bus. Register handlers only from a physical-client entry point. The engine
posts the event on the render thread at `AFTER_BLOCK_ENTITIES`, before its opaque-world lighting
pass, or at `AFTER_LEVEL` after an active Iris shader pack finishes its world composition.
The event is never dispatched during shadow extraction. Offer current poses through
`event.collector().add(instance)`. The collector is sealed
immediately after dispatch; retaining it or the borrowed level across frames is unsupported.
Stopping submission removes the visual on the next frame. No registration or saved engine
instance survives a disconnect. Consumers must dispose their own connection-owned snapshots.

For UI previews, obtain the engine-owned renderer through
`AstraEngineClient.shipRenderer()` on the render thread. Do not construct, register or close
another renderer. `renderPreview(...)` draws a GUI rectangle; `pickPart(...)` returns a
conservative primitive index, or `-1`. `releasePreview()` releases preview attachments when
the consumer screen closes. The engine releases all attachments on logout/resource reload;
Minecraft owns the registered shader. The handle remains stable across reload, and
`renderPreview(...)` returns `false` while the shader is unavailable.

## Minimal world submission

This is a client-only listener example. The consumer supplies the current world origin and
orientation from its own state instead of hard-coding a persistent server object:

```java
ShipVisual visual = new ShipVisual(List.of(new ShipVisualPart(
        ShipVisualPart.Geometry.BOX, ShipVisualPart.Material.PANEL,
        SpaceVector.ZERO, new SpaceVector(2, 1, 4),
        0, 1, 1, new SpaceVector(0.5, 0.6, 0.7))), List.of());

// Inside the consumer's physical-client initialization:
NeoForge.EVENT_BUS.addListener((CollectShipsEvent event) -> {
    // Example placement near the current camera. No entity is created.
    SpaceVector origin = event.camera().add(new SpaceVector(0, 0, 8));
    event.collector().add(new ShipRenderInstance(
            visual, origin, FlightOrientation.IDENTITY, -1));
});
```

Import the four visual types from `dev.lexawhatt.astraengine.api.ship`, the event from
`dev.lexawhatt.astraengine.client.ship`, and `SpaceVector`/`FlightOrientation` from
`dev.lexawhatt.astraengine.cosmos`. `List` is `java.util.List`; `NeoForge` is the host's
`net.neoforged.neoforge.common.NeoForge`. Production consumers should filter the dimension
and submit only their currently visible objects.

## Coordinates, validation and current limits

| Property | Contract |
| --- | --- |
| Geometry | Closed boxes, cylinders and frustums; analytic shader intersections |
| Size | Full XYZ extents in local blocks, each in `[0.01, 64]` |
| Part center | Local XYZ blocks, each in `[-128, 128]` |
| Part yaw | Finite degrees; positive rotation maps local +X toward -Z |
| Assembly pose | Unit `FlightOrientation` quaternion; arbitrary yaw, pitch and roll |
| World origin | Finite double block coordinates; camera subtraction happens before float conversion |
| Color | Linear RGB components in `[0, 4]`; the current opaque output is LDR |
| Frustum radii | Bottom/top ratios in `(0, 1]`, at least one equal to 1; other solids require both 1 |
| Selection | Primitive index, or `-1`; visual highlight only |
| Per visual | At most 32 primitives and six optional preview markers |
| Markers | Local block coordinates in `[-128, 128]`; no attachment or assembly rules |
| Per frame | At most 128 offers; nearest four nonempty, distinct snapshots selected |
| Range | Instance origin within 96 world blocks of the camera |
| Preview | Longest attachment edge at most 1024 pixels; zoom `[0.25, 4]`; pitch clamped to `[-85, 85]` |

Invalid/null inputs fail with `IllegalArgumentException`; offering after collector sealing
fails with `IllegalStateException`. `add(...) == true` means provisionally selected: a later,
nearer offer can replace it. Equal-distance offers retain their submission order. Empty,
duplicate, out-of-range and over-budget offers return `false`.

Surface styles (`PLAIN`, `PANEL`, `WINDOW`, `TANK`, `NOZZLE`, `SOLAR`, `RADIATOR`) are shader
patterns only. They confer no fuel storage, propulsion, heat transfer or electrical behavior.
The current renderer is a bounded analytic backend, not an arbitrary mesh/material importer.
Preview picking uses conservative boxes and frustum slices; it is not pixel-exact selection.

World rendering writes analytic depth, so opaque terrain hides the visual and subsequent
depth-aware engine passes see it. It also runs in the bounded void flight world. Consumer
origins there are physical staging-world blocks, not astronomical meters or light-years;
consumers must explicitly choose the visual mapping from their virtual flight state.
Rendering does not add block light, server collision or a player-controllable ship.

With an active Iris pack, ship visuals are depth-aware late overlays. They do not
participate in the pack's material buffers, shadows, reflections, global illumination
or temporal history. GUI previews retain their own engine rendering. Active-pack
verification and exact version limits are tracked in the
[compatibility guide](COMPATIBILITY.md); ordinary-mode depth checks alone do not
establish shader-pack support.

## Verification

Pure tests exercise bounds, immutable inputs, quaternion poses and collection limits. The
verification-only `ship-visual` consumer covers native preview/picking, world depth, resource
reload, resizing and the actual free-camera flight flow. It is excluded from the shipped JAR.
See [README verification commands](../README.md#verification).
