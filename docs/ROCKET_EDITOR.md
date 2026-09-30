# Rocket construction and extension API

**Transitional implementation:** the current playtest includes this editor, but
the accepted architecture moves the complete editor, part catalog and engineering
calculations to SolarTech. AstraEngine retains consumer-supplied ship rendering.
The code migration and rendering-only replacement API are not implemented yet.
See [the accepted boundary](ENGINE_SCOPE.md#accepted-rocket-editor-extraction).

AstraEngine provides a diagnostic workshop block, an extensible construction
model, a shader viewport, persistent assemblies, and nearby collision proxies.
SolarTech can reuse the editor from its own block and register its own parts,
controllers, resources, and propulsion parameters.

## Try the editor

In creative mode or as an operator with build permission:

```mcfunction
/give @s astraengine:rocket_editor
```

Place the block with open space to its east and right-click it. **Starter** loads
a sample pod, tank, and engine. The left panel switches between the part catalog
and assembly tree. Search matches names, IDs, and categories. Select an attachment
side and radial symmetry, then **Add**. Removing a part also removes its children.

The center is a shader-rendered inspection view: right-drag to orbit, scroll to
zoom, and left-click to select a part. The right inspector edits local position,
size, quarter-turn yaw, and module parameters. **Apply fields** validates the
visible fields before changing the draft. **Undo**, **Redo**, and **Restore saved**
operate on the local draft. **Save** explicitly writes it to the server; **Deploy**
saves a changed draft first, then creates or updates its assembly beside the block.
The editor requires a GUI area of at least 600 by 360 units; smaller layouts offer
a GUI-scale adjustment.

The deployed rocket uses the same analytic GLSL geometry, with collision/picking
boxes derived from its immutable parts. Cylinders and boxes use one conservative
box, and tapered parts use three slices. The parent bounding box is not a solid
obstacle. The workshop saves its blueprint, revision, and deployed entity UUID;
entity unloading does not create a replacement. Breaking the owning workshop
removes its assembly when the assembly next observes the missing host.

This construction slice deploys stationary assemblies. Piloting, fuel consumption,
staging, resource networks, and warp/wormhole travel are not connected to the
assemblies yet. **R** remains the separate astronomical free-camera mode.

## Open definitions, shared editor

The model lives in `dev.lexawhatt.astraengine.rocket`. There is no gameplay part-type
or propulsion enum. A `RocketPartDefinition` has a namespaced identity, a category,
a visual, a default size, and a list of `RocketModuleDefinition` values. Modules
contain typed `RocketParameterDefinition` fields: numbers with units and bounds,
booleans, or named choices. A `RocketPart` stores an immutable map of module IDs to
parameter values. A `RocketBlueprint` stores the assembly tree and name.

Register definitions on the **mod event bus**, from common code on both client and
server. `RegisterRocketPartsEvent` runs once during enqueued common setup, before
worlds exist. The catalog is frozen after dispatch; duplicate IDs fail setup.
Client and server must install matching definitions. Resource reload does not
register definitions again. Use `AstraRocketEditor.catalog()` after setup to query
the immutable catalog, obtain default values, or validate a blueprint.

The following consumer registration adds a wormhole core without changing the
editor, payload codec, or shader renderer:

```java
import dev.lexawhatt.astraengine.api.rocket.RegisterRocketPartsEvent;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import dev.lexawhatt.astraengine.rocket.RocketGeometryKind;
import dev.lexawhatt.astraengine.rocket.RocketMaterialStyle;
import dev.lexawhatt.astraengine.rocket.RocketModuleDefinition;
import dev.lexawhatt.astraengine.rocket.RocketParameterDefinition;
import dev.lexawhatt.astraengine.rocket.RocketPartDefinition;
import dev.lexawhatt.astraengine.rocket.RocketStandardModules;
import dev.lexawhatt.astraengine.rocket.RocketVisual;
import java.util.List;

// Register this method with modEventBus.addListener(...).
private void registerRocketParts(RegisterRocketPartsEvent event) {
    var wormhole = new RocketModuleDefinition("solartech:wormhole", "Wormhole drive", List.of(
            RocketParameterDefinition.number("range_ly", "Maximum range", "ly", 0, 1000, 25),
            RocketParameterDefinition.flag("stabilized", "Stabilization", true),
            RocketParameterDefinition.choice("transit_mode", "Transit mode",
                    List.of("warp", "wormhole"), "wormhole")));
    event.register(new RocketPartDefinition("solartech:wormhole_core", "Wormhole core",
            "solartech:propulsion",
            new RocketVisual(RocketGeometryKind.CYLINDER, 1, 1,
                    new SpaceVector(0.25, 0.7, 0.85), RocketMaterialStyle.PANEL),
            new SpaceVector(2, 2, 2),
            List.of(RocketStandardModules.mass(1800, 0), wormhole)));
}
```

Module identities do not select shapes. The current analytic backend supports
cylinders, frustums, and boxes with several procedural surface styles; these
rendering choices are independent of electronic, ion, radiation, warp, fictional,
or wormhole behavior. New gameplay modules do not require new geometry kinds.
The engine does not interpret arbitrary module IDs as executable code. Consumer
mods own the actual resource and propulsion rules; this API currently describes
and edits their construction data.

The optional standard modules are `astraengine:mass`, `astraengine:thrust_engine`,
`astraengine:power`, `astraengine:thermal`, and `astraengine:control`. Their field names, units, and ranges are fixed contracts.
`RocketStandardModules` provides their definitions. The basic estimator aggregates
those capabilities regardless of part identity. It reports mass, ideal shared-fuel
thrust/Isp/delta-v/burn time, Earth-reference TWR, electrical balance/storage, and
thermal balance. Size edits change geometry, not the configured engineering data.
Active standard thrust estimates require at least 1e-6 N and 0.001 s Isp to keep
accepted calculations finite. Custom modules are retained and marked unmodeled; warp parameters never become
invented conventional thrust. Estimate warnings do not prohibit deployment.

## Use a consumer-owned workshop

Implement `api.rocket.RocketEditorHost` on the consumer's block entity:

- Return an immutable blueprint and a nonnegative persisted revision.
- Authorize the player through `canEdit`.
- `setBlueprint` stores the validated snapshot atomically, advances the revision
  exactly once, and marks the block entity dirty.
- Store `deployedAssembly` separately; updating that UUID does not advance the
  blueprint revision.

Use `AstraRocketEditor.encodeBlueprint` and `decodeBlueprint` in the host's normal
NBT persistence. Encoding returns a fresh caller-owned tag; decoding validates
the registered schema and returns an immutable blueprint. Decode completely
before replacing the prior valid host state. These helpers require the frozen
common-setup catalog and do not read or write files.

On the owning logical-server thread, call
`AstraRocketEditor.open(serverPlayer, blockPosition)` from the consumer block's
interaction. The shared entry point checks the loaded host, eight-block range,
alive/build/spawn-protection permissions, and consumer authorization. It returns
false for expected denial and does not load chunks. The diagnostic block adds a
creative-or-operator restriction; a consumer defines its own access policy.

A player attachment owns the nonpersisted session token and exact host instance.
Requests carry a token, saved revision, and bounded command. Compact values use
the registered schema order and a SHA-256 definition fingerprint; mismatched
installations fail closed before decoding instance values. The maximum blueprint
wire representation is below 16 KiB. The server checks
ownership, range, permissions, schema, and revision before mutation. Stale or
invalid requests preserve the last valid save. Accepted mutation requests are
limited to one per four server ticks. A rate-limited deployment of the unchanged
saved draft retries at most three times with increasing client delays; other
rejections require user action and never trigger an automatic save. Closing, replacing the host, departure,
death, or logout invalidates access. Client drafts and undo history are session
owned; closing without saving discards them. Late replies cannot revive a closed
session. Deployment checks loaded chunks, build height, world border, blocks,
and entities before changing the world, and never duplicates an unloaded UUID.

## Current budgets and rendering ownership

These are bounded construction/transport budgets, not a list of allowed gameplay
mechanics: up to 256 registered definitions, 32 parts per blueprint, 8 modules per
part, and 32 total parameter fields per part. The assembly is one connected,
acyclic parent tree; local centers are bounded to 32 meters per axis and the
whole design to 16 by 24 by 16 meters. The tree records logical attachment
ownership; manual position edits do not enforce physical contact or prevent
parts from overlapping. Saved parameters must match their registered
schema exactly. Changing published IDs or schemas requires consumer migration;
there is no silent replacement or conversion of unknown definitions.

The renderer owns two private color/depth targets, releases them on reload or
logout, and leaves registered programs to Minecraft. Preview resolution is capped
at 1024 pixels on the longest edge. The world pass selects up to four nearby
assemblies within 96 blocks, writes analytic depth before opaque lighting, and
preserves foreground terrain. This is a bounded local renderer, separate from the
astronomical sky's physical-distance model. Shader-pack compatibility and universal
performance are not established by these limits.
