// Version 1 mirror of SurfaceGeography. Hashes retain all 32 seed bits; value
// noise uses float arithmetic on both sides. Voxels remain the height authority.
float geographyHash(ivec3 cell, uint seed) {
    uint value = uint(cell.x) * 0x8DA6B343u ^ uint(cell.y) * 0xD8163841u
            ^ uint(cell.z) * 0xCB1AB31Fu ^ seed;
    value ^= value >> 16u;
    value *= 0x7FEB352Du;
    value ^= value >> 15u;
    value *= 0x846CA68Bu;
    value ^= value >> 16u;
    return float(value & 0xFFFFFFu) * (1.0 / 16777216.0);
}

float geographyNoise(vec3 p, uint seed) {
    ivec3 cell = ivec3(floor(p));
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float lower = mix(mix(geographyHash(cell, seed), geographyHash(cell + ivec3(1, 0, 0), seed), f.x),
            mix(geographyHash(cell + ivec3(0, 1, 0), seed), geographyHash(cell + ivec3(1, 1, 0), seed), f.x), f.y);
    float upper = mix(mix(geographyHash(cell + ivec3(0, 0, 1), seed), geographyHash(cell + ivec3(1, 0, 1), seed), f.x),
            mix(geographyHash(cell + ivec3(0, 1, 1), seed), geographyHash(cell + ivec3(1, 1, 1), seed), f.x), f.y);
    return mix(lower, upper, f.z);
}

float geographyCraters(vec3 p, uint seed) {
    ivec3 base = ivec3(floor(p));
    float result = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                ivec3 cell = base + ivec3(x, y, z);
                // Every center lies in [cell+0.15,cell+0.85], and the largest
                // nonzero rim reaches 0.6*1.22. Cull impossible cells before hashing.
                vec3 outside = max(max(vec3(cell) + 0.15 - p, p - (vec3(cell) + 0.85)), vec3(0.0));
                if (dot(outside, outside) > 0.538) { continue; }
                vec3 center = vec3(cell) + 0.15 + 0.7 * vec3(geographyHash(cell, seed),
                        geographyHash(cell, seed ^ 0x51A3u), geographyHash(cell, seed ^ 0xA73Du));
                float radius = 0.35 + 0.25 * geographyHash(cell, seed ^ 0x3C17u);
                float q = length(p - center) / radius;
                float bowl = max(0.0, 1.0 - q * q);
                float rim = max(0.0, 1.0 - abs(q - 1.0) / 0.22);
                result += -30.0 * bowl * bowl + 10.0 * rim * rim;
            }
        }
    }
    return result;
}

// A zero footprint is the unfiltered CPU contract. Unresolvable relief fades
// smoothly at orbit distances instead of generating crawling subpixel craters.
float geographyHeight(vec3 p, int kind, uint seed, float footprint) {
    float height;
    if (kind == 1) {
        float fine = 1.0 - smoothstep(0.2, 0.8, footprint * 4096.0);
        float crater = 1.0 - smoothstep(0.2, 0.8, footprint * 1600.0);
        height = 18.0 + (geographyNoise(p * 8.0, seed) - 0.5) * 40.0;
        if (fine > 0.001) { height += (geographyNoise(p * 4096.0, seed ^ 0x71E1u) - 0.5) * 8.0 * fine; }
        if (crater > 0.001) { height += geographyCraters(p * 1600.0, seed ^ 0xC4A7u) * crater; }
    } else {
        float coast = geographyNoise(vec3(3.1, 0, 0), seed);
        height = (geographyNoise(p * 3.1, seed) - coast) * 320.0;
        float hills = 1.0 - smoothstep(0.2, 0.8, footprint * 8192.0);
        float fine = 1.0 - smoothstep(0.2, 0.8, footprint * 32768.0);
        if (hills > 0.001) { height += (geographyNoise(p * 8192.0, seed ^ 0x71E1u) - 0.5) * 24.0 * hills; }
        if (fine > 0.001) { height += (geographyNoise(p * 32768.0, seed ^ 0xB135u) - 0.5) * 6.0 * fine; }
    }
    return clamp(height, -48.0, 112.0);
}
