# Closed planetary geography

The second terrain stage provides spherical tile addresses, adjacent tiles across
face boundaries and poles, local tangent frames and saved identity metadata.
These are engine coordinate and persistence contracts. Walking between Minecraft
world edges, moving block storage between frames and changing gravity are not yet
implemented. Existing Moon/Earth patches and the highlands world keep their blocks,
generator definitions, borders and arrival behavior.

[Live geographic frames](SURFACE_FRAMES.md) now connect this topology to actual
highlands players with immutable poses, exact differential velocities and
server-side tile observations. It does not yet transfer blocks or stitch world views.

## Canonical addresses

`PlanetaryTopology` projects six subdivided cube faces onto a sphere. Its current
algorithm version is 1, with levels 0 through 24. Each face has `2^level` columns
and rows. `tileCount()` computes the total without allocating tiles. The faces
have persistent IDs `px`, `nx`, `py`, `ny`, `pz`, `nz`; enum ordinals are not save IDs.

A `PlanetaryTile` has an exact key `v1/level/face/column/row`. The save owner must
qualify it by a permanent geography ID. For example, the highlands definition uses
`astraengine:highlands/v1/12/px/2048/2048`. A key identifies a geographic region;
it does not claim that a dimension, chunk file or block grid exists for that tile.
Parsing rejects aliases, unsupported versions, invalid indices and noncanonical
numbers. Resolution is part of the permanent definition, not a runtime LOD choice.

`locate(direction)` chooses the face by the largest absolute body-fixed component.
Exact face ties prefer X, then Y, then Z. Exact internal grid-coordinate ties prefer
the greater column/row; the outer positive face edge belongs to its last cell.
Closed sampling squares share edges, while location lookup assigns one owner.
Ownership follows the supplied double-precision direction without an epsilon band.
A normalized ideal mathematical edge can round microscopically to either side;
exact latitude poles are canonicalized to the pole axis before lookup.

`direction(tile, u, v)` samples fractions in `[0,1]` on a tile, returning a unit
body-fixed direction. `neighbor(tile, edge)` supplies the neighbor, its entry edge
and whether to reverse the edge fraction. `LEFT`, `RIGHT`, `TOP`, and `BOTTOM`
refer to face grid axes, not compass directions. Every edge has a reciprocal
neighbor; cube corners have three incident faces and no single diagonal neighbor.

The tiles are gnomonic and are neither equal-area nor a fixed number of meters
wide. At level 12 on an Earth-sized sphere, widths are on the order of kilometers.
The general projected-cube approach is also described by
[S2's geometry documentation](https://s2geometry.io/devguide/s2cell_hierarchy.html);
Astra's linear projection, face axes and key format are its own contract, not S2 IDs.
No S2 dependency is bundled.

## Frames and terrain samples

`frame(tile, u, v)` returns an immutable `PlanetaryFrame` at reference altitude zero.
Its Y axis is radial up, its X axis follows the face column tangent, and Z completes
a right-handed frame. Pole faces use ordinary nonsingular axes. Direction and full
quaternion orientation transforms preserve roll; frame changes must pass through
body-fixed space rather than copy local yaw/pitch or X/Z components.

Frame point transforms are local affine tangent-plane transforms in double meters.
They do not map a global Cartesian voxel grid onto a sphere. Position sampling on
the sphere uses `direction()` and radial altitude instead. No single constant
block metric applies to an entire projected cube face.

The existing `PlanetaryTerrain` field samples body-fixed directions. Two incident
tiles can therefore evaluate the same height/climate sample at their shared edge.
This prevents a procedural geographic seam, but does not by itself weld host chunk
meshes, collisions, player edits or block placement across frames. Those operations
need the next traversal/storage stage.

All topology values are immutable and worker-safe. No Minecraft world, renderer,
clock or mutable tile cache is retained by the pure model.

## Save ownership

`PlanetaryGeographyState` is owned by the logical server's Overworld `SavedData`
and accessed on its server thread. The file `data/astraengine_geography.dat` pins:

- Geography ID `astraengine:highlands` and existing prototype world binding.
- Terrain version 1 and seed `71826152767793`.
- Reference radius 6,371,000 meters.
- Topology version 1 and level 12.

Creation initializes this manifest once; Minecraft owns saving it. Changed, missing
or wrongly typed fields in existing metadata reject loading. Unreadable existing
files or an orphaned backup reject default initialization. Restore valid data from
a backup; the engine does not silently reassign old storage to new geography.

This is a small identity manifest, not a second block database. It allocates no
per-tile records or dimensions. The current highlands patch crosses multiple
logical tiles while retaining its single ordinary host world and chunk files.
Its existing storage is not relabeled as separate tiles. Moon/Earth continue using
their original `astraengine_surfaces` bindings. A future storage implementation must
preserve one authoritative location for each block and recover interrupted transfers
before continuous walking can be enabled.

The pure types and manifest are current engine internals, not a frozen general
consumer registration API. Other mods should not rewrite these save files.

## Inspect in game

These read-only commands require operator permission level 2:

```text
/astra terrain here
/astra terrain locate 90 0
/astra terrain locate -90 0
/astra terrain locate 0 180
```

`here` requires the highlands prototype. `locate` accepts latitude `[-90,90]` and
longitude `[-180,180]` in degrees, treating +180 as -180. Output includes the tile
key, procedural reference terrain altitude, and its four neighbors with entry
edges and reversals. It does not teleport, allocate terrain or mark discoveries.

See [the playable highlands prototype](PLANETARY_TERRAIN.md) and
[existing surface travel](SURFACE_TRAVEL.md) for the actual worlds available now.
