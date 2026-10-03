// Bounded spherical single-scattering approximation, in kilometers. The same
// shell is viewed from orbit and a planet-fixed surface; no second sky clock.
vec2 planetAtmosphereInterval(vec3 origin, vec3 ray, float radius) {
    float along = dot(origin, ray);
    vec3 closest = origin - ray * along;
    float discriminant = radius * radius - dot(closest, closest);
    if (discriminant < 0.0) { return vec2(1.0, -1.0); }
    float root = sqrt(max(discriminant, 0.0));
    return vec2(-along - root, -along + root);
}

vec3 marsPlanetaryAtmosphere(vec3 background, vec3 ray, vec4 observer, vec3 sunlight,
                        float irradiance, float skyCoverage, float sunRadius, float surfaceDistanceKm, float atmosphereModel) {
    bool mars = true;
    float shellHeight = mars ? 60.0 : 80.0;
    float radius = observer.w;
    vec3 origin = observer.xyz;
    float observerHeight = length(origin) - radius;
    // Rounded voxel floors and negative elevations can sit just below the nominal sea-level sphere.
    if (observerHeight < 0.002) { origin = safeUnit(origin, vec3(0, 1, 0)) * (radius + 0.002); }
    vec2 outer = planetAtmosphereInterval(origin, ray, radius + shellHeight);
    float start = max(0.0, outer.x);
    float finish = outer.y;
    vec2 ground = planetAtmosphereInterval(origin, ray, radius);
    if (ground.x > 0.0 && ground.y > ground.x) { finish = min(finish, ground.x); }
    // Relief can end the visible air column kilometers above the reference sea sphere.
    // Integrating behind opaque mountain faces would brighten them with nonexistent foreground haze.
    if (surfaceDistanceKm > 0.0) { finish = min(finish, surfaceDistanceKm); }
    if (finish <= start) { return background; }
    // The artistic sky gain is brighter than opaque-surface reflection. Limit
    // that aerial veil over ground while preserving the sky and grazing limb;
    // this is display calibration, not an additional physical scattering term.
    float surfaceFacing = max(0.0, -dot(ray, normalize(origin + ray * finish)));
    float aerialGain = 1.0 - (1.0 - skyCoverage) * smoothstep(0.0, 0.45, surfaceFacing) * 0.70;
    int steps = Detail >= 5 ? 10 : Detail >= 4 ? 8 : 6;
    // Mars keeps weak molecular scattering and a warm dust component; Earth coefficients remain unchanged.
    vec3 betaRayleigh = vec3(0.0058, 0.0135, 0.0331) * (mars ? 0.008 : 1.0);
    vec3 betaMie = mars ? vec3(0.018, 0.010, 0.0045) : vec3(0.0032);
    vec3 betaOzone = mars ? vec3(0.0) : vec3(0.00065, 0.00188, 0.00008);
    vec2 scaleHeight = mars ? vec2(10.8) : vec2(8.0, 1.2);
    float mu = dot(ray, sunlight);
    float rayleighPhase = 0.0596831 * (1.0 + mu * mu);
    float miePhase = 0.015 * (1.0 - 0.76 * 0.76)
            / pow(max(0.05, 1.0 + 0.76 * 0.76 - 1.52 * mu), 1.5);
    vec3 transmission = vec3(1.0);
    vec3 scattering = vec3(0.0);
    for (int i = 0; i < 10; i++) {
        if (i >= steps) { break; }
        float a = float(i) / float(steps);
        float b = float(i + 1) / float(steps);
        // Resolve the dense first kilometers when the observer is inside the shell.
        if (observerHeight < shellHeight) { a *= a; b *= b; }
        float stepKm = (finish - start) * (b - a);
        vec3 position = origin + ray * (start + (a + b) * 0.5 * (finish - start));
        float height = max(0.0, length(position) - radius);
        vec2 density = exp(-height / scaleHeight);
        float ozone = max(0.0, 1.0 - abs(height - 25.0) / 15.0);
        vec3 extinction = betaRayleigh * density.x + betaMie * density.y + betaOzone * ozone;
        vec3 stepTransmission = exp(-extinction * stepKm);
        float sunHeight = dot(normalize(position), sunlight);
        float horizon = -sqrt(max(0.0, 1.0 - radius * radius / dot(position, position)));
        float solarEdge = max(0.0001, sunRadius);
        float lit = smoothstep(horizon - solarEdge, horizon + solarEdge, sunHeight);
        // Curvature regularizes the horizon optical path; sunset extinction remains spectral.
        float airMass = 2.0 / max(0.015, sunHeight + sqrt(sunHeight * sunHeight + 0.003));
        float ozoneColumn = 15.0 * (1.0 - smoothstep(10.0, 40.0, height));
        vec3 sunTransmission = exp(-(betaRayleigh * density.x * scaleHeight.x + betaMie * density.y * scaleHeight.y
                + betaOzone * ozoneColumn) * airMass);
        float dustPhase = mars ? rayleighPhase * 0.55 + miePhase * 0.12 : miePhase;
        vec3 source = (betaRayleigh * density.x * rayleighPhase + betaMie * density.y * dustPhase)
                * sunTransmission * lit * max(0.0, irradiance) * (18.0 * aerialGain);
        // Dust's blue forward-scattering twilight lobe is confined to the solar direction, not the entire limb.
        if (mars) {
            float blueTwilight = (1.0 - smoothstep(0.015, 0.13, abs(sunHeight))) * smoothstep(0.92, 0.998, mu);
            source += vec3(0.001, 0.003, 0.007) * density.y * blueTwilight * lit
                    * max(0.0, irradiance) * aerialGain;
        }
        scattering += transmission * source * (vec3(1.0) - stepTransmission) / max(extinction, vec3(1e-7));
        transmission *= stepTransmission;
    }
    // Daylight contrast hides dim celestial points without shrinking the actual solar disc.
    float skyBrightness = dot(scattering, vec3(0.2126, 0.7152, 0.0722));
    float visibility = 1.0 / (1.0 + skyBrightness * 450.0);
    float solarDisc = mu > 0.0 ? 1.0 - smoothstep(sunRadius * 1.1, sunRadius * 1.8,
            length(ray - sunlight * mu)) : 0.0;
    return background * transmission * mix(1.0, visibility, skyCoverage * (1.0 - solarDisc)) + scattering;
}

#moj_import <astraengine:earth_optics.glsl>

vec3 planetaryAtmosphere(vec3 background, vec3 ray, vec4 observer, vec3 sunlight,
                        float irradiance, float skyCoverage, float sunRadius, float surfaceDistanceKm, float atmosphereModel) {
    if (atmosphereModel > 0.5) {
        return marsPlanetaryAtmosphere(background, ray, observer, sunlight, irradiance,
                skyCoverage, sunRadius, surfaceDistanceKm, atmosphereModel);
    }
    vec3 transmission;
    int steps = Detail >= 5 ? 16 : Detail >= 4 ? 12 : 8;
    float limitKm = surfaceDistanceKm > 0.0 ? surfaceDistanceKm : 1e20;
    vec3 scattering = earthAirScattering(observer.xyz, ray, observer.w, sunlight,
            irradiance, sunRadius, limitKm, steps, transmission);
    // Stellar background is attenuated by the actual air column. Camera exposure,
    // rather than a second per-object visibility curve, determines visible stars.
    return background * transmission + scattering;
}
