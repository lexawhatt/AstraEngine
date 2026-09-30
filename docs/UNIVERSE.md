# Galaxies, regions, and the cosmic atlas

The cosmic atlas extends the original system catalog with a seeded group of
nine galaxies. Galaxy geometry and named regions are shared by navigation and
the renderer. They are spatial objects with coordinates; flying through a region
changes its apparent size and surroundings continuously.

## Exploring

Press **R** to enter Rocket mode, then **M -> Cosmic atlas**, or use
`/astra-flight atlas`. Select a galaxy in the left column and an object in the
right column. **Chart and aim** asks the server to add that public destination
to your chart and turns the camera toward it after acknowledgement.

Use `/astra-flight speed galactic` for a distant galaxy or galactic-center trip,
then hold **W**. Charting never teleports the camera or grants a visit. The first
manual arrival unlocks fast travel and reveals nearby systems. The camera stops
and returns to a local inspection speed at the system boundary. **M -> Approach
body** then provides a continuous close approach to the star or black hole.
Previously visited destinations remain available in **Known systems**.

For an external galaxy view, `/astra-flight galaxy aim` points at the nearest
galaxy center. Select galactic speed and fly away from its disk, stop with **B**,
then aim again. The command changes heading only. Inside nebulae and clusters,
use lower speeds or the mouse wheel to inspect the spatial structure.

The atlas is public, while custom systems created through the consumer API
remain private until explicitly revealed. Selecting an atlas row does not
modify either chart or visits. Charts retain the existing 256-system cap without
eviction; already known destinations remain usable when the chart is full.

## Contents

The Milky Way retains the existing center and nominal 50000-light-year radius.
Eight seeded neighbors occupy a bounded group around it, with distinct sizes,
orientations and spiral, elliptical or irregular forms. Their placement is an
artistic group, not an astronomical reconstruction of the Local Group.

Each galaxy has seven named destinations:

| Region | Appearance and local destination |
| --- | --- |
| Nucleus | Supermassive black hole with a compact population of orbiting stars |
| Emission nebula | Emitting gas, dust and embedded stellar population |
| Dark nebula | Absorbing dust obscuring background light |
| Open cluster | Young-looking blue stars in a loose concentration |
| Globular cluster | Dense, warm stellar concentration in the halo |
| Supernova remnant | A spatial gas shell surrounding a local remnant scene |
| Nuclear cluster | Dense stellar environment close to the galactic center |

One generated galaxy has an active nucleus: its accreting central black hole
powers the quasar presentation and jets. The Milky Way's central hole is not
treated as a permanently bright quasar. The model uses an approximate central
black-hole mass, while stellar population, color and gas structure are authored
procedural content. Core light comes from the surrounding population and
accretion; the horizon retains its physical local scale instead of being enlarged
to cover a visible fraction of the entire galaxy.

[NASA's galactic-center overview](https://science.nasa.gov/mission/webb/science-overview/science-explainers/what-is-the-center-of-our-galaxy-like/)
explains the dense central stellar population and Sagittarius A*;
[NASA's quasar overview](https://science.nasa.gov/mission/hubble/science/science-behind-the-discoveries/hubble-quasars/)
describes active accreting nuclei. The generated atlas is a visual/game model,
not an observational catalog or a relativistic simulation.

Remnant regions are not a population-wide stellar birth/death simulation. The
existing [controlled solar supernova](SOLAR_SKY.md) remains the timed explosion
demo, separate from static atlas identities and the economy of a consumer mod.

## Identity and population

`UniverseGenerator` is a pure Java catalog, with immutable `GalaxyDescriptor`
and `CosmicRegion` values. Atlas version **1** is independent of the original
system generator version **1**. Queries neither allocate worlds nor mutate visits.

- `galaxies(seed)` returns the nine galaxy descriptors.
- `regions(seed, galaxyIndex)` returns the seven named regions.
- `landmarkSystems(seed, galaxyIndex)` resolves their local systems.
- `find(seed, id)` resolves built-in content, returning empty for an unpopulated
  canonical universe sector. Invalid identities throw.
- `nearby(seed, currentSystem, radiusSectors)` queries the relevant local population;
  the radius remains bounded to 0-2 sectors.

Atlas systems use `u_<galaxy>_<region>`, for example `u_0_0` for the Milky Way
nucleus. Galaxy-relative sector systems use `v_<galaxy>_<x>_<y>_<z>`, with signed
integer coordinates in an oriented four-light-year grid. Density depends on the
galaxy and its regions; empty sectors remain empty. A seed and canonical identity
reproduce the same immutable descriptor independently of visit order.

Original `sol` and `s_<x>_<y>_<z>` definitions remain unchanged, including previously
visited positions. Their legacy neighborhood queries retain their original
behavior. New atlas destinations and their sector neighborhoods use the new
population. Background unresolved light represents more stars than the bounded
set of local bodies drawn per frame; it is not a one-to-one catalog of every
visible speck.

Nearby catalog points are bounded to 24 visible primary stars from a cached
27-sector neighborhood. Exact legacy points occupy a Sol-centered 64-light-year
zone with a four-light-year boundary fade; new galaxy population points apply
outside it. Distant legacy destinations remain navigable, but the renderer does
not separately draw every original sector. See [rendering budgets](RENDERING.md).

Galaxy and region coordinates are absolute light-years. Bodies and flight
positions retain double meters relative to their current system, narrowed only
after camera-relative subtraction. The physical player remains in the bounded
void staging world. Planetary terrain, arbitrary persistent dimensions and
biological life are outside this atlas feature.

## Save and request contract

Exploration NBT **v6** retains `universe_version`, adds `satellite_version`, and
preserves previous custom systems, known/visited lists, exact navigation pose,
speed and shared clock.
Readable v1-v5 saves migrate without changing existing system definitions. Old
v1-v3 charts infer visits conservatively as before; v4/v5 visits remain exact.
Unknown atlas versions, dangling identities and empty saved sectors are rejected;
unreadable existing data is never silently replaced by a new catalog.

Navigation protocol **v6** and action protocol **v4** require matching clients
and servers. `CHART_ATLAS` accepts only the bounded public `u_` identities for
the requesting player's active idle flight session, with the existing rate limit.
It cannot reveal private custom content, grant visits, or bypass fast-travel
checks. The client waits for a server chart snapshot before aiming; pending aim
expires or is discarded on a navigation-epoch change or disconnect.

Rendering consumes the shared descriptors and authoritative navigation/clock.
Resource reload preserves catalog identity and disposes only owned GPU resources.
There is no second display transform or restored transit-streak effect. Visual
limits and measured verification are documented with the implementation; no
unlimited scene complexity, shader-pack compatibility or frame-rate guarantee
is implied by the atlas size.
