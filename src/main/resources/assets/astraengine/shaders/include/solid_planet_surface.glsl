// RGBA maps contain canonical physical bed height plus host display RGB. Twelve fixed atlas slots,
// three nearby tiles and one CPU worker bound the cost independently of physical body size.
uniform sampler2D PlanetAtlas;
uniform sampler2D PlanetTiles;
uniform int PlanetSlots[12];
uniform int PlanetDetailIndex;
uniform vec3 PlanetUp;
uniform vec3 PlanetEast;
uniform vec3 PlanetSouth;
uniform vec3 PlanetSpacing;

vec4 planetAtlasSample(int index, vec3 p) {
    int slot = PlanetSlots[index];
    if (slot < 0) { return vec4(0.0, 0.5, 0.5, 0.5); }
    vec2 angular = vec2(atan(-p.z, p.x) / (2.0 * PI) + 0.5,
            asin(clamp(p.y, -1.0, 1.0)) / PI + 0.5);
    vec2 base = vec2(slot % 4, slot / 4) * vec2(513.0, 257.0);
    vec2 uv = (base + angular * vec2(512.0, 256.0) + 0.5) / vec2(2052.0, 771.0);
    return continentalBilinear(PlanetAtlas, uv);
}

vec4 planetTileSample(int tile, vec2 meters, float spacing) {
    vec2 uv = continentalUv(meters, spacing);
    // Preserve texel-center margins inside the packed tile, so filtering cannot bleed between levels.
    uv.x = (float(tile) + uv.x) / 3.0;
    return continentalBilinear(PlanetTiles, uv);
}

vec4 planetTerrainSample(int index, vec3 p, float radiusMeters) {
    vec4 coarse = planetAtlasSample(index, p);
    float forward = dot(p, PlanetUp);
    if (PlanetDetailIndex != index || forward <= 0.0) { return coarse; }
    vec2 meters = vec2(dot(p, PlanetEast), dot(p, PlanetSouth)) * radiusMeters / forward;
    float distance = max(abs(meters.x), abs(meters.y));
    vec3 extent = PlanetSpacing * 256.0;
    vec3 weight = vec3(1.0) - smoothstep(extent * 0.8, extent, vec3(distance));
    if (weight.z <= 0.0) { return coarse; }
    vec4 result = planetTileSample(2, meters, PlanetSpacing.z);
    if (weight.y > 0.0) {
        result = mix(result, planetTileSample(1, meters, PlanetSpacing.y), weight.y);
    }
    if (weight.x > 0.0) {
        result = mix(result, planetTileSample(0, meters, PlanetSpacing.x), weight.x);
    }
    return mix(coarse, result, weight.z);
}

vec3 planetTerrainNormal(int index, vec3 p, float radiusMeters, float footprint) {
    float stepMeters = max(16.0, footprint * radiusMeters * 1.4);
    vec3 axis = abs(p.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 east = normalize(cross(axis, p));
    vec3 south = cross(p, east);
    float angle = stepMeters / radiusMeters;
    float dx = planetTerrainSample(index, normalize(p + east * angle), radiusMeters).x
            - planetTerrainSample(index, normalize(p - east * angle), radiusMeters).x;
    float dz = planetTerrainSample(index, normalize(p + south * angle), radiusMeters).x
            - planetTerrainSample(index, normalize(p - south * angle), radiusMeters).x;
    return normalize(p - east * dx / (2.0 * stepMeters) - south * dz / (2.0 * stepMeters));
}
