# AstraEngine scope

AstraEngine provides the cosmos, its rendering, and the server foundation that
makes it persistent and interactive. Rendering presents synchronized state;
the server owns the player's virtual position, discoveries, and stellar evolution.

| Responsibility | AstraEngine | Consumer mod, such as SolarTech |
| --- | --- | --- |
| Systems and bodies | Identity, seeds, parameters, and generation | Which systems a gameplay scenario needs |
| Evolution | Celestial state, depletion, stages, and events | Machines that affect bodies, rewards, and balance |
| Visual scene | Celestial bodies, lighting, shaders, lensing, effects, and sound | Custom visual objects and scene content |
| Planetary sky | Orbital/rotation geometry, seasons, atmosphere and visual sky lighting | Biome/crop/snow rules and gameplay responses to seasons |
| Navigation | Virtual position, aiming, routes, safe stopping, and transitions | Travel availability and cost; possible ship and fuel rules |
| Worlds and persistence | Permanent identity, world bindings, and recovery after leaving | Station content, machines, inventories, and structures |
| Ship presentation | Render consumer-supplied visual geometry, transforms and motion through an engine API | Part catalog, assembly/editor UI, engineering statistics, resources, crafting and progression |
| Tools | Free camera, exploration map, scene editor, and GLSL editor | Gameplay UI and progression for the consumer mod |

Smooth planetary approach belongs to the engine: it turns object selection into
camera movement. Approaching a body does not require a SolarTech engine block,
recipe, or fuel. Gameplay ship rules need a separate contract that is still to be
defined. The current Rocket mode is a free camera for exploring and inspecting
the cosmos.

Chart visibility and a recorded visit are separate engine states. Under the default policy, first manual
arrival unlocks fast travel and reveals neighboring systems; consumers can reveal
custom content through the API but cannot use discovery to grant a visit. Manual
flight crosses system boundaries while the real player stays in the void.

Operator [navigation rules](NAVIGATION_API.md) may explicitly permit a first jump
without prior discovery/visits and set its duration. Consumer maps use a client
replacement event and immutable request handle; server authority is preserved.

Procedural system/body descriptors and spatial galactic environments are
implemented. The versioned [cosmic atlas](UNIVERSE.md) supplies nine galaxies and named
nebula, cluster, remnant and nucleus regions with navigable anchors;
the shader background is not a complete navigable census of astronomical objects.

The player physically occupies a bounded void world. Interplanetary movement
changes virtual coordinates and the celestial view. Landing, walking on a surface,
and building require a real block world and a dedicated arrival process. Smooth
approach alone does not create a planet surface.

Persistent building worlds include `alpha`, `beta`, and the first bounded
[Moon/Earth surface patches](SURFACE_TRAVEL.md). Arbitrary procedural systems
do not yet receive their own block worlds. The evolution model
and controlled solar cycle exist, but production energy economics, Hawking losses,
and the white-hole finale are unfinished. These are intended responsibilities,
not claims that every subsystem is complete.

The public celestial API creates saved planets, stars, black holes, and systems,
and grants private discovery. They use the existing map, navigation, and rendering.
See the [creation guide](CELESTIAL_API.md) for examples and limits. It creates no
new block worlds and does not bind custom bodies to extraction or stellar evolution.

The responsibility boundary is broader than the available public API. Supported
calls for the current slice are documented in [API.md](API.md); internal navigation
classes and network packets are not a stable SolarTech API. Consumer integration
must first define permissions, movement limits, and result handling.

See [cosmos controls and scale](COSMOS.md) and [rendering](RENDERING.md).


## Accepted Rocket Editor extraction

The Rocket Editor belongs to SolarTech. SolarTech owns its complete construction
workflow, part catalog and parameters, assembly persistence, crafting, and
mass/thrust/TWR/delta-v/energy calculations. AstraEngine retains ship visualization:
it consumes a prepared visual configuration with geometry and transforms/motion.
The renderer must not interpret propulsion types, fuel economics or engineering
statistics. The existing astronomical free camera remains an engine tool.

The construction implementation is now removed from AstraEngine. No SolarTech
project or gameplay replacement is created here. The renderer accepts immutable
[visual values](SHIP_RENDERING.md); the engine does not supply a catalog, editor,
engineering calculator, deployment service or constructor network protocol.

Legacy editor blocks/items and assembly entity IDs remain registered solely as
[inert save archives](ROCKET_EDITOR.md). They retain opaque stored data and have
no construction UI, gameplay ticking or rendering of old assemblies. Preserving
these IDs prevents existing data from disappearing during load/save. Actual
transfer into a future consumer remains a separate migration.

[Seasonal sky](SEASONS.md) uses Minecraft's saved time with a 365-day year and
20-minute mean solar days. Its current world adapter targets the default Overworld;
the pure ephemeris/profile API is reusable. Seasonal presentation does not override
Minecraft's sleep eligibility, spawning or authoritative block-light rules.
