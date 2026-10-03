#version 150

uniform mat4 InverseViewProjection;
uniform int HdrOutput;
uniform vec2 ScreenSize;
uniform float Time;
uniform float Seed;
uniform int GalaxyCount;
uniform int RegionCount;
uniform int CatalogStarCount;
uniform vec4 CatalogStarDirection[24];
uniform vec3 CatalogStarColor[24];
uniform vec4 GalaxyObserver[9];
uniform vec4 GalaxyShape[9];
uniform vec4 GalaxyStructure[9];
uniform vec3 GalaxyAxisX[9];
uniform vec3 GalaxyAxisY[9];
uniform vec3 GalaxyAxisZ[9];
uniform vec4 RegionObserver[24];
uniform vec4 RegionColor[24];
uniform vec4 RegionStructure[24];
uniform float GalaxySeed;
uniform float Exposure;
uniform int Detail;
uniform vec3 ReliefBudget;
uniform int Supernova;
uniform int BodyCount;
uniform int EvolutionIndex;
uniform int LensIndex;
uniform int NucleusBodyIndex;
uniform vec3 NucleusAxis;
uniform float BodyDistanceRatio[12];
uniform vec4 Evolution;
uniform vec4 SolarLight;
uniform vec4 BodyDirectionRadius[12];
uniform vec4 BodyColorKind[12];
uniform vec4 BodySurface[12];
uniform vec4 BodyLightTilt[12];
uniform float BodySpin[12];
uniform int CalendarEarth;
uniform mat3 EarthInverseRotation;
uniform vec4 BodyGeography[12];
uniform int BodyGeographySeed[12];
uniform int AtmosphereBodyIndex;
uniform float BodyAtmosphereModel[12];
uniform vec4 AtmosphereObserver;
uniform vec4 SurfaceHorizon;
uniform vec3 SurfaceFog;
uniform vec2 CloudWind;

in vec2 clipPosition;
out vec4 fragColor;
const float PI = 3.14159265359;

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

float noise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(cell), hash31(cell + vec3(1, 0, 0)), f.x),
                   mix(hash31(cell + vec3(0, 1, 0)), hash31(cell + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash31(cell + vec3(0, 0, 1)), hash31(cell + vec3(1, 0, 1)), f.x),
                   mix(hash31(cell + vec3(0, 1, 1)), hash31(cell + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float value = 0.0;
    float weight = 0.52;
    for (int i = 0; i < 5; i++) {
        if (i >= Detail) { break; }
        value += noise(p) * weight;
        p = mat3(0.0, 0.8, 0.6, -0.8, 0.36, -0.48, -0.6, -0.48, 0.64)
          * p * 2.07 + vec3(13.1, 7.7, 19.2);
        weight *= 0.48;
    }
    return value;
}

#moj_import <astraengine:solar.glsl>
#moj_import <astraengine:celestial_display.glsl>
#moj_import <astraengine:black_hole.glsl>
#moj_import <astraengine:planet_atmosphere.glsl>
#moj_import <astraengine:surface_geography.glsl>
#moj_import <astraengine:continental_surface.glsl>
#moj_import <astraengine:solid_planet_surface.glsl>
#moj_import <astraengine:orbital_edits.glsl>
#moj_import <astraengine:surface_height_cache.glsl>
#moj_import <astraengine:lunar.glsl>
#moj_import <astraengine:pulsar.glsl>
#define CLOUD_NOISE_EXTERNAL_SAMPLER
#define CloudNoise EarthHeightTile0
#moj_import <astraengine:cloud_density.glsl>
#moj_import <astraengine:earth_clouds.glsl>
#undef CloudNoise
#undef CLOUD_NOISE_EXTERNAL_SAMPLER

// Face projection avoids the pole stretching of latitude/longitude star textures.
vec3 starCoordinates(vec3 ray) {
    vec3 a = abs(ray);
    if (a.x > a.y && a.x > a.z) { return vec3(ray.yz / a.x, ray.x > 0.0 ? 0.0 : 1.0); }
    if (a.y > a.z) { return vec3(ray.xz / a.y, ray.y > 0.0 ? 2.0 : 3.0); }
    return vec3(ray.xy / a.z, ray.z > 0.0 ? 4.0 : 5.0);
}

vec3 stars(vec3 ray, float scale, float density, float gain) {
    vec3 coordinates = starCoordinates(ray);
    vec2 grid = coordinates.xy * scale;
    vec2 cell = floor(grid);
    vec2 pixel = max(fwidth(grid), vec2(0.003));
    vec3 random = hash33(vec3(cell, coordinates.z * 271.0 + GalaxySeed));
    if (random.z > density) { return vec3(0); }
    vec2 delta = fract(grid) - mix(vec2(0.18), vec2(0.82), random.xy);
    // Optical point spread is independent of a celestial body's geometric radius.
    float radius = 0.02 + 0.07 * random.y * random.y;
    float width = max(radius, max(pixel.x, pixel.y) * 0.7);
    float star = exp(-dot(delta, delta) / (width * width));
    star *= min(1.0, radius * radius / (width * width));
    float cross = exp(-abs(delta.x) / (width * 0.2) - abs(delta.y) / (width * 3.5))
                + exp(-abs(delta.y) / (width * 0.2) - abs(delta.x) / (width * 3.5));
    star += cross * 0.06 * step(0.985, random.y);
    vec3 tint = mix(vec3(0.54, 0.72, 1.0), vec3(1.0, 0.71, 0.43), random.x);
    return tint * star * gain * (0.4 + random.y * 2.2);
}

#moj_import <astraengine:galaxy.glsl>

vec3 universe(vec3 ray, float pixelAngle) {
    return galacticSky(ray, pixelAngle);
}

vec3 surfaceCoordinates(vec3 n, float tilt, float spin) {
    float c = cos(tilt);
    float s = sin(tilt);
    n = vec3(n.x, c * n.y + s * n.z, -s * n.y + c * n.z);
    c = cos(spin);
    s = sin(spin);
    return vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z);
}

// Body-fixed albedo detail only: these frequencies never displace the surface,
// change sea level, or substitute an independent continental map for the CPU geography.
vec3 mappedEarthAlbedo(vec3 p, float height, uint seed, float footprint, float water) {
    float broad = geographyNoise(p * 9.0, seed ^ 0x1D7Au);
    float detail = 0.0;
    float frequency = 37.0;
    float weight = 0.58;
    for (int octave = 0; octave < 3; octave++) {
        float resolved = 1.0 - smoothstep(0.18, 0.75, footprint * frequency);
        if (resolved > 0.001) {
            detail += (geographyNoise(p * frequency, seed ^ (0xA219u + uint(octave) * 0x41u)) - 0.5)
                    * weight * resolved;
        }
        frequency *= 3.05;
        weight *= 0.52;
    }
    float variation = clamp(broad + detail * 0.85, 0.0, 1.0);
    // Sand, vegetation and rock retain the pinned elevation ordering. Smooth
    // optical transitions avoid drawn contour bands without moving the shoreline.
    vec3 sand = mix(vec3(0.32, 0.25, 0.13), vec3(0.53, 0.44, 0.27), variation);
    vec3 grass = mix(vec3(0.025, 0.072, 0.023), vec3(0.12, 0.21, 0.055), variation);
    vec3 rock = mix(vec3(0.075, 0.09, 0.09), vec3(0.36, 0.29, 0.20), variation);
    rock *= 0.92 + detail * 0.8;
    vec3 land = mix(sand, grass, smoothstep(2.0, 4.0, height));
    land = mix(land, rock, smoothstep(62.0, 74.0, height));
    // Resolve fixed physical material scales progressively. This is reflectance,
    // not a second height field: closer views never move mountains or coastlines.
    float materialDetail = 0.0;
    float meterScale = 2048.0;
    float materialWeight = 0.18;
    for (int octave = 0; octave < 5; octave++) {
        float frequencyMeters = 6371000.0 / meterScale;
        float resolved = 1.0 - smoothstep(0.18, 0.70, footprint * frequencyMeters);
        if (resolved > 0.001) {
            materialDetail += (geographyNoise(p * frequencyMeters, seed ^ (0x5391u + uint(octave))) - 0.5)
                    * materialWeight * resolved;
        }
        meterScale *= 0.25;
        materialWeight *= 0.72;
    }
    land *= 1.0 + materialDetail;
    float polarWidth = max(0.00015, footprint * 0.65);
    float ice = smoothstep(0.82 - polarWidth, 0.82 + polarWidth, abs(p.y));
    vec3 snow = mix(vec3(0.48, 0.58, 0.65), vec3(0.84, 0.89, 0.91), variation);
    land = mix(land, snow, ice);
    float shelf = smoothstep(-22.0, -1.0, height);
    vec3 ocean = mix(vec3(0.003, 0.016, 0.036), vec3(0.011, 0.071, 0.078), shelf);
    ocean *= 0.96 + detail * 0.15;
    return mix(land, ocean, water);
}

vec3 mappedEarthNormal(vec3 p, uint seed, float footprint, float radiusMeters) {
    if (footprint > (ContinentalEarth != 0 ? 0.006 : 0.00015)) { return p; }
    vec3 tangent = normalize(cross(p, abs(p.y) < 0.9 ? vec3(0, 1, 0) : vec3(1, 0, 0)));
    vec3 bitangent = cross(p, tangent);
    float stepAngle = max(2.0 / radiusMeters, footprint * 1.25);
    float x0 = max(0.0, earthHeight(normalize(p - tangent * stepAngle), seed, footprint));
    float x1 = max(0.0, earthHeight(normalize(p + tangent * stepAngle), seed, footprint));
    float y0 = max(0.0, earthHeight(normalize(p - bitangent * stepAngle), seed, footprint));
    float y1 = max(0.0, earthHeight(normalize(p + bitangent * stepAngle), seed, footprint));
    vec2 slope = vec2(x1 - x0, y1 - y0) / (2.0 * stepAngle * radiusMeters);
    return normalize(p - tangent * slope.x - bitangent * slope.y);
}

vec3 mappedSurfaceCoordinates(vec3 direction, float tilt, float spin, vec4 geography) {
    return CalendarEarth != 0 && geography.x > 1.5 && geography.x < 2.5 ? EarthInverseRotation * direction
            : surfaceCoordinates(direction, tilt, spin);
}

vec3 planetSurface(int bodyIndex, vec3 n, vec3 viewRay, vec3 light, vec4 material, vec4 parameters,
                   float tilt, float spin, vec4 geography, uint geographySeed, float normalFootprint) {
    int kind = int(material.w + 0.5);
    vec3 p = mappedSurfaceCoordinates(n, tilt, spin, geography);
    vec3 seed = vec3(parameters.x * 0.071, parameters.x * 0.027, 3.7);
    float diffuse = max(dot(n, light), 0.0);
    float day = smoothstep(-0.075, 0.15, dot(n, light));
    float terrain = geography.x > 0.5 ? 0.5 : fbm(p * 3.5 + seed);
    vec3 albedo = material.rgb;
    float water = 0.0;
    float clouds = 0.0;
    float terrainAltitude = 0.0;
    bool solidMapped = geography.x > 2.5 && PlanetSlots[bodyIndex] >= 0;
    bool hostSurface = solidMapped || ContinentalEarth != 0 && geography.x > 1.5 && geography.x < 2.5;
    if (solidMapped) {
        vec4 sampleValue = planetTerrainSample(bodyIndex, p, geography.z);
        terrainAltitude = sampleValue.x;
        albedo = sampleValue.yzw;
        water = kind == 3 && sampleValue.x < 0.0 ? 1.0 : 0.0;
        vec3 fixedLight = mappedSurfaceCoordinates(light, tilt, spin, geography);
        vec3 reliefNormal = water > 0.5 ? p : planetTerrainNormal(bodyIndex, p, geography.z, normalFootprint);
        diffuse = max(dot(reliefNormal, fixedLight), 0.0) * smoothstep(-0.006, 0.025, dot(p, fixedLight));
    } else if (geography.x > 0.5 && geography.x < 2.5) {
        int surfaceKind = int(geography.x + 0.5);
        float height = surfaceKind == 2 ? earthHeight(p, geographySeed, normalFootprint)
                : geographyHeight(p, surfaceKind, geographySeed, normalFootprint);
        terrainAltitude = height;
        float fineWeight = 1.0 - smoothstep(0.15, 0.8, normalFootprint * geography.z / 16.0);
        float grain = fineWeight > 0.001 ? noise(p * (geography.z / 16.0)) : 0.5;
        if (surfaceKind == 1) {
            return lunarRadiance(p, mappedSurfaceCoordinates(light, tilt, spin, geography),
                    mappedSurfaceCoordinates(-viewRay, tilt, spin, geography), height, geographySeed,
                    normalFootprint, geography.z, true);
        } else {
            water = 1.0 - smoothstep(-0.3, 0.4, height);
            if (ContinentalEarth != 0) {
                vec4 surface = continentalMaterial(p);
                albedo = surface.rgb;
                water = surface.a;
            } else {
                albedo = mappedEarthAlbedo(p, height, geographySeed, normalFootprint, water);
            }
            vec3 reliefNormal = mappedEarthNormal(p, geographySeed, normalFootprint, geography.z);
            vec3 fixedLight = mappedSurfaceCoordinates(light, tilt, spin, geography);
            diffuse = max(dot(reliefNormal, fixedLight), 0.0)
                    * (bodyIndex == AtmosphereBodyIndex && BodyAtmosphereModel[bodyIndex] < 0.5
                    ? 1.0 : smoothstep(-0.006, 0.025, dot(p, fixedLight)));
            // Local terrain/cloud ownership changes at handoff; the geographic map itself never scrolls.
            clouds = 0.0;
        }
        albedo *= 1.0 + (grain - 0.5) * fineWeight * 0.28;
    } else if (kind == 3) {
        float continents = fbm(p * 2.7 + seed);
        float coast = smoothstep(0.48, 0.515, continents);
        float mountains = smoothstep(0.58, 0.73, continents);
        vec3 ocean = mix(vec3(0.004, 0.023, 0.075), vec3(0.012, 0.11, 0.22), terrain);
        vec3 land = mix(vec3(0.036, 0.10, 0.026), vec3(0.28, 0.22, 0.09),
                        smoothstep(0.34, 0.65, terrain + abs(p.y) * 0.09));
        land = mix(land, vec3(0.42, 0.39, 0.33), mountains);
        albedo = mix(ocean, land, coast);
        float ice = smoothstep(0.80, 0.95, abs(p.y) + terrain * 0.065);
        albedo = mix(albedo, vec3(0.83, 0.91, 0.96), ice);
        water = (1.0 - coast) * (1.0 - ice);
        clouds = smoothstep(0.50, 0.69, fbm(p * 8.0 + seed + vec3(19.1)));
        albedo = mix(albedo, vec3(0.9, 0.94, 1.0), clouds * 0.88);
    } else if (kind == 4) {
        float turbulence = fbm(p * vec3(7, 3, 7) + seed);
        float bands = sin(p.y * 38.0 + turbulence * 4.0);
        float wisps = sin(p.y * 125.0 + turbulence * 13.0);
        albedo *= 0.63 + 0.15 * bands + 0.22 * turbulence + wisps * 0.035;
        albedo = mix(albedo, vec3(0.82, 0.73, 0.56), smoothstep(0.3, 0.85, bands) * 0.32);
        vec2 stormCoordinates = vec2(atan(p.z, p.x) - 0.9, (p.y + 0.22) * 3.0);
        float storm = length(stormCoordinates);
        float spiral = sin(storm * 130.0 - atan(stormCoordinates.y, stormCoordinates.x) * 4.0);
        albedo = mix(albedo, material.rgb * vec3(1.1, 0.39, 0.2) * (0.75 + spiral * 0.2),
                     1.0 - smoothstep(0.15, 0.31, storm));
    } else if (kind == 5) {
        float fissure = abs(fbm(p * 14.0 + seed) - 0.48);
        albedo *= 0.63 + terrain * 0.8;
        albedo = mix(albedo * 0.32, albedo, smoothstep(0.006, 0.035, fissure));
        albedo += pow(max(0.0, dot(reflect(-light, n), -viewRay)), 28.0) * 0.35;
    } else {
        float detail = noise(p * 110.0 + seed);
        float craters = noise(p * 38.0 + seed);
        float rim = smoothstep(0.58, 0.67, craters) - smoothstep(0.67, 0.74, craters);
        albedo *= 0.38 + terrain * 0.91 + detail * 0.16 + rim * 0.12;
    }
    vec4 observedSurface;
    vec2 observedLight;
    if (orbitalLookup(bodyIndex, p, geography.z, normalFootprint * geography.z, observedSurface, observedLight)) {
        albedo = mix(albedo, observedSurface.rgb, observedLight.y);
        hostSurface = true;
    }
    if (BodyAtmosphereModel[bodyIndex] > 0.5) {
        // Match the host Mars dust-light ratios before display decoding. Actual material observations remain
        // canonical; this illumination also warms historical v1 gray substrate without replacing its blocks.
        albedo *= mix(vec3(1.0), vec3(1.0, 0.825, 0.665), exp(-max(0.0, terrainAltitude) / 10800.0));
    }
    vec3 color = albedo * (0.004 + diffuse * 1.35);
    vec3 directTransmission = vec3(1.0);
    bool canonicalClouds = ContinentalEarth != 0 && geography.x > 1.5 && geography.x < 2.5 && CloudPlanet.w > 0.0;
    float cloudShadow = canonicalClouds ? earthCloudShadowFiltered(p * ((geography.z + max(0.0, terrainAltitude)) * 0.001),
            mappedSurfaceCoordinates(light, tilt, spin, geography), 6, normalFootprint * geography.z * 0.001) : 1.0;
    if (hostSurface && bodyIndex == AtmosphereBodyIndex && BodyAtmosphereModel[bodyIndex] < 0.5) {
        // Decode the canonical host palette once, before linear lighting/transport.
        // This bounded display calibration preserves the host's daylight material scale;
        // it is renderer-relative radiance, not an SI reflectance measurement.
        vec3 hostResponse = earthMaterialResponse(albedo);
        float sunRadius = EvolutionIndex >= 0 ? BodyDirectionRadius[EvolutionIndex].w : 0.00465;
        directTransmission = earthSunTransmission(n * ((geography.z + max(0.0, terrainAltitude)) * 0.001),
                geography.z * 0.001, light, sunRadius);
        color = hostResponse * diffuse * (earthIncidentIrradiance(1.0) / EARTH_PI) * directTransmission * cloudShadow;
    } else if (hostSurface) {
        // Shade the host display albedo before undoing the display shoulder. Decoding bright snow
        // first would amplify the night ambient term and make ice appear self-luminous.
        color = celestialRadiance(albedo * pow((0.004 + diffuse * cloudShadow * 1.35) / 1.354, 1.0 / 2.2));
    }
    if (kind == 3) {
        float specular = pow(max(0.0, dot(reflect(-light, n), -viewRay)), 90.0);
        vec3 glintLight = bodyIndex == AtmosphereBodyIndex && BodyAtmosphereModel[bodyIndex] < 0.5
                ? directTransmission : vec3(1.0, 0.82, 0.59);
        color += glintLight * specular * water * (1.0 - clouds) * day * cloudShadow;
    }
    float rim = pow(1.0 - max(dot(n, -viewRay), 0.0), 3.0);
    vec3 atmosphere = mix(vec3(1.0, 0.20, 0.025), vec3(0.12, 0.42, 1.0), day);
    color += atmosphere * parameters.y * rim * (0.08 + day * 0.5) * (geography.x > 0.5 ? 0.0 : 1.0);
    return color;
}

// A normalized center is always length one. Compute perpendicular displacement
// directly; 1-dot(ray,center)^2 loses tiny physical angular radii to cancellation.
float sphereHit(vec3 ray, vec3 center, float radius, out vec3 normal) {
    float along = dot(ray, center);
    vec3 perpendicular = center - ray * along;
    float discriminant = radius * radius - dot(perpendicular, perpendicular);
    if (discriminant <= 0.0 || (along <= 0.0 && radius <= 1.0)) { return -1.0; }
    float root = sqrt(discriminant);
    float distance = along - root;
    float side = -1.0;
    if (distance <= 0.0) { distance = along + root; side = 1.0; }
    if (distance <= 0.0) { return -1.0; }
    normal = safeUnit(-perpendicular + ray * root * side, -ray);
    return distance;
}

// Stable ray interval through a physical height shell. The CPU supplies altitude
// separately: subtracting two float Earth radii would destroy human-scale precision.
vec2 reliefShell(float alongMeters, float altitude, float radiusMeters, float height) {
    float c = (altitude - height) * (2.0 * radiusMeters + altitude + height);
    float discriminant = alongMeters * alongMeters - c;
    if (discriminant < 0.0) { return vec2(1.0, -1.0); }
    float root = sqrt(discriminant);
    float q = -alongMeters - (alongMeters >= 0.0 ? root : -root);
    if (abs(q) < 1e-7) { return vec2(0.0); }
    return vec2(min(q, c / q), max(q, c / q));
}

float reliefResidual(int bodyIndex, vec3 up, vec3 ray, float distanceMeters, float altitude, float radiusMeters,
                     float alongMeters, float travel, uint seed, float pixelAngle, out vec3 p) {
    vec3 point = up * distanceMeters + ray * travel;
    float lengthMeters = length(point);
    p = point / max(lengthMeters, 1.0);
    float height = (altitude * (2.0 * radiusMeters + altitude)
            + travel * (2.0 * alongMeters + travel)) / (lengthMeters + radiusMeters);
    float footprint = pixelAngle * max(travel, 0.01)
            / (radiusMeters * max(0.025, abs(dot(p, ray))));
    // Sea occludes the negative seabed; this does not fill excavated host blocks.
    bool solid = BodyGeography[bodyIndex].x > 2.5;
    float displacement = solid || ContinentalEarth != 0 ? 1.0 - smoothstep(450000.0, 500000.0, altitude)
            : 1.0 - smoothstep(80000.0, 100000.0, altitude);
    float baseHeight = solid ? planetTerrainSample(bodyIndex, p, radiusMeters).x : earthHeight(p, seed, footprint);
    bool liquidBody = !solid || int(BodyColorKind[bodyIndex].w + 0.5) == 3;
    float visibleHeight = liquidBody ? max(0.0, baseHeight) : baseHeight;
    vec4 observedSurface;
    vec2 observedLight;
    if (orbitalLookup(bodyIndex, p, radiusMeters, footprint * radiusMeters, observedSurface, observedLight)) {
        visibleHeight = mix(visibleHeight, observedSurface.a, observedLight.y);
    }
    return height - visibleHeight * displacement;
}

// Bounded spherical parallax-occlusion march through the canonical Earth height
// field, followed by a bracketed refinement. Camera motion changes occlusion and
// the visible sample rather than sliding a texture on the nominal sphere.
float mappedEarthHit(int bodyIndex, vec3 ray, vec3 center, float nominalHit, vec4 geography, uint seed,
                     float tilt, float spin, float pixelAngle, inout vec3 normal) {
    bool solid = geography.x > 2.5;
    bool largeRelief = solid || ContinentalEarth != 0;
    if (solid && PlanetSlots[bodyIndex] < 0) { return nominalHit; }
    float radiusMeters = geography.z;
    float altitude = geography.y;
    float distanceMeters = radiusMeters + altitude;
    if (geography.w < 0.5 || altitude > (largeRelief ? 500000.0 : 100000.0)
            || altitude < (largeRelief ? -7000.0 : -48.0) || distanceMeters <= 0.0) {
        return nominalHit;
    }
    vec3 up = mappedSurfaceCoordinates(-center, tilt, spin, geography);
    vec3 fixedRay = mappedSurfaceCoordinates(ray, tilt, spin, geography);
    float along = distanceMeters * dot(up, fixedRay);
    vec2 outer = reliefShell(along, altitude, radiusMeters, largeRelief ? 10000.0 : 112.0);
    if (outer.y <= 0.0 || outer.y < outer.x) { return -1.0; }
    float start = max(0.0, outer.x);
    float finish = outer.y;
    vec2 inner = reliefShell(along, altitude, radiusMeters, solid && int(BodyColorKind[bodyIndex].w + 0.5) != 3 ? -7000.0 : 0.0);
    if (inner.x > start && inner.y >= inner.x) {
        finish = min(finish, inner.x);
        nominalHit = inner.x / distanceMeters;
        normal = normalize(-center + ray * nominalHit);
    }
    // The displacement is subpixel in broad orbital views. Smooth material
    // filtering still runs; skip the costly march where it cannot be resolved.
    if (pixelAngle * max(start, 1.0) > (largeRelief ? 3000.0 : 300.0)) { return nominalHit; }
    int steps = int(ReliefBudget.x) * (largeRelief ? 4 : 1);
    vec3 p;
    float previous = start;
    float startResidual = reliefResidual(bodyIndex, up, fixedRay, distanceMeters, altitude, radiusMeters,
            along, start, seed, pixelAngle, p);
    if (startResidual <= 0.0) {
        normal = -center;
        return max(start, 0.01) / distanceMeters;
    }
    // Version-one Earth has less than 0.17 m/m maximum geometric slope.
    // Steep descending rays have one crossing, so refine the enclosing bracket
    // directly instead of marching all its empty samples. Grazing views still march.
    if (!largeRelief && dot(up, fixedRay) < -0.3 && inner.x > start && inner.y >= inner.x) {
        float low = start;
        float high = finish;
        for (int refine = 0; refine < int(ReliefBudget.y); refine++) {
            float mid = (low + high) * 0.5;
            float error = reliefResidual(bodyIndex, up, fixedRay, distanceMeters, altitude, radiusMeters,
                    along, mid, seed, pixelAngle, p);
            if (error > 0.0) { low = mid; } else { high = mid; }
        }
        float travel = (low + high) * 0.5;
        normal = normalize(-center + ray * (travel / distanceMeters));
        return travel / distanceMeters;
    }
    for (int stepIndex = 1; stepIndex <= steps; stepIndex++) {
        // Concentrate samples near the observer inside the shell. Uniform steps
        // can otherwise skip all nearby hills along a long grazing interval.
        float fraction = float(stepIndex) / float(steps);
        float travel = mix(start, finish, start < 1.0 ? fraction * fraction : fraction);
        float residual = reliefResidual(bodyIndex, up, fixedRay, distanceMeters, altitude, radiusMeters,
                along, travel, seed, pixelAngle, p);
        if (residual <= 0.0) {
            float low = previous;
            float high = travel;
            for (int refine = 0; refine < int(ReliefBudget.z) + (largeRelief ? 5 : 0); refine++) {
                float mid = (low + high) * 0.5;
                float error = reliefResidual(bodyIndex, up, fixedRay, distanceMeters, altitude, radiusMeters,
                        along, mid, seed, pixelAngle, p);
                if (error > 0.0) { low = mid; } else { high = mid; }
            }
            travel = (low + high) * 0.5;
            normal = normalize(-center + ray * (travel / distanceMeters));
            return travel / distanceMeters;
        }
        previous = travel;
    }
    // At a grazing ray a bounded march can miss very narrow peaks. Retain only
    // the nominal sea hit, never an inflated enclosing shell as an opaque surface.
    return nominalHit;
}

vec4 ringSurface(vec3 ray, vec3 center, float radius, vec3 normal, float inner, float outer,
                 vec3 light, vec3 tint, float seed, float pixelAngle, out float distance) {
    distance = -1.0;
    float denominator = dot(ray, normal);
    if (abs(denominator) < 0.00001 || outer <= inner) { return vec4(0); }
    float hit = dot(center, normal) / denominator;
    if (hit <= 0.0) { return vec4(0); }
    vec3 local = (ray * hit - center) / radius;
    float r = length(local);
    // Explicit angular footprint is valid even inside divergent intersection branches.
    float radialFootprint = pixelAngle / max(radius * max(abs(denominator), 0.04), 1e-12);
    float edge = clamp(radialFootprint, 0.005, 1.0);
    float coverage = smoothstep(inner, inner + edge, r) * (1.0 - smoothstep(outer - edge, outer, r));
    if (coverage <= 0.0) { return vec4(0); }
    float bands = 0.67 + 0.11 * sin(r * 181.0 + seed) / (1.0 + radialFootprint * 181.0)
                + 0.08 * sin(r * 517.0) / (1.0 + radialFootprint * 517.0);
    float gap = 1.0 - (smoothstep(2.03, 2.045, r) - smoothstep(2.10, 2.12, r)) * 0.82;
    float towardSun = dot(local, light);
    vec3 closest = local + light * max(0.0, -towardSun);
    float shadow = towardSun < 0.0 ? smoothstep(0.97, 1.04, length(closest)) : 1.0;
    vec3 color = tint * bands * (0.10 + abs(dot(normal, light)) * 0.8) * mix(0.065, 1.0, shadow);
    float alpha = coverage * gap * 0.87;
    distance = hit;
    return vec4(color, alpha);
}

vec3 body(vec3 color, vec3 ray, int index, float pixelAngle, inout float bloomWeight) {
    vec4 descriptor = BodyDirectionRadius[index];
    vec3 center = descriptor.xyz;
    float radius = descriptor.w;
    vec4 material = BodyColorKind[index];
    vec4 parameters = BodySurface[index];
    vec3 light = BodyLightTilt[index].xyz;
    float tilt = BodyLightTilt[index].w;
    int kind = int(material.w + 0.5);
    float along = dot(ray, center);
    if (kind == 6) {
        return pulsarRadiance(color, ray, center, radius, material.rgb, tilt,
                              BodySpin[index], parameters.x, pixelAngle);
    }
    bool insideRelief = BodyGeography[index].w > 0.5 && BodyGeography[index].y <= (ContinentalEarth != 0 || BodyGeography[index].x > 2.5 ? 10000.0 : 112.0);
    if (along <= 0.0 && radius <= 1.0 && index != AtmosphereBodyIndex && !insideRelief) { return color; }
    float separation = length(center - ray * along);
    vec3 normal = vec3(0);
    float hit = sphereHit(ray, center, radius, normal);
    float discCoverage = radius > 1.0 ? 1.0
            : 1.0 - smoothstep(radius - pixelAngle, radius + pixelAngle, separation);

    if (kind == 0) {
        if (index == EvolutionIndex) {
            return evolvingSolarRadiance(color, ray, center, radius, material.rgb, parameters.x, pixelAngle);
        }
        float ratio = separation / max(radius, 1e-20);
        float footprint = min(1.0, radius * radius / (pixelAngle * pixelAngle));
        float beyondLimb = max(0.0, ratio - 1.0);
        float corona = exp(-beyondLimb * 14.0) * 0.10
                     + exp(-beyondLimb * 4.5) * 0.013;
        if (ratio > 1.0 && ratio < 2.5) {
            vec3 radial = normalize(ray - center * along);
            float streamers = fbm(radial * 8.0 + vec3(parameters.x * 0.01));
            corona += pow(streamers, 3.0) * exp(-beyondLimb * 6.0) * 0.24;
        }
        color += mix(material.rgb, vec3(1.0, 0.67, 0.26), 0.38) * corona * footprint * 5.0;
        if (Supernova == 1) {
            // Catalog remnants are mature objects, not a shader-time explosion loop.
            color += stellarEjectaRadiance(ray, center, min(radius * 14.0, 0.85),
                                          16.0, parameters.x, pixelAngle);
        }
        if (discCoverage > 0.0) {
            bloomWeight = mix(bloomWeight, 1.0, discCoverage);
            if (radius <= 1.0) {
                normal = safeUnit(-(center - ray * along) - ray * sqrt(max(0.0,
                        radius * radius - separation * separation)), -ray);
            }
            float grain = noise(normal * 110.0 + vec3(Time * 0.004));
            float granulation = smoothstep(0.24, 0.72, grain);
            float cells = fbm(normal * 18.0 + vec3(Time * 0.0008));
            float activity = fbm(normal * 7.0 + vec3(parameters.x * 0.071));
            float spots = (1.0 - smoothstep(0.215, 0.29, activity)) * 0.62;
            float limb = 0.28 + 0.72 * pow(max(dot(normal, -ray), 0.0), 0.45);
            // Linear photospheric energy survives in RGBA16F; lower exposure reveals granules.
            vec3 sun = mix(material.rgb, vec3(1.0, 0.93, 0.80), 0.4)
                     * (1.02 + granulation * 0.65 + cells * 0.23) * limb * (1.0 - spots) * 6.0;
            float coverage = radius < pixelAngle
                    ? exp(-separation * separation / (pixelAngle * pixelAngle)) * footprint : discCoverage;
            color = mix(color, sun, coverage);
        }
        return color;
    }

    if (kind == 1) { return blackHoleRadiance(color, ray, index, pixelAngle); }

    if (BodyGeography[index].w > 0.5) {
        hit = mappedEarthHit(index, ray, center, hit, BodyGeography[index], uint(BodyGeographySeed[index]),
                tilt, BodySpin[index], pixelAngle, normal);
        if (hit > 0.0 && BodyGeography[index].y < (ContinentalEarth != 0 ? 500000.0 : 100000.0)) {
            float displacedRadius = length(ray * hit - center);
            // Separation is sin(angle), not angle. Near a human-height horizon
            // its derivative approaches zero; an unscaled angular width leaks
            // the star background through several degrees of solid ground.
            float limbWidth = pixelAngle * sqrt(max(0.0, 1.0 - displacedRadius * displacedRadius))
                    + 0.5 * pixelAngle * pixelAngle;
            discCoverage = displacedRadius > 1.0 ? 1.0
                    : 1.0 - smoothstep(displacedRadius - limbWidth, displacedRadius + limbWidth, separation);
        }
    }

    float atmosphereRadius = radius * (1.0 + parameters.y * 0.035);
    float halo = exp(-max(0.0, separation / max(radius, 1e-20) - 1.0) * 38.0)
               * (1.0 - smoothstep(atmosphereRadius, atmosphereRadius + pixelAngle, separation));
    vec3 tangentNormal = normalize(ray * along - center + vec3(1e-12));
    float haloDay = smoothstep(-0.25, 0.25, dot(tangentNormal, light));
    if (index != AtmosphereBodyIndex) {
        vec3 haloColor = BodyAtmosphereModel[index] > 0.5
                ? mix(vec3(0.12, 0.035, 0.01), vec3(0.45, 0.23, 0.10), haloDay)
                : mix(vec3(0.25, 0.045, 0.005), vec3(0.035, 0.16, 0.46), haloDay);
        color += haloColor * halo * parameters.y * (0.12 + haloDay);
    }
    float ringHit;
    vec3 ringNormal = normalize(vec3(0.0, cos(tilt), sin(tilt)));
    vec4 ring = ringSurface(ray, center, radius, ringNormal, parameters.z, parameters.w,
                           light, mix(material.rgb, vec3(0.76, 0.66, 0.48), 0.7), parameters.x, pixelAngle, ringHit);
    bool canonicalEarth = ContinentalEarth != 0 && BodyGeography[index].x > 1.5 && BodyGeography[index].x < 2.5;
    float solarGain = EvolutionIndex >= 0 ? max(canonicalEarth ? 0.0 : 0.018, SolarLight.x + SolarLight.y * 0.6) : 1.0;
    ring.rgb *= solarGain;
    if (ring.a > 0.0 && (hit < 0.0 || ringHit > hit)) { color = mix(color, ring.rgb, ring.a); }
    if (hit > 0.0) {
        float normalFootprint = pixelAngle * max(hit, 1e-7)
                / max(1e-8, radius * max(0.025, abs(dot(normal, -ray))));
        vec3 surface = planetSurface(index, normal, ray, light, material, parameters, tilt, BodySpin[index],
                BodyGeography[index], uint(BodyGeographySeed[index]), normalFootprint) * solarGain;
        vec3 bodyFixed = mappedSurfaceCoordinates(normal, tilt, BodySpin[index], BodyGeography[index]);
        vec4 observedSurface;
        vec2 observedLight;
        float emittedLuminance = 0.0;
        if (orbitalLookup(index, bodyFixed, BodyGeography[index].z,
                normalFootprint * BodyGeography[index].z, observedSurface, observedLight)) {
            float night = 1.0 - smoothstep(0.02, 0.18, max(dot(normal, light), 0.0) * solarGain);
            vec3 emission = vec3(1.0, 0.67, 0.30) * observedLight.x * observedLight.y * night * 1.8;
            surface += emission;
            emittedLuminance = dot(emission, vec3(0.2126, 0.7152, 0.0722));
        }
        color = mix(color, surface, discCoverage);
        // Host display white requires large inverse-shoulder values, but is not an emissive source.
        // Store eligibility only in our private HDR attachment; final sky alpha remains opaque.
        float surfaceBloom = BodyGeography[index].x > 2.5 || ContinentalEarth != 0 && BodyGeography[index].x > 1.5 ? 0.0 : 1.0;
        surfaceBloom = max(surfaceBloom, emittedLuminance / max(0.0001, dot(surface, vec3(0.2126, 0.7152, 0.0722))));
        bloomWeight = mix(bloomWeight, surfaceBloom, discCoverage);
    }
    if (ring.a > 0.0 && hit > 0.0 && ringHit < hit) { color = mix(color, ring.rgb, ring.a); }
    float cloudDistance = 0.0;
    vec4 cloudTransportValue = vec4(0, 0, 0, 1);
    if (canonicalEarth && CloudPlanet.w > 0.0) {
        float limitKm = hit > 0.0 ? hit * (BodyGeography[index].z + BodyGeography[index].y) * 0.001
                : length(CloudPlanet.xyz) + CloudPlanet.w + CloudLayer.y;
        vec3 cloudRay = mappedSurfaceCoordinates(ray, tilt, BodySpin[index], BodyGeography[index]);
        cloudTransportValue = earthCloudTransport(CloudPlanet.xyz, cloudRay, limitKm, pixelAngle,
                Detail >= 5 ? 32 : Detail >= 4 ? 24 : 16, cloudDistance);
    }
    if (index == AtmosphereBodyIndex) {
        color = planetaryAtmosphere(color, ray, AtmosphereObserver, light,
                EvolutionIndex >= 0 ? SolarLight.x + SolarLight.y * 0.6 : 1.0,
                hit > 0.0 ? 1.0 - discCoverage : 1.0,
                EvolutionIndex >= 0 ? BodyDirectionRadius[EvolutionIndex].w : 0.00465,
                hit > 0.0 ? hit * (BodyGeography[index].z + BodyGeography[index].y) * 0.001 : -1.0,
                BodyAtmosphereModel[index]);
        if (cloudTransportValue.a < 0.99999 && cloudDistance > 0.0) {
            vec3 cloudRadiance = cloudTransportValue.rgb / max(0.00001, 1.0 - cloudTransportValue.a);
            vec3 foregroundCloud = planetaryAtmosphere(cloudRadiance, ray, AtmosphereObserver, light,
                    max(0.0, solarGain), 0.0,
                    EvolutionIndex >= 0 ? BodyDirectionRadius[EvolutionIndex].w : 0.00465,
                    cloudDistance, BodyAtmosphereModel[index]);
            color = color * cloudTransportValue.a + foregroundCloud * (1.0 - cloudTransportValue.a);
        }
    } else if (cloudTransportValue.a < 0.99999) {
        color = color * cloudTransportValue.a + cloudTransportValue.rgb;
    }
    return color;
}

// Protect actual directly visible foreground surfaces, including large spheres whose
// center lies behind the lens. Remaining disjoint objects retain the CPU far-to-near order.
bool foregroundOfLens(vec3 ray, int index, float lensPlane) {
    float ratio = BodyDistanceRatio[index];
    vec3 normal;
    float distance = sphereHit(ray, BodyDirectionRadius[index].xyz, BodyDirectionRadius[index].w, normal);
    if (distance > 0.0 && distance * ratio < lensPlane) { return true; }
    return ratio < 1.0;
}

void main() {
    vec4 reconstructed = InverseViewProjection * vec4(clipPosition, 1.0, 1.0);
    vec3 ray = normalize(reconstructed.xyz / reconstructed.w);
    float pixelAngle = max(length(dFdx(ray)), length(dFdy(ray))) * 0.65;
    pixelAngle = max(pixelAngle, 1.0 / max(ScreenSize.x, ScreenSize.y) * 0.25);
    bool lens = LensIndex >= 0 && LensIndex < BodyCount;
    vec3 sourceRay = ray;
    float lensPlane = 1.0;
    if (lens) {
        vec4 descriptor = BodyDirectionRadius[LensIndex];
        float theta = atan(length(ray - descriptor.xyz * dot(ray, descriptor.xyz)), dot(ray, descriptor.xyz));
        lens = theta < lensSupport(descriptor.w) || descriptor.w >= 1.0;
        if (lens) {
            sourceRay = gravitationalRay(ray, descriptor.xyz, descriptor.w, pixelAngle);
            vec3 normal;
            float surface = sphereHit(ray, descriptor.xyz, descriptor.w, normal);
            lensPlane = surface > 0.0 ? surface : max(dot(ray, descriptor.xyz), 0.001);
        }
    }
    // Compute background derivatives before divergent body paths.
    vec3 color = universe(sourceRay, pixelAngle);
    float bloomWeight = 1.0;
    // One body-material call site prevents drivers from triplicating the full
    // relief program. The host bounds BodyCount to 12 and all relief loop budgets.
    int passes = lens ? 2 : 1;
    for (int pass = 0; pass < passes; pass++) {
        if (pass == 1) { color = blackHoleRadiance(color, ray, LensIndex, pixelAngle); }
        vec3 materialRay = lens && pass == 0 ? sourceRay : ray;
        for (int i = 0; i < BodyCount; i++) {
            if (lens && (i == LensIndex || foregroundOfLens(ray, i, lensPlane) != (pass == 1))) { continue; }
            color = body(color, materialRay, i, pixelAngle, bloomWeight);
        }
    }
    if (SurfaceHorizon.w > 0.5) {
        float elevation = dot(ray, SurfaceHorizon.xyz);
        float heightKm = max(0.0, length(AtmosphereObserver.xyz) - AtmosphereObserver.w);
        float haze = exp(-pow(elevation / 0.035, 2.0) - heightKm / 8.0) * 0.92;
        float sunlight = EvolutionIndex >= 0 ? dot(ray, BodyDirectionRadius[EvolutionIndex].xyz) : -1.0;
        haze *= 1.0 - smoothstep(0.9998, 0.99997, sunlight);
        color = mix(color, celestialRadiance(SurfaceFog) / max(Exposure, 0.1), haze);
    }
    // Preserve linear radiance until the HDR bloom pipeline performs its single display transform.
    fragColor = vec4(HdrOutput == 1 ? max(color, vec3(0.0)) : celestialDisplay(color, Exposure),
                    HdrOutput == 1 ? bloomWeight : 1.0);
}
