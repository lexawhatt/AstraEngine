# Mars surface and atmosphere

Sol Mars retains its catalog radius of 3,389,500 meters, orbit and permanent body/dimension identities.
Its surface uses the same body-fixed elevation field for host chunks, native distant landscape and orbital relief. These are procedural approximations, not a map of observed Martian geography.

Newly bound Mars uses solid profile v2: red sand represents oxidized loose regolith, terracotta represents
exposed oxidized rock, and the extreme polar surface uses packed ice. Subsurface dust becomes terracotta;
the deep rock remains stone. Orbital and landscape colors are sampled from those actual host textures,
including resource-pack replacements. Elevations, crater geometry and chart addresses are unchanged from v1.
Other generated solid profiles retain v1 and their previous material policy.

An existing saved Mars v1 binding remains authoritative. Its gravel/stone blocks, procedural material field,
and subsequent charts are not silently upgraded; the renderer consumes that same saved version. Visiting,
restarting or opening another altitude band cannot replace it with v2. There is no automatic material migration.

Both versions use a Mars-specific atmospheric presentation: weak molecular scattering, warm suspended dust,
a thin orbital limb, and warm surface ambient light/fog. A blue twilight component is confined to the direction
near the Sun; it does not turn the whole sky or rim blue. The same stellar source scales the effect, including
solar dimming. This is a bounded display approximation, not a pressure, weather or dust-storm simulation.
It changes neither block light nor gameplay survival rules. Active Iris packs retain their documented host
lighting ownership; Astra's late astronomical composition is not an Iris material.

The color direction follows [NASA's Mars facts](https://science.nasa.gov/mars/facts/) and its explanation of
[Martian sunrises and sunsets](https://science.nasa.gov/solar-system/planets/mars/what-does-a-sunrise-sunset-look-like-on-mars/):
oxidized material gives Mars its reddish appearance, while fine dust produces a localized blue glow near the
low Sun. This implementation does not treat enhanced or false-color orbital imagery as a literal terrain palette.

The isolated `mars-visual` verification phase captures the production orbital material, the 100 km limb,
actual generated v2 ground and twilight. Dedicated tests compare real generated blocks against procedural
column runs and verify that saved v1 bindings continue to win over new defaults. Run results belong to the local
evidence log; successful compilation alone is not visual acceptance.
