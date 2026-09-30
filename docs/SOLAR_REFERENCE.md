# Solar System: physical scale

The initial `sol` system contains the Sun and eight planets. Radii and orbital
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

Unit conversions: 1 km = 1000 m; 1 day = 86400 s. An astronomical unit is exactly
149597870700 m under the
[IAU definition](https://iauarchive.eso.org/public/themes/measuring/).
The code defines a light-year as `299792458 * 365.25 * 86400` meters: the distance
light travels in a Julian year. Earth's semi-major axis in the NASA table is
rounded, so it is not bit-for-bit equal to the AU constant.

`CelestialBody.positionAt(seconds)` solves Kepler's equation for an independent
elliptical orbit. Each body's initial phase is chosen for the scene; node and
periapsis longitudes are simplified to a common orientation. These are neither
J2000 ephemerides nor planetary positions for the current date. Inclinations and
periods feed the model, while texture rotation, color, clouds, atmospheres, ring
extents, and brightness are artistic representations. Oblateness, moons, minor
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
The generator returns only immutable descriptors: it does not allocate dimensions,
discover objects for a player, or start system evolution.

## Additive universe atlas

The legacy IDs and values above remain unchanged. [Universe atlas version 1](UNIVERSE.md)
adds `u_` region anchors and density-conditioned `v_` systems in galaxy-relative
sectors. Galaxy transforms and region descriptors are shared with rendering;
these additions do not reposition Sol or previously saved `s_` systems.
