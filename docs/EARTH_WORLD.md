# Continental Earth world preset

The **Astra Earth** world type creates an Overworld backed by the shared spherical
continental field. Existing ordinary Overworlds, legacy inspection patches and
saved terrain versions retain their generators. Create a new Astra Earth world
for the continental geographic integration; replacing coordinate labels does not
convert an older vanilla world.

On a bound planet, **R** enables free inspection at the player's actual position.
It stays in the real block world below the space boundary. Move with the usual
flight controls; **R** again leaves inspection at the reached location. Crossing
100,000 meters of radial feet altitude enters space. Flying inward through that
same physical shell prepares and enters the corresponding geographic chart;
there is no mandatory landing animation or automatic descent to the ground.
**L** explains this free crossing on canonical planets. Vehicle propulsion and
autolanding remain consumer responsibilities.

The server prepares actual neighboring chunks and the client acknowledges their
bounded block/light/biome view before a host handoff. Only that prepared transfer
suppresses Minecraft's intermediary waiting screen. Slow or unavailable terrain
retains the reached valid pose. Stop or turn away to cancel space-entry preparation;
a veto cannot move the player into an unaccepted destination. Real builds are not
cleared, and neither procedural height fields nor client acknowledgements create
collision authority. Ground inspection uses normal block collision. Already loaded,
entirely empty swept sections permit vertical movement up to 51,200 m/s; nonempty
terrain retains 2,560 m/s movement with small collision steps. Horizontal streaming
and prepared seam crossings remain bounded. The selected speed is retained across
these local constraints. Space has its separate astronomical speed range.
The map's **To orbit** button prepares an explicit 150 km shortcut above the current
geographic location, with **B** cancellation and **R** recovery to its real source.
Legacy local patches retain their separate guided route.

The bound Earth uses the Overworld's saved calendar for Sol orbital motion and
its complete axial/spin orientation. The default 365 game days span one canonical
Earth revolution; radii and orbital descriptors retain their physical values.
Surface sunlight, the rotating orbital height field and the landing point use
that same frame. Free inspection within six radii follows the nearest body's
orbital translation while preserving the camera's heading. Stopping daylight
also stops this orbital motion. A time or season-policy discontinuity safely
cancels guidance and preserves a nearby free observer's relative position.
A discontinuous calendar change rebinds the current geographic view safely. Shader animation
and stellar evolution keep their existing independent ownership and rates.

New **Astra Earth** worlds save `cave_version: 2` independently of the surface
terrain version. A deterministic body-fixed field follows folded bedding and two
fracture families. Ordinary passages are several meters high, with less frequent
junction rooms and narrow connecting shafts, down to 1,200 meters below local
terrain. Rock roofs protect shallow soil and ocean floors; some passages reach
the dry surface as entrances. This draws on the
[bedding and joint controls described by the National Park Service](https://www.nps.gov/maca/learn/nature/how-mammoth-cave-formed.htm).
It is a geometric karst approximation, not an erosion, lithology or groundwater
simulation. It does not add abandoned mineshafts or mining
machines. Ordinary biome decoration and consumer ore features still run afterward.

Missing `cave_version` decodes as zero, preserving old solid underground generators.
Saved version one retains its original broad chambers and 2,400-meter depth envelope.
Already saved chunks are never recarved. Changing a saved generator by hand is not
a migration and can introduce chunk borders. The cave field uses physical altitude
across face/band representations, including the automatic storage handoffs described below. The native distant mesh samples the exterior field; nearby actual chunks
retain their carved geometry and local lighting.

With Astra's automatic sky/lighting active, enclosed unlit cells lose the host's
pre-gamma ambient boost. Block-source light and night vision retain their behavior.
**F8** toggles the client flashlight; rebind it in Controls. The light reaches up to
64 blocks with a soft cone and works in automatic Overworld rendering. It is an
inspection light with no item or energy economy. Active Iris shader packs retain
opaque-lighting ownership and do not receive this engine light automatically.

The geographic surface also continues beyond loaded chunks as a bounded distant
mesh from the same saved height/climate field. Oceans and mountain silhouettes
use the physical Earth radius. Near the player, the mesh preserves the host's flat
chart appearance; a smooth presentation transition ends at 32.768 km in the physical
tangent view. This does not reproject host blocks or change collision. Current
chunks render over the background. The derived mesh contains procedural terrain with bounded observed edit summaries.
Only real nearby block observations are voxel interaction targets.
Beach/desert materials use the same climate classification as the block generator;
water follows the source-water surface datum. Derivative-filtered material detail
reduces distant texture shimmer. This mesh does not generate distant voxel chunks.
The near coverage mask applies only to land within the native far plane and a
16-to-48-meter neighborhood whose existing terrain sections have finished meshing.
Until then, the procedural background stays visible. The mask preserves background
water beneath translucent blocks and terrain below high-altitude views.

Terrain, ocean and cloud depth use separate, explicit projections. Clouds stop at
the visible distant terrain instead of shining through it. In Fabulous mode,
opaque depth is preserved before the host transparency resolve; its fullscreen
effect cannot masquerade as a nearby obstruction and erase clouds viewed from above.
Private color/depth
buffers are rebuilt after resize/reload; background CPU work uses immutable geography
and is canceled on context retirement. Active Iris packs keep their sky/terrain
ownership; the native far background yields while a pack is active.

The preset uses the real 6,371,000-meter reference radius. Six versioned cube faces
cover the globe, including both poles. Each face retains its six original persistent
4064-meter altitude bands; higher air bands are allocated on demand through the
[permanent planetary binding manifest](PLANET_SURFACES.md). Band zero of positive X is `minecraft:overworld`; the
remaining charts have permanent `astraengine:earth/<face>/<band>` dimension IDs.
Only visited/generated chunks consume voxel storage. Newly generated uniform rock
and water sections save directly as the standard singleton palette, avoiding a
4096-entry expansion. Edited palettes use Minecraft's normal serializer; no new
save format or global palette patch is introduced. At the FEATURES stage, Earth
heightmap priming skips section palettes which cannot satisfy the requested block
predicate. It still reads actual blocks, including earlier mod edits, and writes
the standard host heightmaps. Other generators retain Minecraft's original path.

Owned 4064-meter Earth and solid-planet chunk reads reuse at most sixteen successful
singleton block/biome decodes within that one read. The first value passes the
original codec, and reuse requires an exact canonical NBT key and the same codec;
each section receives an independent ordinary host container. Partial/error results,
mixed palettes and other generators retain the original path. Templates never cross
reads, registries or worlds, and the saved format is unchanged.
This avoids scanning thousands of water layers for a seabed outside the current
altitude band. The whole globe is not
generated at full block detail during creation.

Host Y ranges from -2032 through 2031. Physical altitude is
`hostY + band * 4064`. Bands -2 through25 cover [-10160, 103632) meters,
including the100000m space boundary. The original preset still owns bands -2 through3. The generator does not
compress mountains or insert bedrock, new sea surfaces or summit caps at band
boundaries. Horizontal storage uses a gnomonic projection, so chart block distances
are not a globally uniform metric on the sphere.

Within48 chart meters of a storage edge, the server prepares a bounded neighborhood
using expiring FULL-status chunk tickets. It sends at most32 real sections, including
saved block states, light, biomes and chest openness, under a560-KiB packet budget. Capture never
requests synchronous generation. The immutable observations cannot edit blocks or
authorize travel. They expire on departure, player replacement or disconnect;
canonical chunks remain in their original permanent worlds.

Neighboring baked block models and fluids use a cancellable CPU mesh request and
owned GPU buffers. Geometry retains section-local floats; double-derived projective
coefficients map adjacent charts relative to the camera. Native views share terrain
depth; with an Iris pack these neutral observations compose after finalization and
are not pack shadow/reflection inputs. Resource reload rebuilds GPU data while
retaining the connection's numeric observation. Ordinary movement crosses prepared
faces, poles and altitude bands automatically. Collision reads canonical neighboring
blocks; oblique faces use a conservative1/64m raster with a maximum2.21cm shape
extension. Band translation is exact. Mining and placement recompute the server
ray and permissions; container access forwards the original inventory. A copied
observation never becomes another writable chunk. Block-entity presentation has
its own bounded support and does not duplicate server inventories.

Terrain version, exact 64-bit seed, chart version, face and band are saved in the
generator codec. Unknown versions, mismatched biome faces, incomplete palettes and
fractional/overflowing identities fail validation. Startup rejects a partial or
changed active Earth preset instead of allocating replacement worlds. Ordinary
host chunk storage retains builds and block entities through shutdown and restart.

New Earth worlds select terrain version 4. It retains the broad continental structure
of version 2, adds plateau provinces, eroded uplands, foothills, rolling lowlands,
basins, finer ridged mountain spurs and connected regional river valleys. Offshore
relief includes ridges, trenches and abyssal variation. Annual-mean zonal climate
uses latitude, altitude, coastal moderation and regional moisture variation; an
upwind plateau sample supplies a bounded rain-shadow approximation. It does not
simulate tectonics or atmospheric circulation. Versions 1, 2 and 3 remain readable with their exact original relief and
biomes. Terrain and biome source versions must agree; the server sends the saved
terrain version to clients (Earth context protocol 3). Updating the mod does not
upgrade a saved world's generator. Create a new **Astra Earth** world to use v4;
editing saved version fields is not a migration.

Regional drainage uses an original Java implementation of
[Barnes, Lehman and Mulla's Priority-Flood algorithm](https://doi.org/10.1016/j.cageo.2013.04.024).
Ocean cells seed a closed six-face, 256-by-256-per-face graph. A deterministic
parent forest routes inland depressions to ocean outlets; accumulated contributing
area controls river width. Channel profiles are breached downstream, with shared
water elevations at confluences, meandering lowland courses and smooth valley
cross-sections. The graph is derived once during parallel mod setup and contains
no chunks, world references or simulation clock. There is no whole-planet voxel
pregeneration. Noncanonical pure-model seeds prepare their own immutable atlas
at construction and must be constructed on a startup/worker thread.

Version 4 replaces the coarse straight connections with bounded spherical cubic
curves. Joined channels share endpoint positions and tangent directions; their
valley walls rise from the channel instead of forming broad flat shelves. Wet
channel water survives overlapping dry banks, and local riparian moisture keeps
an incised temperate valley from becoming an artificial desert strip. This is a
regional drainage approximation, not a fluid or sediment simulation. Small streams
below the graph resolution are still outside its scope.

The same sampler returns bed elevation, water elevation, temperature and moisture.
Chunk columns, landing queries and distant/orbital presentation
use those observations. Regional rivers are ordinary Minecraft water over gravel;
a twelve-meter rock roof protects submerged river beds from the cave carver.
A dedicated saved river biome entry retains host river decoration. The twelve
existing climate classes retain their registry-owned ocean, beach, snow, alpine,
desert, savanna, jungle, taiga, forest and plains features. Consumers can extend
these through biome modifiers and placed features. No structure sets are added.

This is routed procedural geography, not measured Earth drainage, a hydraulic
erosion model or a rainfall/groundwater simulation. Small streams below the
regional graph resolution and a seasonal discharge simulation are not supplied.
The host still represents sloping water in block-height steps. At coarser orbital
or distant mesh resolution, channels narrower than a sample interval can disappear;
local orbital tiles retain visible river water height and a water material mask.
The v3 distant mesh locally bisects wet/dry edges with shared midpoint vertices;
four bounded refinement passes reduce jagged banks without introducing cracks or
raising the resolution of the entire planetary mesh. Geometry is capped at 60,000
vertices per retained background mesh.

Snow and freezing use physical latitude/elevation in Earth generation and server
weather checks. A narrow `Biome.shouldSnow`/`shouldFreeze` injection replaces the
temperature predicate because those host methods otherwise apply their own local-Y
lapse rate. All other block, light, survival and water-edge checks remain host-owned.
Legacy worlds retain the original predicate. Submerged storage ceilings cannot
become artificial ice surfaces. This is the base climate calculation; a seasonal
snow accumulation/melting simulation is not supplied by this hook.

The sky reads geographic latitude/longitude and physical altitude from these charts.
Its 1800..3200-meter cloud layer retains the same sea-level datum in every altitude
band; see [seasons and atmosphere](SEASONS.md).

In space, the server-selected Earth uses samples of this exact field. A 1025x513
global height/climate map and four 513x513 local tiles at 4/32/256/1024-meter spacing
provide progressive detail. Tile edges blend into coarser levels. Height interpolation uses shader float
arithmetic instead of limited-precision hardware filter weights, preventing
meter-high terraces in kilometer-scale relief. These RGBA32F
textures occupy about 24.1 MiB; generation runs on a cancellable host worker request,
never through synchronous chunk generation in a render callback. Resource reload
and logout release owned textures. Legacy saves retain their versioned geography.
The bounded relief march resolves height-dependent silhouette and parallax; its
step budget still limits very thin terrain features at grazing angles. Atmospheric
transport ends at the actual mountain hit, avoiding haze integrated behind terrain.

Orbital and distant-mesh materials use an immutable palette captured on the render
thread from active block-atlas textures and the default Earth biomes' foliage,
grass and water tints. Forest, jungle and taiga summaries weight their canopies;
they do not reconstruct individual trees. Exposed freezing and snow cover use the
same physical zero-Celsius weather threshold and quantized surface altitude as
the Earth weather hook. This does not change saved biome classes or terrain versions.
Ice carries its own solid material flag, so a pale blue texture cannot accidentally
select ocean reflections. Resource reload and logout invalidate the captured palette.

The orbital HDR path shades host display colors before decoding its display transform.
This avoids brightening green canopies twice and amplifying white materials at night.
Atmospheric scattering, sunlight and exposure still change their visible appearance.
These are base regional summaries. A separate bounded layer of observed surface
edits adds buildings, excavation, changed surface materials and exposed emission
to nearby orbital and distant views; see [persistent surface summaries](PLANET_SURFACES.md).
Custom consumer biome-palette synchronization remains outside the base palette.

The server announces the Earth binding at login. The client retains it across
resource reload and clears it on logout. F3 then displays longitude, latitude and
physical altitude in these charts; reduced-debug privacy is preserved. It never
infers an Earth binding solely from the Overworld name.

`AstraGeography.planetaryReference(ServerLevel)` exposes the immutable
`GeographicReference` projection on the owning server thread. `snapshot` and
`resolve` use it, including exact differential velocity. The older patch-only
`reference(ServerLevel)` method retains its original signature and behavior.
`/astra geography here` reads the binding; `tp` currently resolves within the
current chart and retains the operator, travel-ownership and host-veto checks.

Verification includes every chart codec, actual generated ProtoChunks, independent
column/heightmap agreement, pole/edge coordinates, altitude ownership, climate
temperature, native tree decoration, geographic F3 and an independent-process
restart retaining player pose, placed blocks and chest inventory. New version-two
creation and a saved version-one world both pass independent-process restart.
The orbital fixture checks full-globe and close relief, exact uploaded terrain
texels, resource reload, cache retirement and texture-binding restoration on
Fabulous. Native scenarios:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-earth -PverifyPhase=earth-generation-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-earth -PverifyPhase=earth-generation-restart
```

The `earth-boundary-observations` phase checks real band/face/corner preparation,
canonical edits delivered over the actual wire, immutable prior observations,
visible gold-to-emerald replacement and owned ticket retirement. It uses a fresh
Earth preset and preserves its evidence separately from player saves.

The separate `earth-orbit` phase verifies the orbital presentation path. The
`earth-materials` phase captures forest, taiga, jungle, ice and snow by day and
night, reads back actual GPU palette uniforms, and verifies invalidation on resource
reload. It uses `/astra-flight map` to avoid another mod's M-key binding in a test pack.
The `earth-landscape` phase covers lowland, summit, high-altitude, coast, pole, face-edge
and foothill views, plus resource reload and resize. It retains screenshots and
31 asynchronous GPU timer samples per view; these measure the complete `AFTER_SKY`
event interval, including any optional handlers, rather than total frame time.
`earth-landscape-pack` checks the same scenes while an actual Iris pack owns rendering.
The geographic
travel fixture uses a fresh `earth-travel-create` directory, then
`earth-travel-restart` in that same completed directory. It exercises actual
navigation packets, forest and mountain departures, both polar charts, delayed
body-fixed aiming, stale input epochs, collision-checked arrival, canceled ascent
and persistence of pose, blocks and a chest inventory. Verification owns disposable
worlds and never modifies a user's existing save.

Use a fresh disposable directory for creation. Restart accepts only that completed
fixture; verification code and worlds are not included in the distributed JAR.

The `earth-underground` native phase creates real cave chunks, compares dark,
flashlight, local block emission and night-vision images, and exercises rebinding,
menu suppression, resource reload and disconnect. Pure connected-component checks
and dedicated carver/legacy-codec/ocean-roof tests complement this visible scenario.

Six real generated river chunks are checked by dedicated GameTests, including
their water level, river biome and protected cave roofs. Pure checks cover outlet
reachability, downhill profiles, confluences, globe seams and bounded shoreline
refinement. Earlier DH-specific river measurements are retired historical evidence;
the planetary renderer and alpha qualification now use the native landscape.
