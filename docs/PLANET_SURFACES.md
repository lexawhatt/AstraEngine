# Persistent solid-planet surfaces

Solid planets and moons use the same geographic storage and transition contracts as the new Earth. A `SolidPlanetProfile` pins a whole-body terrain realization to a system/body identity. Its serialized radius comes from the physical descriptor; the renderer does not enlarge it or create a small substitute surface patch.

## Ownership and persistence

`PlanetChart` represents one cube face and one 4,064-meter altitude band. Latitude/longitude and radial altitude identify canonical positions independently of the currently loaded host dimension. Six faces cover the sphere. A chart extension can observe neighboring storage, but cannot hold a second writable copy of its blocks.

The logical server owns `PlanetSurfaceBindings`, stored as `astraengine_planet_surfaces.dat`. Each binding includes the exact generator codec, full versioned profile, face and band. A dimension has a stable namespaced key derived from the profile identity and its chart address. Its blocks, entities and inventories remain ordinary Minecraft save data in that unique directory. Leaving, unloading chunks, reloading renderer resources, or opening another body never recycles this storage.

Dynamic levels are restored from this manifest before login. Allocation adds only the requested band; it does not allocate a 100-kilometer column of empty sections. Existing Earth ground bands retain their original preset ownership; upper Earth air bands use the same dynamic manifest. The current dynamic allocation limit is 96 charts per save. Reaching it refuses a new allocation before the player changes worlds. It never evicts an older chart.

Unknown versions, conflicting identities, unreadable existing manifests and missing historical Earth worlds fail explicitly. They are not replaced with generated defaults. Custom system definitions are synchronized before referring to their profiles. Client chart context contains only discovered systems and the player's occupied generic world; allocating a private body does not reveal it to other players.

## Shared geography and presentation

`SolidPlanetTerrain` samples a body-fixed unit direction. Host columns, direct Distant Horizons data, globe height/color maps and nearby landscape meshes call that same field. Poles and face boundaries do not choose separate terrain seeds.

The first profile version has three solid families:

- Rocky bodies: broad basins, mountain ridges, crater bowls and rims, stone and regolith.
- Icy bodies: folded relief, narrow cracks, packed ice and elevated snow.
- Ocean bodies: connected global continental relief, a physical zero-altitude water surface, and climate-dependent materials.

These are deterministic procedural approximations, not observational planetary maps or a tectonic simulation. Rotation is pinned with the profile: satellite periods follow their descriptor orbit, while unspecified primary rotation uses a deterministic period. A profile must receive a new version if its geographic realization changes.

Gas giants, stars, black holes and the Solar System ice giants have no invented solid floor. Supported descriptor radii remain physical meters. Very small solid bodies scale nearby landscape rings and texture coverage with their radius.

For a solid body whose center falls inside the supported storage depth, the innermost valid radial block forms a bedrock core at least one meter from the mathematical center. Lower cells have no canonical writable owner. A read-only collision boundary also protects this cutoff if creative editing removes its bedrock; ordinary movement cannot enter a singular negative-radius coordinate. Host generation, direct LOD and base-column queries share this cutoff.

The client owns a bounded globe atlas and three local detail tiles. The detail tiles share one GPU texture; the complete celestial pass uses eleven samplers, within Minecraft 1.21.1's twelve tracked texture units. Resource reload retires GPU objects and stale sampling jobs without changing connection descriptors or saved world definitions.

Generic surface skies use the shared celestial pass in the actual body frame, including ordinary walking views. Atmospheric density decreases with physical altitude; airless bodies have no atmospheric haze or vanilla clouds. Direct and reflected sunlight use the synchronized stellar state. Visual lightmap correction preserves local block emission.

## Host integration

The optional DH generator supplies `API_DATA_SOURCES` directly from the height/material field through the executor provided by DH. It does not create ordinary chunks to obtain unvisited procedural LOD. Real host chunks retain priority for modified terrain. Neither the direct generator nor orbital textures grant block ownership.

DH 3.3.3 repeatedly looks up the same immutable biome wrapper while scanning tall real chunks. A narrowly versioned optional mixin remembers the last holder/wrapper pair inside each DH chunk wrapper for 4,064-block height intervals. This height guard also includes another mod's world if it uses that exact height; it is not a generator-identity test. The actual biome is still sampled on every lookup, and changed biome holders use DH's original conversion. The immutable pair is published atomically, has no process-wide owner and retains no additional level reference. The hook exists because the public DH API offers no interception for this lookup; it does not alter scan bounds, blocks, light, caves, player edits, scheduling or user settings. Other DH versions and ordinary-height chunks retain their original path.

The same exact-version adapter also coalesces an already unchanged opaque run in DH's real-chunk converter. It skips only the remaining part of a section whose entire block and biome palettes prove one value and whose skipped DH block/sky-light samples are all zero. Changed palettes, caves, transparent blocks, biome boundaries, lit cells and foreign chunk-wrapper implementations retain the ordinary scan. Immutable height bounds are captured once per conversion and discarded with that invocation. A failed singleton proof is remembered only within that conversion so mixed sections immediately use the ordinary scan; positive proofs are never cached across later edits. Two synchronous identity predicates are reused only inside that conversion, and the scalar loop-local hook allocates no mutable local reference per scanned voxel. The original DH converter still emits columns, runs material/biome overrides, compresses and saves its data. Native comparisons against the unmodified scan verify identical packed columns, mappings and event positions, including a real material/biome override. There is no public DH uniform-run hook, so this optional integration is restricted to the inspected 3.3.3 implementation. It does not replace the scheduler, bypass health gates or change user options.

Saving an unchanged uniform section created by Astra generation reuses an exact host-codec NBT template for one of eight fixed vanilla materials. The same path applies to exact vanilla palettes loaded from disk in tall Earth/planetary chunks, after proving their current singleton membership under the host guard. Every save receives an independent copy. Mixed palettes, other materials, foreign container implementations and other encoding operations follow the normal host path. The bounded block templates retain no worlds, biomes, registries or mutable palettes.

Uniform biome palettes in those tall chunks reuse at most sixteen original-codec templates during one synchronous chunk write. This cache uses the writing registry, returns independent NBT and is discarded when that write returns; it cannot cross worlds, reloads or save invocations. Later block and biome edits still serialize normally. Neither optimization changes the chunk format, save ownership or mod block entities. Actual tall-chunk write/read/resave tests compare the original codecs and preserve later block and biome changes.

Ordinary and cross-chart writes reject noncanonical cells. A block-item placement whose destination is in a loaded neighboring chart is routed before NeoForge begins block-snapshot capture. The original item use path owns placement events, cancellation, components, block-entity data and inventory consumption. The temporary player coordinate scope restores its numeric pose in `finally`; actual teleport or removal exits every nested scope first. Scoped support-state reads observe existing neighboring chunks without allocating aliases.

Cross-chart mining validates the current server eye ray, range, loaded owner, held item and block state. Survival progress advances on host ticks. Client observations allow a small bounded network-latency window, but do not authorize a stale target. Chest menus forward the actual canonical inventory and validate geographic reach; they do not copy inventory contents into a second world.

Neighbor observations include a fixed open/closed visual bit for each block. Vanilla normal, trapped and ender chests use Minecraft's baked chest geometry, atlas textures, facing and single/double state in the existing boundary mesh. The server retains real geographic menu users in the host opener lifecycle, including lid state and trapped-chest signals. No inventory, loot data or arbitrary block-entity NBT is sent through the observation channel. The visual bit has a bounded refresh interval and does not reproduce the host's interpolated lid animation. Positional sound audiences retain the host's dimension rules. Other mods' custom block-entity renderers and custom menus require their own geographic integration; a successful vanilla chest test does not establish that parity.

Neighbor observations contain blocks, biomes and captured light, not a second tracked entity world. Players can share a canonical chest from adjacent charts, but players, mobs, projectiles and item entities in another chart are not projected into the current view. Ordinary entity tracking resumes when both participants occupy the same host dimension. The player crossing contract does not establish general entity migration through chart boundaries.

Light values are sampled from the canonical chunk and refreshed with its observation. Minecraft's light engines remain separate per host dimension: a lamp in the adjacent chart does not propagate block light into the current chart, and the observation mesh does not add such light to mob spawning or gameplay. Positional sounds and particles also retain their dimension-local delivery; an observed chest can visibly open without its opening sound reaching a listener in another chart. These limits apply equally to Earth and other solid bodies. Arbitrary cross-chart fluid, redstone and mod machine networks require additional host integration; canonical block placement and shared vanilla inventories do not imply those systems are continuous.

The pinned host can repeatedly enqueue an unload callback when its save dependency has completed but a generation reference is still held. For bound chart worlds, the retry is deferred to the next host unload tick through a per-`ChunkMap` queue. The pending holder and original host save routine remain authoritative; this permits generation handoffs to finish instead of spinning inside one unload callback. No blocks or block entities are skipped to accelerate shutdown.

## Verification scenarios

`solid-planets-create` and `solid-planets-restart` use disposable actual Moon and Europa dimensions, separate block edits and chest inventories, and a Moon/Europa/Moon return route. They verify persistent ownership, renderer effects and OpenGL status. Focused pure tests cover profile identity, geographic round trips, material fields and exact map sampling. Dedicated GameTests exercise real generated columns, manifest/wire round trips and canonical interaction scopes.

The full alpha acceptance also requires the shared crossing and surface/space transition scenarios. A successful profile or save test alone does not demonstrate a seamless transition or a performance target.
