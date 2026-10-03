// Sparse server-observed chunks, then 256 m pages. No noise-generated cities or client-authored block state.
uniform sampler2D OrbitalEdits;
uniform int OrbitalEditsReady;
uniform int OrbitalEditsProbes;

uint orbitalHash(ivec2 tile, int marker) {
    uint value = uint(tile.x) * 0x8da6b343u ^ uint(tile.y) * 0xd8163841u ^ uint(marker) * 0xcb1ab31fu;
    value ^= value >> 16u; value *= 0x7feb352du; value ^= value >> 15u;
    return value & 8191u;
}

// Must match CubeFace.containing tie order and its persistent u/v axes.
vec2 orbitalChart(vec3 p, float radius, out int face) {
    vec3 a = abs(p);
    if (a.x >= a.y && a.x >= a.z) {
        face = p.x >= 0.0 ? 0 : 1;
        return vec2(p.x >= 0.0 ? -p.z : p.z, -p.y) * radius / a.x;
    }
    if (a.y >= a.z) {
        face = p.y >= 0.0 ? 2 : 3;
        return vec2(p.x, p.y >= 0.0 ? p.z : -p.z) * radius / a.y;
    }
    face = p.z >= 0.0 ? 4 : 5;
    return vec2(p.z >= 0.0 ? p.x : -p.x, -p.y) * radius / a.z;
}

bool orbitalLookup(int body, vec3 normal, float radius, float footprintMeters,
                   out vec4 materialHeight, out vec2 emissionCoverage) {
    materialHeight = vec4(0.0); emissionCoverage = vec2(0.0);
    if (OrbitalEditsReady == 0) { return false; }
    int face; vec2 meters = orbitalChart(normal, radius, face);
    for (int lod = 0; lod < 2; lod++) {
        float size = lod == 0 ? 16.0 : 256.0;
        // Once a fine chunk is subpixel, its neighboring chunks contribute to the same pixel.
        // Select their coarse aggregate instead of attenuating just one arbitrarily selected chunk.
        if (lod == 0 && footprintMeters > size) { continue; }
        ivec2 tile = ivec2(floor(meters / size));
        int marker = 1 + body * 12 + face * 2 + lod;
        uint initial = orbitalHash(tile, marker);
        for (int probe = 0; probe < 128; probe++) {
            if (probe >= OrbitalEditsProbes) { break; }
            int slot = int((initial + uint(probe)) & 8191u);
            vec4 key = texelFetch(OrbitalEdits, ivec2(slot, 0), 0);
            if (key.z == 0.0) { break; }
            if (int(key.z) == marker && all(equal(ivec2(key.xy), tile))) {
                vec2 cellPosition = clamp(fract(meters / size) * 4.0, vec2(0.0), vec2(3.999));
                ivec2 cell = ivec2(cellPosition); int row = (cell.y * 4 + cell.x) * 2 + 1;
                materialHeight = texelFetch(OrbitalEdits, ivec2(slot, row), 0);
                emissionCoverage = texelFetch(OrbitalEdits, ivec2(slot, row + 1), 0).xy;
                // Average an entire patch once its cell detail is subpixel: energy fades with angular area.
                float blur = smoothstep(size * 0.20, size, footprintMeters);
                if (blur > 0.0) {
                    vec4 sum = vec4(0.0); float emission = 0.0, weight = 0.0;
                    for (int sampleIndex = 0; sampleIndex < 16; sampleIndex++) {
                        vec4 sampleMaterial = texelFetch(OrbitalEdits, ivec2(slot, sampleIndex * 2 + 1), 0);
                        vec2 sampleEmission = texelFetch(OrbitalEdits, ivec2(slot, sampleIndex * 2 + 2), 0).xy;
                        sum += sampleMaterial * sampleEmission.y;
                        emission += sampleEmission.x * sampleEmission.y; weight += sampleEmission.y;
                    }
                    if (weight > 0.0) {
                        materialHeight = mix(materialHeight, sum / weight, blur);
                        emissionCoverage = mix(emissionCoverage, vec2(emission / weight, weight / 16.0), blur);
                    }
                }
                float angularCoverage = min(1.0, size * size / max(1.0, footprintMeters * footprintMeters));
                emissionCoverage.y *= angularCoverage;
                if (emissionCoverage.y > 0.0) { return true; }
            }
        }
    }
    return false;
}
