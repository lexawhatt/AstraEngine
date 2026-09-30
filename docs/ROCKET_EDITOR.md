# Removed Rocket Editor and existing saves

The Rocket Editor has been removed from AstraEngine. Its UI, part catalog,
parameter schemas, engineering calculations, blueprint API, deployment service
and editor network messages are no longer shipped. AstraEngine now provides a
[visual ship renderer](SHIP_RENDERING.md) that accepts already-prepared geometry,
materials and transforms. No SolarTech project is bundled or implemented.

The complete former implementation is preserved in Git snapshot
[`ac0c980`](https://github.com/lexawhatt/AstraEngine/tree/ac0c980d9423d5b3ee605c3ed0c6770b23a185f3).
It can be used as source material when construction work begins in SolarTech.
That historical snapshot is not the current engine API.

## Compatibility archives

Existing worlds may contain `astraengine:rocket_editor` blocks/items and
`astraengine:rocket_assembly` entities. Removing their registry entries outright
would risk losing saved data, so the engine keeps small inert compatibility types.
They are absent from the creative catalog and provide no construction behavior.

- Former editor blocks show an archive notice when clicked. Their original
  version, revision, blueprint and deployed-assembly reference are copied as
  opaque NBT under the same keys. The engine does not interpret part/module data.
- Former assemblies retain their entity UUID, position and original opaque
  blueprint/host fields. They do not render, collide, run propulsion or disappear
  because an editor is missing. They remain saved for future migration.
- Existing editor items keep their registry identity and native item data.
  Compatibility blocks resist ordinary survival destruction and explosions;
  explicit creative/command removal still follows Minecraft behavior.

These archives are not a working editor and do not migrate records into SolarTech.
The future consumer must define and verify that transfer. Do not delete the
archives if you intend to recover construction data later. No existing user world
is converted, reset or used as a test fixture by the build.

The astronomical **R** free camera, map, scene editor and GLSL text editor are
independent engine tools and remain available.
