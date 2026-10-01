# Continental Earth world preset

The **Astra Earth** world type creates an Overworld backed by the shared spherical
continental field. It is an opt-in integration build while planetary travel and
storage-boundary presentation are being connected. Existing Overworlds, saved
legacy landing patches and continental inspection worlds keep their generators.
Do not select this preset expecting the complete orbital landing workflow yet:
the current `L` landing action still targets the legacy Moon/Earth patches.

The preset uses the real 6,371,000-meter reference radius. Six versioned cube faces
cover the globe, including both poles. Each face has six persistent, disjoint
4064-meter altitude bands. Band zero of positive X is `minecraft:overworld`; the
remaining charts have permanent `astraengine:earth/<face>/<band>` dimension IDs.
Only visited/generated chunks consume voxel storage. The whole globe is not
generated at full block detail during creation.

Host Y ranges from -2032 through 2031. Physical altitude is
`hostY + band * 4064`, covering [-10160, 14224) meters. The generator does not
compress mountains or insert bedrock, new sea surfaces or summit caps at band
boundaries. Horizontal storage uses a gnomonic projection, so chart block distances
are not a globally uniform metric on the sphere. Automatic walking across chart
or band boundaries and their connected views are still under integration; these
are separate from the already implemented address and storage mappings.

Terrain version, exact 64-bit seed, chart version, face and band are saved in the
generator codec. Unknown versions, mismatched biome faces, incomplete palettes and
fractional/overflowing identities fail validation. Startup rejects a partial or
changed active Earth preset instead of allocating replacement worlds. Ordinary
host chunk storage retains builds and block entities through shutdown and restart.

The immutable continental sampler supplies elevation, temperature and moisture.
Twelve named climate classes select registry-owned ocean, beach, snow, alpine,
desert, savanna, jungle, taiga, forest and plains biomes. Their normal vegetation
features remain active; consumers can extend them through ordinary biome modifiers
and placed features. This version does not generate structure sets or carvers.

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
restart retaining player pose, placed blocks and chest inventory. Native scenarios:

```sh
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-earth -PverifyPhase=earth-generation-create
./gradlew runVerifyClient -PverifyDirectory=Workflow/verification/my-earth -PverifyPhase=earth-generation-restart
```

Use a fresh disposable directory for creation. Restart accepts only that completed
fixture; verification code and worlds are not included in the distributed JAR.
