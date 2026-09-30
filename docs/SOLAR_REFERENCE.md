# Solar System: physical scale

The initial `sol` system contains the Sun, eight planets and 21 major natural
satellites: **30 selectable bodies**. Mercury and Venus remain moonless. Radii and orbital
semi-major axes are stored as `double` meters, without compressing distances or
enlarging planets for visibility. Galactic system coordinates use light-years
separately. Before uploading to a shader, the observer position is subtracted
from the body position in `double`; Minecraft coordinates never grow to
astronomical magnitudes.

The values below come from individual NASA NSSDCA fact sheets, checked on
2026-09-27. Radius is the **volumetric mean radius**, rather than half the
equatorial diameter from the summary table. Periods are sidereal. Values retain
the precision published by their sources; "1:1" means there is no gameplay scale
factor, not that astronomical measurements have absolute precision.

| Body / NASA source | Radius, km | Semi-major axis, million km | Period, days | Eccentricity | Orbital inclination, degrees | Axial tilt, degrees |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| [Sun](https://nssdc.gsfc.nasa.gov/planetary/factsheet/sunfact.html) | 695700 | - | - | - | - | 7.25 |
| [Mercury](https://nssdc.gsfc.nasa.gov/planetary/factsheet/mercuryfact.html) | 2439.7 | 57.909 | 87.969 | 0.2056 | 7.004 | 0.034 |
| [Venus](https://nssdc.gsfc.nasa.gov/planetary/factsheet/venusfact.html) | 6051.8 | 108.210 | 224.701 | 0.0068 | 3.395 | 177.36 |
| [Earth](https://nssdc.gsfc.nasa.gov/planetary/factsheet/earthfact.html) | 6371.0 | 149.598 | 365.256 | 0.0167 | 0.000 | 23.44 |
| [Mars](https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html) | 3389.5 | 227.956 | 686.980 | 0.0935 | 1.848 | 25.19 |
| [Jupiter](https://nssdc.gsfc.nasa.gov/planetary/factsheet/jupiterfact.html) | 69911 | 778.479 | 4332.589 | 0.0487 | 1.304 | 3.13 |
| [Saturn](https://nssdc.gsfc.nasa.gov/planetary/factsheet/saturnfact.html) | 58232 | 1432.041 | 10755.699 | 0.0520 | 2.486 | 26.73 |
| [Uranus](https://nssdc.gsfc.nasa.gov/planetary/factsheet/uranusfact.html) | 25362 | 2867.043 | 30685.400 | 0.0469 | 0.770 | 97.77 |
| [Neptune](https://nssdc.gsfc.nasa.gov/planetary/factsheet/neptunefact.html) | 24622 | 4514.953 | 60189.018 | 0.0097 | 1.770 | 28.32 |

## Natural satellites

The following NASA NSSDCA tables were checked on 2026-09-30. Distances are from
the **parent planet's center**, not the Sun. The Moon occupies catalog index 9;
all later satellites are appended. The original Sun/eight planet IDs, indices,
fields and orbital positions are unchanged.

For nonspherical moons, an `a x b x c` entry lists the published semiaxes in km;
the shader sphere uses the equal-volume radius `cbrt(a*b*c)`. Other radii use the
published scalar value. These approximations do not enlarge the bodies.

| Parent / NASA source | Moon | Radius or semiaxes, km | Semimajor axis, thousand km | Period, days | Eccentricity | Source inclination, degrees |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| [Earth](https://nssdc.gsfc.nasa.gov/planetary/factsheet/moonfact.html) | Moon | 1737.4 | 384.4 | 27.3217 | 0.0549 | 5.145 |
| [Mars](https://nssdc.gsfc.nasa.gov/planetary/factsheet/marsfact.html) | Phobos | 13.0 x 11.4 x 9.1 | 9.378 | 0.31891 | 0.0151 | 1.08 |
| Mars | Deimos | 7.8 x 6.0 x 5.1 | 23.459 | 1.26244 | 0.0005 | 1.79 |
| [Jupiter](https://nssdc.gsfc.nasa.gov/planetary/factsheet/joviansatfact.html) | Io | 1821.5 | 421.8 | 1.769138 | 0.004 | 0.04 |
| Jupiter | Europa | 1560.8 | 671.1 | 3.551181 | 0.009 | 0.47 |
| Jupiter | Ganymede | 2631.2 | 1070.4 | 7.154553 | 0.001 | 0.18 |
| Jupiter | Callisto | 2410.3 | 1882.7 | 16.689017 | 0.007 | 0.19 |
| [Saturn](https://nssdc.gsfc.nasa.gov/planetary/factsheet/saturniansatfact.html) | Mimas | 208 x 197 x 191 | 185.52 | 0.9424218 | 0.0202 | 1.53 |
| Saturn | Enceladus | 257 x 251 x 248 | 238.02 | 1.370218 | 0.0045 | 0.00 |
| Saturn | Tethys | 538 x 528 x 526 | 294.66 | 1.887802 | 0.0000 | 1.86 |
| Saturn | Dione | 563 x 561 x 560 | 377.40 | 2.736915 | 0.0022 | 0.02 |
| Saturn | Rhea | 765 x 763 x 762 | 527.04 | 4.517500 | 0.0010 | 0.35 |
| Saturn | Titan | 2575 | 1221.87 | 15.945421 | 0.0292 | 0.33 |
| Saturn | Iapetus | 746 x 746 x 712 | 3560.85 | 79.330183 | 0.0283 | 14.72 |
| [Uranus](https://nssdc.gsfc.nasa.gov/planetary/factsheet/uraniansatfact.html) | Miranda | 240 x 234.2 x 232.9 | 129.90 | 1.413479 | 0.0013 | 4.34 |
| Uranus | Ariel | 581.1 x 577.9 x 577.7 | 190.90 | 2.520379 | 0.0012 | 0.04 |
| Uranus | Umbriel | 584.7 | 266.00 | 4.144176 | 0.0039 | 0.13 |
| Uranus | Titania | 788.9 | 436.30 | 8.705867 | 0.0011 | 0.08 |
| Uranus | Oberon | 761.4 | 583.50 | 13.463234 | 0.0014 | 0.07 |
| [Neptune](https://nssdc.gsfc.nasa.gov/planetary/factsheet/neptuniansatfact.html) | Triton | 1353.4 | 354.76 | 5.876854 | 0.000016 | 157.345 |
| Neptune | Proteus | 220 x 208 x 202 | 117.647 | 1.122315 | 0.0004 | 0.04 |

The Moon's inclination is relative to the ecliptic. Other listed inclinations are
relative to the parent equator; the shared-node model adds the parent's axial tilt
to map them approximately into system axes. It does not reconstruct measured pole
longitudes, ascending nodes or precession. Triton's tilted retrograde orbit is
retained. Lunar obliquity uses the NASA value 6.68 degrees; other satellite material
spins are artistic and do not model tidal locking or libration. Colors and Titan's
visible atmosphere are presentation choices. Small/irregular satellite populations
outside this explicit list are not claimed to be complete.

The navigable Moon is separate from the current Overworld sky Moon, which still
uses Minecraft's eight-phase display. This catalog addition does not replace that
host sky cycle with a lunar ephemeris.

## Orbital model and units

Unit conversions: 1 km = 1000 m; 1 day = 86400 s. An astronomical unit is exactly
149597870700 m under the
[IAU definition](https://iauarchive.eso.org/public/themes/measuring/).
The code defines a light-year as `299792458 * 365.25 * 86400` meters: the distance
light travels in a Julian year. Earth's semi-major axis in the NASA table is
rounded, so it is not bit-for-bit equal to the AU constant.

`CelestialBody.positionAt(seconds)` solves Kepler's equation for a parent-relative
elliptical orbit. `CosmosSystem.positionAt(body, seconds)` adds the parent's entire
ancestry at that same instant to obtain system-local meters. Each body's initial phase is chosen for the scene; node and
periapsis longitudes are simplified to a common orientation. These are neither
J2000 ephemerides nor planetary positions for the current date. Inclinations and
periods feed the model, while texture rotation, color, clouds, atmospheres, ring
extents, and brightness are artistic representations. Oblateness, unlisted moons, minor
bodies, mutual orbital perturbations, and relativistic effects are not modeled.

At physical scale, the Sun subtends approximately 0.533 degrees at 1 AU; most
distant planets are smaller than a pixel. A close view requires moving the camera
toward the body. Enlarged map icons help select objects without changing their
scene radii. Removing chunk-distance culling does not increase angular size.
The separate [Overworld sky](SEASONS.md) has an explicit apparent Sun multiplier
(default 3) for visibility; this does not modify the physical cosmos parameters.

Procedural systems outside `sol` are reproduced from the galaxy seed and three
integer sector coordinates. Each sector spans 4 light-years and contains one
center, offset by up to 1.2 light-years on each axis; `sol` always occupies the
center of sector `(0,0,0)`. A canonical ID is `sol` or `s_<x>_<y>_<z>`, such as
`s_-1_2_0`. Coordinates use signed 32-bit integers. Neighbor queries accept a
radius of 0-2 sectors, return at most 125 systems, and sort by distance. Searches
at integer boundaries are clipped without wrapping to the opposite boundary.

Generator version 1 assigns 78% to single-star systems, 18% to binaries, 2% to
black-hole systems, and 2% to visual supernova remnants. These are gameplay
template weights, not statistics of the real Universe. Systems have 2-9 separated
planetary orbits, approximately within 1-86 AU, several materials, and rings around
some gas giants. Binary stars follow circular orbits around a shared center.
An independent satellite-generation version 1 appends 0-2 candidates to rocky,
ocean and ice planets and 1-4 to gas giants, with a deterministic per-parent seed.
Candidates outside the authored stability envelope are omitted. Estimated parent
density/mass determines periods; these procedural moons are fictional. Their
orbits clear parent surfaces/rings and remain inside a fraction of the estimated
Hill region. The old parent generator stream and all existing parent descriptors
remain unchanged; custom authored systems are never automatically populated.

The descriptor catalog allows 64 bodies, while the celestial GPU pass selects at
most 12 by apparent size/distance, retaining the primary and nearest black-hole
lens. Selection changes rendering work only: every satellite remains in the map,
navigation, collision and persistence contracts at its physical scale. Exploration
v6 records the separate satellite version; old saves migrate without changing
the saved camera pose or previously authored definitions.
The generator returns only immutable descriptors: it does not allocate dimensions,
discover objects for a player, or start system evolution.

## Additive universe atlas

The legacy IDs and values above remain unchanged. [Universe atlas version 1](UNIVERSE.md)
adds `u_` region anchors and density-conditioned `v_` systems in galaxy-relative
sectors. Galaxy transforms and region descriptors are shared with rendering;
these additions do not reposition Sol or previously saved `s_` systems.
