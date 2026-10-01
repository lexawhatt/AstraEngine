// Render-owned local height bands sampled from the same immutable CPU geography.
// All coordinates are body-fixed; angular pixel footprint uses the analytic filter.
uniform sampler2D EarthHeightTile0;
uniform sampler2D EarthHeightTile1;
uniform sampler2D EarthHeightTile2;
uniform int EarthHeightCacheEnabled;
uniform vec3 EarthHeightUp;
uniform vec3 EarthHeightEast;
uniform vec3 EarthHeightSouth;
uniform vec4 EarthHeightGrid; // physical radius, texel count, inner and middle spacing
uniform float EarthHeightOuterSpacing;

vec2 earthHeightUv(vec2 meters, float spacing) {
    // Samples lie at grid vertices including both edges, rather than at cell centers.
    return (meters / spacing + vec2(EarthHeightGrid.y * 0.5)) / EarthHeightGrid.y;
}

float earthHeightFromBands(vec3 bands, float footprint) {
    float hills = 1.0 - smoothstep(0.2, 0.8, footprint * 8192.0);
    float fine = 1.0 - smoothstep(0.2, 0.8, footprint * 32768.0);
    return clamp(bands.x + bands.y * hills + bands.z * fine, -48.0, 112.0);
}

float earthHeight(vec3 p, uint seed, float footprint) {
    if (ContinentalEarth != 0) { return continentalSample(p).x; }
    float cached = 0.0;
    float coverage = 0.0;
    float forward = dot(p, EarthHeightUp);
    if (EarthHeightCacheEnabled != 0 && forward > 0.0) {
        vec2 meters = vec2(dot(p, EarthHeightEast), dot(p, EarthHeightSouth)) * EarthHeightGrid.x / forward;
        float distance = max(abs(meters.x), abs(meters.y));
        float halfIntervals = (EarthHeightGrid.y - 1.0) * 0.5;
        float inner = halfIntervals * EarthHeightGrid.z;
        float middle = halfIntervals * EarthHeightGrid.w;
        float outer = halfIntervals * EarthHeightOuterSpacing;
        coverage = 1.0 - smoothstep(outer * 0.8, outer, distance);
        if (coverage > 0.0) {
            float fine = 1.0 - smoothstep(inner * 0.8, inner, distance);
            float medium = (1.0 - fine) * (1.0 - smoothstep(middle * 0.8, middle, distance));
            float coarse = 1.0 - fine - medium;
            vec3 bands = vec3(0.0);
            if (fine > 0.0) { bands += texture(EarthHeightTile0, earthHeightUv(meters, EarthHeightGrid.z)).rgb * fine; }
            if (medium > 0.0) { bands += texture(EarthHeightTile1, earthHeightUv(meters, EarthHeightGrid.w)).rgb * medium; }
            if (coarse > 0.0) { bands += texture(EarthHeightTile2, earthHeightUv(meters, EarthHeightOuterSpacing)).rgb * coarse; }
            cached = earthHeightFromBands(bands, footprint);
        }
    }
    if (coverage >= 1.0) { return cached; }
    // Keep one analytic fallback site. Repeating it in every branch makes Mesa's
    // inliner expand the hash field at every parallax sample during program linking.
    return mix(geographyHeight(p, 2, seed, footprint), cached, coverage);
}
