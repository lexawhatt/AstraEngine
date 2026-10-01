// Exact CPU ContinentalTerrain samples: height meters, Celsius, moisture, mountain mask.
uniform int ContinentalEarth;
uniform int ContinentalReady;
uniform int ContinentalTilesReady;
uniform sampler2D ContinentalGlobe;
uniform sampler2D ContinentalTile0;
uniform sampler2D ContinentalTile1;
uniform sampler2D ContinentalTile2;
uniform sampler2D ContinentalTile3;
uniform vec3 ContinentalUp;
uniform vec3 ContinentalEast;
uniform vec3 ContinentalSouth;
uniform vec4 ContinentalSpacing;

vec2 continentalUv(vec2 meters, float spacing) {
    return (meters / spacing + vec2(256.5)) / 513.0;
}

// Hardware linear weights can have only eight fractional bits. Kilometer relief
// then acquires meter-high terraces and a discontinuous ray residual. Interpolate
// in shader float precision; these four taps read the same bounded mip-zero data.
vec4 continentalBilinear(sampler2D data, vec2 uv) {
    ivec2 size = textureSize(data, 0);
    vec2 position = clamp(uv * vec2(size) - 0.5, vec2(0.0), vec2(size - 1));
    ivec2 low = ivec2(floor(position));
    ivec2 high = min(low + 1, size - 1);
    vec2 weight = fract(position);
    return mix(mix(texelFetch(data, low, 0), texelFetch(data, ivec2(high.x, low.y), 0), weight.x),
               mix(texelFetch(data, ivec2(low.x, high.y), 0), texelFetch(data, high, 0), weight.x), weight.y);
}

vec4 continentalSample(vec3 p) {
    if (ContinentalReady == 0) { return vec4(0.0, 12.0, 0.5, 0.0); }
    vec2 globeSize = vec2(textureSize(ContinentalGlobe, 0));
    vec2 angular = vec2(atan(-p.z, p.x) / (2.0 * PI) + 0.5,
                        asin(clamp(p.y, -1.0, 1.0)) / PI + 0.5);
    // Explicit LOD keeps derivatives out of divergent relief-march loops.
    vec4 coarse = continentalBilinear(ContinentalGlobe, (angular * (globeSize - 1.0) + 0.5) / globeSize);
    float forward = dot(p, ContinentalUp);
    if (ContinentalTilesReady == 0 || forward <= 0.0) { return coarse; }
    vec2 meters = vec2(dot(p, ContinentalEast), dot(p, ContinentalSouth)) * 6371000.0 / forward;
    float distance = max(abs(meters.x), abs(meters.y));
    vec4 extent = ContinentalSpacing * 256.0;
    vec4 weight = vec4(1.0) - smoothstep(extent * 0.8, extent, vec4(distance));
    if (weight.w <= 0.0) { return coarse; }
    vec4 result = continentalBilinear(ContinentalTile3, continentalUv(meters, ContinentalSpacing.w));
    if (weight.z > 0.0) {
        result = mix(result, continentalBilinear(ContinentalTile2, continentalUv(meters, ContinentalSpacing.z)), weight.z);
    }
    if (weight.y > 0.0) {
        result = mix(result, continentalBilinear(ContinentalTile1, continentalUv(meters, ContinentalSpacing.y)), weight.y);
    }
    if (weight.x > 0.0) {
        result = mix(result, continentalBilinear(ContinentalTile0, continentalUv(meters, ContinentalSpacing.x)), weight.x);
    }
    return mix(coarse, result, weight.w);
}

vec3 continentalAlbedo(vec3 p, float water) {
    vec4 climate = continentalSample(p);
    vec3 dry = mix(vec3(0.25, 0.20, 0.105), vec3(0.42, 0.31, 0.15), smoothstep(12.0, 28.0, climate.y));
    vec3 foliage = mix(vec3(0.034, 0.075, 0.039), vec3(0.055, 0.105, 0.024), smoothstep(4.0, 24.0, climate.y));
    vec3 land = mix(dry, foliage, smoothstep(0.25, 0.53, climate.z));
    float rock = smoothstep(1900.0, 3400.0, climate.x);
    land = mix(land, vec3(0.26, 0.245, 0.225), rock);
    float snow = 1.0 - smoothstep(-5.0, 1.0, climate.y);
    land = mix(land, vec3(0.73, 0.79, 0.81), snow);
    float shelf = smoothstep(-650.0, -8.0, climate.x);
    vec3 ocean = mix(vec3(0.003, 0.014, 0.034), vec3(0.009, 0.055, 0.066), shelf);
    float seaIce = 1.0 - smoothstep(-12.0, -4.0, climate.y);
    ocean = mix(ocean, vec3(0.59, 0.69, 0.74), seaIce);
    return mix(land, ocean, water);
}
