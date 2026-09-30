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
uniform vec4 BodyGeography[12];
uniform int BodyGeographySeed[12];
uniform int AtmosphereBodyIndex;
uniform vec4 AtmosphereObserver;
uniform vec4 SurfaceHorizon;
uniform vec3 SurfaceFog;

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
    float polarWidth = max(0.00015, footprint * 0.65);
    float ice = smoothstep(0.82 - polarWidth, 0.82 + polarWidth, abs(p.y));
    vec3 snow = mix(vec3(0.48, 0.58, 0.65), vec3(0.84, 0.89, 0.91), variation);
    land = mix(land, snow, ice);
    float shelf = smoothstep(-22.0, -1.0, height);
    vec3 ocean = mix(vec3(0.003, 0.016, 0.036), vec3(0.011, 0.071, 0.078), shelf);
    ocean *= 0.96 + detail * 0.15;
    return mix(land, ocean, water);
}

vec3 planetSurface(vec3 n, vec3 viewRay, vec3 light, vec4 material, vec4 parameters,
                   float tilt, float spin, vec4 geography, uint geographySeed, float normalFootprint) {
    int kind = int(material.w + 0.5);
    vec3 p = surfaceCoordinates(n, tilt, spin);
    vec3 seed = vec3(parameters.x * 0.071, parameters.x * 0.027, 3.7);
    float diffuse = max(dot(n, light), 0.0);
    float day = smoothstep(-0.075, 0.15, dot(n, light));
    float terrain = geography.x > 0.5 ? 0.5 : fbm(p * 3.5 + seed);
    vec3 albedo = material.rgb;
    float water = 0.0;
    float clouds = 0.0;
    if (geography.x > 0.5) {
        int surfaceKind = int(geography.x + 0.5);
        float height = geographyHeight(p, surfaceKind, geographySeed, normalFootprint);
        float fineWeight = 1.0 - smoothstep(0.15, 0.8, normalFootprint * geography.z / 16.0);
        float grain = fineWeight > 0.001 ? noise(p * (geography.z / 16.0)) : 0.5;
        if (surfaceKind == 1) {
            albedo = mix(vec3(0.23, 0.22, 0.205), vec3(0.39, 0.38, 0.36), smoothstep(-30.0, 35.0, height));
        } else {
            water = 1.0 - smoothstep(-0.3, 0.4, height);
            albedo = mappedEarthAlbedo(p, height, geographySeed, normalFootprint, water);
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
    vec3 color = albedo * (0.004 + diffuse * 1.35);
    if (kind == 3) {
        float specular = pow(max(0.0, dot(reflect(-light, n), -viewRay)), 90.0);
        color += vec3(1.0, 0.82, 0.59) * specular * water * (1.0 - clouds) * day;
        float city = geography.x > 0.5 ? 0.0 : pow(noise(p * 230.0 + seed), 22.0)
                   * smoothstep(0.515, 0.55, fbm(p * 2.7 + seed)) * (1.0 - day) * (1.0 - clouds);
        color += vec3(1.0, 0.57, 0.18) * city * 0.7;
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

vec3 body(vec3 color, vec3 ray, int index, float pixelAngle) {
    vec4 descriptor = BodyDirectionRadius[index];
    vec3 center = descriptor.xyz;
    float radius = descriptor.w;
    vec4 material = BodyColorKind[index];
    vec4 parameters = BodySurface[index];
    vec3 light = BodyLightTilt[index].xyz;
    float tilt = BodyLightTilt[index].w;
    int kind = int(material.w + 0.5);
    float along = dot(ray, center);
    if (along <= 0.0 && radius <= 1.0 && index != AtmosphereBodyIndex) { return color; }
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

    float atmosphereRadius = radius * (1.0 + parameters.y * 0.035);
    float halo = exp(-max(0.0, separation / max(radius, 1e-20) - 1.0) * 38.0)
               * (1.0 - smoothstep(atmosphereRadius, atmosphereRadius + pixelAngle, separation));
    vec3 tangentNormal = normalize(ray * along - center + vec3(1e-12));
    float haloDay = smoothstep(-0.25, 0.25, dot(tangentNormal, light));
    if (index != AtmosphereBodyIndex) {
        color += mix(vec3(0.25, 0.045, 0.005), vec3(0.035, 0.16, 0.46), haloDay)
               * halo * parameters.y * (0.12 + haloDay);
    }
    float ringHit;
    vec3 ringNormal = normalize(vec3(0.0, cos(tilt), sin(tilt)));
    vec4 ring = ringSurface(ray, center, radius, ringNormal, parameters.z, parameters.w,
                           light, mix(material.rgb, vec3(0.76, 0.66, 0.48), 0.7), parameters.x, pixelAngle, ringHit);
    float solarGain = EvolutionIndex >= 0 ? max(0.018, SolarLight.x + SolarLight.y * 0.6) : 1.0;
    ring.rgb *= solarGain;
    if (ring.a > 0.0 && (hit < 0.0 || ringHit > hit)) { color = mix(color, ring.rgb, ring.a); }
    if (hit > 0.0) {
        float normalFootprint = pixelAngle * max(hit, 1e-7)
                / max(1e-8, radius * max(0.025, abs(dot(normal, -ray))));
        vec3 surface = planetSurface(normal, ray, light, material, parameters, tilt, BodySpin[index],
                BodyGeography[index], uint(BodyGeographySeed[index]), normalFootprint) * solarGain;
        color = mix(color, surface, discCoverage);
    }
    if (ring.a > 0.0 && hit > 0.0 && ringHit < hit) { color = mix(color, ring.rgb, ring.a); }
    if (index == AtmosphereBodyIndex) {
        color = planetaryAtmosphere(color, ray, AtmosphereObserver, light,
                EvolutionIndex >= 0 ? SolarLight.x + SolarLight.y * 0.6 : 1.0,
                hit > 0.0 ? 1.0 - discCoverage : 1.0,
                EvolutionIndex >= 0 ? BodyDirectionRadius[EvolutionIndex].w : 0.00465);
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
    if (!lens) {
        for (int i = 0; i < 12; i++) {
            if (i >= BodyCount) { break; }
            color = body(color, ray, i, pixelAngle);
        }
    } else {
        for (int i = 0; i < 12; i++) {
            if (i >= BodyCount) { break; }
            if (i != LensIndex && !foregroundOfLens(ray, i, lensPlane)) {
                color = body(color, sourceRay, i, pixelAngle);
            }
        }
        color = blackHoleRadiance(color, ray, LensIndex, pixelAngle);
        for (int i = 0; i < 12; i++) {
            if (i >= BodyCount) { break; }
            if (i != LensIndex && foregroundOfLens(ray, i, lensPlane)) {
                color = body(color, ray, i, pixelAngle);
            }
        }
    }
    if (SurfaceHorizon.w > 0.5) {
        float elevation = dot(ray, SurfaceHorizon.xyz);
        float haze = exp(-pow(elevation / 0.035, 2.0)) * 0.92;
        float sunlight = EvolutionIndex >= 0 ? dot(ray, BodyDirectionRadius[EvolutionIndex].xyz) : -1.0;
        haze *= 1.0 - smoothstep(0.9998, 0.99997, sunlight);
        color = mix(color, celestialRadiance(SurfaceFog) / max(Exposure, 0.1), haze);
    }
    // Preserve linear radiance until the HDR bloom pipeline performs its single display transform.
    fragColor = vec4(HdrOutput == 1 ? max(color, vec3(0.0)) : celestialDisplay(color, Exposure), 1.0);
}
