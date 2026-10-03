# Earth atmospheric transport

Earth's orbital atmosphere derives its color from the solar direction, the
camera ray, the physical body radius and the atmospheric density along both
paths. It does not assign a uniform colored rim to the planet. Mars uses its
separate thin dust atmosphere; airless bodies do not use this transport model.

## Optical model

The Earth model has an 80 km integration boundary, exponential Rayleigh and
aerosol density profiles with 8 km and 1.2 km scale heights, and a triangular
ozone layer centered at 25 km. RGB extinction coefficients are renderer
approximations in inverse kilometers. Solar visibility includes the solid
planet's shadow and a finite solar-disc transition. The view integral uses
Rayleigh and Henyey-Greenstein phase functions and accumulates scattering and
Beer-Lambert transmission in linear color before celestial exposure.

The solar optical columns follow the spherical distance/radius parameterization
described in [Bruneton's reference atmosphere implementation](https://ebruneton.github.io/precomputed_atmospheric_scattering/atmosphere/functions.glsl.html).
A ray starting above the atmosphere first intersects the actual outer shell;
its impact parameter is preserved. A camera-to-cloud segment uses the difference
between two optical columns along the same ray. A downward segment uses the
opposite direction when the forward continuation intersects the ground.

This is bounded single scattering, not a complete spectral or multiple-scattering
atmosphere solver. Radiance uses the renderer's relative linear units rather than
calibrated physical sensor units. The separate Minecraft opaque world path is
still LDR. Canonical body radii and terrain heights are unchanged.

## Ownership and rendering

Each active rendering owner holds one 128 by 88 RGBA32F atlas (176 KiB), keyed
by physical radius. The original 128 by 64 optical-column texels are unchanged;
the remaining rows hold a 48 by 24 diffuse-irradiance tile. Its immutable CPU bake runs through the
host background executor, checks cancellation per row and owns no level,
registry, player or simulation clock. The render thread polls completion without
waiting. Until the table is ready, the shader uses a bounded direct integration
of the same density profiles. Failed work retains that fallback; a table for a
different radius is never sampled.

The appended tile integrates single-scattered sky radiance over the local upper
hemisphere with the cosine measure used by
[Bruneton's indirect-irradiance reference](https://ebruneton.github.io/precomputed_atmospheric_scattering/atmosphere/functions.glsl.html).
It uses eight Gauss-Legendre elevation nodes, 24 azimuth samples and 24 bounded
view-ray intervals, with the same optical columns, phase functions, aerosol
single-scattering albedo and planetary shadow. Altitude and solar-cosine samples
are concentrated near the ground and horizon. The tile excludes the direct
solar beam, ground reflection and higher-order atmospheric scattering; this is
not the complete Bruneton multiple-scattering model. Ozone absorbs light and
is never recycled as a diffuse source.

Cloud diffuse lighting reads this unit-incident-irradiance tile and divides by
pi for its isotropic response before applying the shared stellar source once.
It replaces the previous constant blue sky term. While the table is unready,
diffuse sky contributes zero and direct light retains the bounded optical
fallback. This adds no per-pixel hemispherical integration or texture sampler.

Radius changes, resource reload and owner shutdown retire the pending request
and release the owned texture. Minecraft retains ownership of registered shader
programs. The shader include can be shared by orbital surface lighting, cloud
lighting and foreground attenuation without creating another simulation clock.

The Earth paths share one renderer-relative incident-irradiance convention:
`E = max(0, solarSource) * pi * (1.35 / 1.354)`. Normalized atmospheric and
cloud phase functions consume `E`; the mapped surface uses
`hostResponse * cosine * E / pi` before solar transmission and cloud shadow.
The mapped `hostResponse` inverses the existing display shoulder and is bounded
to 32 per channel. It can exceed one: it preserves Minecraft palette matching
and is explicitly not a measured, energy-bounded physical albedo. A single
relative source gain therefore scales the complete lighting path without
changing that established material calibration. The common `earthMaterialResponse`
function implements this bounded inverse shoulder for both mapped terrain and
cloud particles. Cloud water/ice uses a neutral display-white reference of 0.90,
which decodes to approximately 3.83 in these renderer-relative units. This
response multiplies the cloud scattering coefficients before incident irradiance;
it does not change extinction, phase normalization, exposure or zero-source
behavior. It deliberately matches the host's material convention rather than
claiming an energy-bounded physical cloud albedo. The existing opaque host path
and its terrain palette are unchanged.

The selected Earth surface decodes its mapped color before applying direct
irradiance and solar transmission. Existing observed player emission remains a
separate contribution. Exposure then applies once to the composed celestial
color and bloom; optional automatic exposure does not independently brighten
night terrain or stars. See [Rendering](RENDERING.md) for its controls and scope.

## Verification

`EarthAtmosphereOpticsTest` compares vertical columns with analytic integrals,
random spherical rays with an independent dense Cartesian quadrature, and
orbital rays with the same physical shell-entry geometry. It also checks
planetary shadow, bounded transmission, immutable upload storage and cancellation.
`EarthSkyIrradianceTest` compares the diffuse tile against independently sampled,
convergence-checked hemisphere integration, including overhead, low and horizon
Sun directions, high cloud altitude, deep shadow and the atmosphere boundary.
The native field oracle checks actual GPU diffuse/direct lookups at cloud heights
and zero, quarter, unit and doubled shared sources.

The native `earth-atmosphere-transport` phase records fixed and automatic
exposure at 100 km and full-disc distances, in daylight, both twilight
directions and night. Captures and optical measurements are evidence for those
specific poses; they are not a general performance or shader-pack guarantee.

The `earth-cloud-morphology` phase is an eight-view preview: full-disc clear/cloudy,
100 km daylight clear/cloudy and sunward twilight, plus a daylight extratropical
front centered at latitude 48 degrees and geographic longitude 28 degrees
(body azimuth -28 degrees) from
full-disc distance and an oblique 100 km clear/cloudy pair, all at fixed exposure.
The disposable world's normal tick freeze holds the source clock; exact shader
season, rain, incident source and wind equality is required across captures.
It retains ready
resource checks, frame samples, and a 64-direction GPU/CPU weather-field comparison
with the shared irradiance reference. A separate float32 GPU oracle compares
the three-stratum view intervals against Cartesian occupancy and checks exact
cell budgets, including grazing rays and the clear interior of the shell.
Fourteen fixed rays compare production integration with independent uniform
128/256-cell integration, including paired rays across a previously visible seam.
It omits the full matrix's exposure and
pixel-transfer state probes, resource reload and other-body regressions. A preview pass is not full visual
or lifecycle qualification.

Ground clouds use the same finite-segment air-scattering integral as the orbital
atmosphere. Their light crosses the foreground air column, and composition
restores the foreground scattering hidden by cloud opacity. The clear background
already contains air scattering, so only that occluded share is restored; the
whole sky is not added twice. The native GPU probe compares a vertical segment
against independent analytic optical columns and scattering, including zero,
quarter, unit and doubled incident source strengths.

The `earth-orbital-supernova` verification phase follows the existing
[operator solar diagnostic](SOLAR_SKY.md) from a real Sol flight session.
It enters through the normal orbit action and moves with ordinary flight input
to approximately 400 km and 100 km altitude; the lower view retains a 500 m
margin above the surface-entry boundary. The normal Rocket renderer draws the
Sun and Earth limb together. Each pose runs the real `/astra sun demo 10` cycle
at fixed and automatic exposure, without seeking the server's evolution clock.
Captures cover the healthy star, expansion, collapse, flash, ejecta and remnant.
The fixture records the actual GPU exposure history and shared solar/cloud
source values. Its readbacks are diagnostic evidence, not a performance benchmark.
Visual acceptance still requires inspection of the resulting images.
