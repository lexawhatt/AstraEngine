// Bounded single-scattering approximation, in kilometres. This is an analytic
// optical-depth model with a small view integral, not a weather/fluid simulation.
const float ATMOSPHERE_RADIUS = 6360.0;
const float ATMOSPHERE_HEIGHT = 70.0;
const vec3 RAYLEIGH_EXTINCTION = vec3(0.0058, 0.0135, 0.0331);

float atmosphereAirMass(float cosine, float scaleHeight) {
    // Curvature bounds grazing optical depth; below-horizon paths are handled by
    // the planet-shadow test and the longer tangent segment, never a 1 / 0.
    float curvature = 2.0 * scaleHeight / ATMOSPHERE_RADIUS;
    return 2.0 / max(0.006, cosine + sqrt(cosine * cosine + curvature));
}

vec3 atmosphereTransmission(float sunHeight, float height, float aerosol) {
    float radius = ATMOSPHERE_RADIUS + height;
    float horizonCosine = -sqrt(max(0.0, 1.0 - ATMOSPHERE_RADIUS * ATMOSPHERE_RADIUS / (radius * radius)));
    // Finite solar angular size creates a penumbra at the planetary shadow.
    // A binary tangent test makes higher cloud billows pop into red plateaus.
    float visibility = smoothstep(horizonCosine - 0.00465, horizonCosine + 0.00465, sunHeight);
    if (visibility <= 0.0) { return vec3(0.0); }
    float rayleigh = exp(-height / 8.0) * 8.0 * atmosphereAirMass(sunHeight, 8.0);
    float mie = exp(-height / 1.2) * 1.2 * atmosphereAirMass(sunHeight, 1.2);
    // A broad ozone band removes some green light on long twilight paths.
    float ozone = 0.35 * atmosphereAirMass(max(sunHeight, 0.02), 22.0);
    return visibility * exp(-RAYLEIGH_EXTINCTION * rayleigh - vec3(0.0035 * aerosol * mie)
               - vec3(0.00065, 0.00185, 0.00009) * ozone);
}

vec3 atmosphericSky(vec3 ray, vec3 sun, float aerosol, float observerHeight) {
    vec3 view = normalize(vec3(ray.x, max(ray.y, 0.001), ray.z));
    float heightKm = max(0.002, observerHeight);
    if (heightKm >= ATMOSPHERE_HEIGHT) { return vec3(0.0); }
    vec3 origin = vec3(0.0, ATMOSPHERE_RADIUS + heightKm, 0.0);
    float radial = dot(origin, view);
    float outer = ATMOSPHERE_RADIUS + ATMOSPHERE_HEIGHT;
    float lengthKm = sqrt(radial * radial + outer * outer - dot(origin, origin)) - radial;
    float cosine = dot(view, sun);
    float phaseR = 0.0596831 * (1.0 + cosine * cosine);
    const float g = 0.76;
    float phaseM = 0.119366 * (1.0 - g * g) * (1.0 + cosine * cosine)
                 / ((2.0 + g * g) * pow(max(0.01, 1.0 + g * g - 2.0 * g * cosine), 1.5));
    int samples = Detail > 4 ? 12 : Detail > 3 ? 10 : 8;
    vec3 opticalDepth = vec3(0.0);
    vec3 scattering = vec3(0.0);
    float previousDistance = 0.0;
    for (int i = 0; i < 12; i++) {
        if (i >= samples) { break; }
        float fraction = float(i + 1) / float(samples);
        float distanceKm = lengthKm * fraction * fraction;
        float stepKm = distanceKm - previousDistance;
        vec3 point = origin + view * (previousDistance + stepKm * 0.5);
        float radius = length(point);
        float height = max(radius - ATMOSPHERE_RADIUS, 0.0);
        vec3 rayleigh = RAYLEIGH_EXTINCTION * exp(-height / 8.0);
        vec3 mie = vec3(0.0035 * aerosol * exp(-height / 1.2));
        vec3 extinction = (rayleigh + mie) * stepKm;
        vec3 sunlight = atmosphereTransmission(dot(point, sun) / radius, height, aerosol);
        scattering += exp(-opticalDepth - extinction * 0.5) * sunlight
                    * (rayleigh * phaseR + mie * phaseM) * stepKm;
        opticalDepth += extinction;
        previousDistance = distanceKm;
    }
    float daylight = smoothstep(-0.10, 0.10, sun.y);
    float zenith = pow(max(view.y, 0.0), 0.45);
    // Low-order multiple scattering keeps the anti-solar sky and civil twilight
    // legible; it is deliberately separate from the directional single scatter.
    vec3 multiple = mix(vec3(0.019, 0.032, 0.052), vec3(0.008, 0.026, 0.067), zenith) * daylight;
    float twilight = exp(-pow((sun.y + 0.055) / 0.105, 2.0));
    float horizon = exp(-max(view.y, 0.0) * 8.0);
    float solarSide = pow(max(0.0, dot(normalize(vec3(view.x, 0.001, view.z)),
                                             normalize(vec3(sun.x, 0.001, sun.z)))), 2.0);
    multiple += mix(vec3(0.025, 0.009, 0.047), vec3(0.38, 0.090, 0.012), solarSide)
              * twilight * horizon;
    multiple += vec3(0.006, 0.014, 0.110) * twilight * (0.25 + zenith * 1.5);
    float solarExposure = mix(11.0, 20.0, smoothstep(0.0, 0.42, sun.y));
    return scattering * solarExposure + multiple * exp(-heightKm / 8.0);
}

float cloudHash(vec2 p) {
    vec3 key = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    key += dot(key, key.yzx + 33.33);
    return fract((key.x + key.y) * key.z);
}

float cloudNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(cloudHash(cell), cloudHash(cell + vec2(1, 0)), f.x),
               mix(cloudHash(cell + vec2(0, 1)), cloudHash(cell + vec2(1, 1)), f.x), f.y);
}

float cloudFbm(vec2 p) {
    float sum = 0.0;
    float weight = 0.53;
    for (int i = 0; i < 5; i++) {
        if (i >= Detail) { break; }
        sum += cloudNoise(p) * weight;
        p = mat2(0.8, -0.6, 0.6, 0.8) * p * 2.08 + vec2(17.3, 29.1);
        weight *= 0.47;
    }
    return sum;
}

vec3 cloudLayer(vec3 background, vec3 ray, float coverage, float aerosol, float incidentRadiance,
                float moonlight) {
    if (coverage <= 0.001 || ray.y <= 0.026) { return background; }
    // The projection stops before its shallow-angle clamp. Distant clouds
    // dissolve into atmospheric haze instead of stretching into vertical curtains.
    float horizon = smoothstep(0.026, 0.13, ray.y);
    vec2 wind = CloudWind;
    vec2 planar = ray.xz / (max(ray.y, 0.025) + 0.10);
    vec2 position = planar * 1.72 + CloudOffset + wind;
    float threshold = mix(0.78, 0.23, coverage);
    float field = cloudFbm(position);
    float billows = cloudFbm(position * 4.8 + vec2(5.7));
    field += (billows - 0.48) * 0.28;
    float detailFootprint = max(length(dFdx(position)), length(dFdy(position))) * 24.0;
    float resolvedDetail = 1.0 - smoothstep(0.5, 1.5, detailFootprint);
    float detail = mix(0.5, cloudNoise(position * 24.0 + wind * 0.7), resolvedDetail);
    float thickness = max(0.0, (field + (detail - 0.5) * 0.09 - threshold + 0.015) * 5.8);
    // Feather low-density fringes over a finite optical width. A linear ramp
    // followed immediately by high extinction makes sparse clouds look cut out.
    thickness *= smoothstep(0.0, 0.40, thickness);
    // Do not hard-clamp dense billows: a plateau would erase their height and
    // optical variation exactly where grazing sunset light reveals the relief.
    float density = 1.0 - exp(-thickness * 1.5);
    density = max(0.0, density - (1.0 - billows) * detail * 0.16 * (1.0 - density));
    float opacity = (1.0 - exp(-density * (4.5 + Weather.x * 2.0))) * horizon;
    vec2 sunOffset = SunDirection.xz * 0.35 / max(0.35, SunDirection.y + 0.5);
    // Include billow relief in the light path as well as the visible silhouette.
    // Two cheap detail/coarse samples soften the shadow through nearby cloud
    // without another full fractal pass or an unbounded volume march.
    vec2 aheadPosition = position + sunOffset;
    float aheadField = cloudFbm(aheadPosition)
                     + (cloudNoise(aheadPosition * 4.8 + vec2(5.7)) - 0.48) * 0.28;
    float aheadThickness = max(0.0, (aheadField - threshold) * 5.8);
    float aheadDensity = 1.0 - exp(-aheadThickness * 1.5);
    float distantOcclusion = max(0.0, cloudNoise(position + sunOffset * 1.9) - threshold) * 1.2;
    float litSide = clamp((density - aheadDensity) * 2.0 + 0.5, 0.0, 1.0);
    // Thin low-cloud edges lie deeper in the reddened solar path; thicker
    // billows reach higher while retaining cooler, self-shadowed cores.
    float cloudHeightKm = 0.65 + density * 1.8;
    vec3 sunlight = atmosphereTransmission(SunDirection.y, cloudHeightKm, aerosol) * incidentRadiance;
    float forward = pow(max(dot(ray, SunDirection), 0.0), 12.0);
    float silver = pow(1.0 - density, 2.0) * forward * 2.0;
    float shadow = exp(-density * (2.6 + Weather.x * 1.5) - aheadDensity * 1.35 - distantOcclusion * 0.75);
    float ambientDay = smoothstep(-0.07, 0.35, SunDirection.y);
    vec3 ambient = vec3(0.080, 0.115, 0.170) * ambientDay * incidentRadiance;
    // Starlight is a tiny independent floor. Reflected moonlight has already
    // been scaled by the same stellar incident radiance as direct illumination.
    ambient += vec3(0.00035, 0.00060, 0.0012) * (0.4 + moonlight * 2.4);
    float sunset = exp(-pow((SunDirection.y + 0.012) / 0.12, 2.0));
    ambient += vec3(0.0018, 0.003, 0.011) * sunset * incidentRadiance;
    float powder = 0.35 + 0.65 * (1.0 - exp(-density * 4.0));
    vec3 illumination = sunlight * (0.35 + litSide * 0.95 + silver) * shadow * powder;
    illumination += sunlight * sunset * (0.30 + litSide * 1.2) * shadow * powder * (1.0 - Weather.y * 0.7);
    // Grazing illumination reaches the underside and the optically thin rim.
    // Keep the thick core dark so sunset light does not paint a flat orange mask.
    float grazingLight = sunset * (0.45 + 0.55 * pow(max(dot(ray, SunDirection), 0.0), 2.0));
    vec3 cloud = ambient * (0.40 + shadow * 0.60) + illumination * (2.0 + grazingLight * 3.2);
    // Thin high cloud catches the Sun after the low cloud has entered shadow.
    // It is composited first and can remain pink above a dark lower deck.
    vec2 cirrusPosition = planar * 0.55 + CloudOffset * 0.65 + wind * 0.37 + vec2(47.2, 9.3);
    cirrusPosition = vec2(cirrusPosition.x + cirrusPosition.y * 0.65, cirrusPosition.y * 0.35);
    float filaments = cloudFbm(cirrusPosition * 2.0);
    float cirrus = smoothstep(0.53, 0.79, filaments) * coverage * 0.24 * horizon;
    vec3 highSun = atmosphereTransmission(SunDirection.y, 8.0, aerosol) * incidentRadiance;
    vec3 highCloud = ambient + highSun * (0.42 + sunset * 0.90);
    background = mix(background, highCloud, cirrus);
    return mix(background, cloud, opacity);
}
