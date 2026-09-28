# AstraEngine scope

AstraEngine provides the cosmos, its rendering, and the server foundation that
makes it persistent and interactive. Rendering presents synchronized state;
the server owns the player's virtual position, discoveries, and stellar evolution.

| Responsibility | AstraEngine | Consumer mod, such as SolarTech |
| --- | --- | --- |
| Systems and bodies | Identity, seeds, parameters, and generation | Which systems a gameplay scenario needs |
| Evolution | Celestial state, depletion, stages, and events | Machines that affect bodies, rewards, and balance |
| Visual scene | Celestial bodies, lighting, shaders, lensing, effects, and sound | Custom visual objects and scene content |
| Navigation | Virtual position, aiming, routes, safe stopping, and transitions | Travel availability and cost; possible ship and fuel rules |
| Worlds and persistence | Permanent identity, world bindings, and recovery after leaving | Station content, machines, inventories, and structures |
| Tools | Free camera, exploration map, scene editor, and GLSL editor | Gameplay UI and progression for the consumer mod |

Smooth planetary approach belongs to the engine: it turns object selection into
camera movement. Approaching a body does not require a SolarTech engine block,
recipe, or fuel. Gameplay ship rules need a separate contract that is still to be
defined. The current Rocket mode is a free camera for exploring and inspecting
the cosmos.

The player physically occupies a bounded void world. Interplanetary movement
changes virtual coordinates and the celestial view. Landing, walking on a surface,
and building require a real block world and a dedicated arrival process. Smooth
approach alone does not create a planet surface.

Currently, `alpha` and `beta` are the persistent building worlds; arbitrary
procedural systems do not yet receive their own block worlds. The evolution model
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
