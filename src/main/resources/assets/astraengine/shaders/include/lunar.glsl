// Shared airless lunar material. Depends on the pinned surface_geography helpers.
// Maria and distant impact scars are albedo only; they do not change canonical
// geography, radii, landing coordinates or the version-one voxel terrain.
vec3 lunarAlbedo(vec3 p, float height, uint seed, float footprint) {
    float broad = geographyNoise(p * 3.2 + vec3(2.7, 0.3, 4.1), seed ^ 0x1E39u);
    float folded = geographyNoise(p * 7.5 + vec3(broad * 0.8), seed ^ 0xA153u);
    float maria = smoothstep(0.47, 0.62, broad * 0.72 + folded * 0.28);
    float mottling = geographyNoise(p * 21.0, seed ^ 0xB359u);
    vec3 highlands = mix(vec3(0.27, 0.255, 0.235), vec3(0.43, 0.415, 0.385), mottling);
    vec3 basalt = mix(vec3(0.075, 0.08, 0.087), vec3(0.16, 0.16, 0.155), folded);
    vec3 albedo = mix(highlands, basalt, maria * 0.90);

    // Analytic pigment rings and ejecta rays stay stable in body coordinates.
    // Suppress unresolved rings rather than introducing noisy subpixel sparkle.
    for (int i = 0; i < 48; i++) {
        ivec3 key = ivec3(i, 29, 73);
        float z = 1.0 - 2.0 * (float(i) + 0.5) / 48.0;
        float azimuth = float(i) * 2.39996322973 + geographyHash(ivec3(7, 3, 9), seed) * 6.28318530718;
        float radial = sqrt(max(0.0, 1.0 - z * z));
        vec3 center = vec3(cos(azimuth) * radial, z, sin(azimuth) * radial);
        float size = 0.017 + 0.042 * geographyHash(key, seed ^ 0x9231u);
        float distance = length(p - center);
        if (distance > size * 5.0) { continue; }
        float brokenRim = geographyNoise(p * 120.0, seed ^ uint(i + 317));
        float q = distance / size + (brokenRim - 0.5) * 0.14;
        float edge = max(footprint / size, 0.085);
        float resolved = 1.0 - smoothstep(0.12, 0.85, footprint / size);
        float rim = exp(-pow((q - 1.0) / max(0.19, edge), 2.0)) * (0.55 + brokenRim * 0.45);
        float floorMask = 1.0 - smoothstep(0.45, 0.85 + edge, q);
        vec3 tangent = normalize(cross(center, abs(center.y) < 0.9 ? vec3(0, 1, 0) : vec3(1, 0, 0)));
        vec3 delta = p - center;
        float angle = atan(dot(delta, cross(center, tangent)), dot(delta, tangent) + 1e-8);
        float spoke = pow(max(0.0, sin(angle * 11.0 + float(i) * 1.7) * 0.55
                + sin(angle * 17.0 + float(i)) * 0.30 + sin(angle * 7.0 - float(i)) * 0.15), 4.0);
        float rays = spoke * exp(-max(0.0, q - 1.0) * 1.25) * smoothstep(0.9, 1.3, q)
                * (1.0 - smoothstep(2.5, 5.0, q));
        albedo = mix(albedo, albedo * 0.70, floorMask * resolved * 0.55);
        albedo += vec3(0.16, 0.15, 0.135) * (rim + rays * 1.1) * resolved;
    }
    float fine = 1.0 - smoothstep(0.15, 0.65, footprint * 140.0);
    if (fine > 0.001) {
        albedo *= 1.0 + (geographyNoise(p * 140.0, seed ^ 0x382Du) - 0.5) * 0.25 * fine;
    }
    // Meter-scale regolith appears only when its footprint is resolved. These
    // bands are reflectance, preserving the saved height field at close approach.
    float grain = 1.0 - smoothstep(0.15, 0.75, footprint * 108587.5);
    if (grain > 0.001) {
        albedo *= 1.0 + (geographyNoise(p * 108587.5, seed ^ 0xB28Du) - 0.5) * 0.30 * grain;
    }
    // Near the surface, the saved geography's crater bowls/rims tint the regolith.
    albedo *= 0.93 + smoothstep(-30.0, 35.0, height) * 0.12;
    return albedo;
}

vec3 lunarRadiance(vec3 p, vec3 light, vec3 toObserver, float height, uint seed,
                   float footprint, float radiusMeters, bool mappedTerrain) {
    vec3 normal = p;
    if (mappedTerrain && footprint < 0.00045) {
        // Only the canonical height field supplies relief normals. Optical impact
        // markings above never introduce invented kilometer-scale terrain.
        vec3 tangent = normalize(cross(p, abs(p.y) < 0.9 ? vec3(0, 1, 0) : vec3(1, 0, 0)));
        vec3 bitangent = cross(p, tangent);
        float stepAngle = max(2.0 / max(radiusMeters, 1.0), footprint * 1.2);
        float hX = geographyHeight(normalize(p + tangent * stepAngle), 1, seed, footprint);
        float hY = geographyHeight(normalize(p + bitangent * stepAngle), 1, seed, footprint);
        vec2 slope = clamp(vec2(hX - height, hY - height) / max(stepAngle * radiusMeters, 0.01),
                           vec2(-2.0), vec2(2.0));
        normal = normalize(p - tangent * slope.x - bitangent * slope.y);
    }
    float incident = max(0.0, dot(normal, light));
    float visible = max(0.025, dot(normal, toObserver));
    float geometricDay = smoothstep(-0.008, 0.025, dot(p, light));
    // A bounded lunar-style diffuse response keeps a full Moon broad and flat,
    // while the real terminator and local relief retain strong grazing contrast.
    float scattering = (0.62 * incident / max(incident + visible, 0.025) + 0.38 * incident) * geometricDay;
    float opposition = pow(max(0.0, dot(light, toObserver)), 24.0) * 0.10;
    return lunarAlbedo(p, height, seed, footprint) * (0.003 + scattering * (1.0 + opposition));
}
