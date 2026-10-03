// CPU ContinentalTerrain: visible height meters, Celsius, moisture, mountain mask (-1 for inland water).
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

vec4 continentalClimateMaterial(vec4 climate);

vec4 continentalField(sampler2D data, vec2 uv, bool material) {
    vec4 value = continentalBilinear(data, uv);
    return material ? continentalClimateMaterial(value) : value;
}

vec4 continentalSample(vec3 p, bool material) {
    if (ContinentalReady == 0) {
        vec4 fallback = vec4(0.0, 12.0, 0.5, 0.0);
        return material ? continentalClimateMaterial(fallback) : fallback;
    }
    vec2 globeSize = vec2(textureSize(ContinentalGlobe, 0));
    vec2 angular = vec2(atan(-p.z, p.x) / (2.0 * PI) + 0.5,
                        asin(clamp(p.y, -1.0, 1.0)) / PI + 0.5);
    // Explicit LOD keeps derivatives out of divergent relief-march loops.
    vec4 coarse = continentalField(ContinentalGlobe, (angular * (globeSize - 1.0) + 0.5) / globeSize, material);
    float forward = dot(p, ContinentalUp);
    if (ContinentalTilesReady == 0 || forward <= 0.0) { return coarse; }
    vec2 meters = vec2(dot(p, ContinentalEast), dot(p, ContinentalSouth)) * 6371000.0 / forward;
    float distance = max(abs(meters.x), abs(meters.y));
    vec4 extent = ContinentalSpacing * 256.0;
    vec4 weight = vec4(1.0) - smoothstep(extent * 0.8, extent, vec4(distance));
    if (weight.w <= 0.0) { return coarse; }
    vec4 result = continentalField(ContinentalTile3, continentalUv(meters, ContinentalSpacing.w), material);
    if (weight.z > 0.0) {
        result = mix(result, continentalField(ContinentalTile2, continentalUv(meters, ContinentalSpacing.z), material), weight.z);
    }
    if (weight.y > 0.0) {
        result = mix(result, continentalField(ContinentalTile1, continentalUv(meters, ContinentalSpacing.y), material), weight.y);
    }
    if (weight.x > 0.0) {
        result = mix(result, continentalField(ContinentalTile0, continentalUv(meters, ContinentalSpacing.x), material), weight.x);
    }
    return mix(coarse, result, weight.w);
}

vec4 continentalSample(vec3 p) { return continentalSample(p, false); }

// Display colors captured from the same host block textures and biome tints as the ground.
// Indices are the non-persistent EarthClimate presentation order.
uniform vec4 EarthSurfaceColors[12];
vec4 continentalClimateMaterial(vec4 climate) {
    float height = climate.x, temperature = climate.y, moisture = climate.z;
    int material;
    if (height < 0.0 || climate.w < -0.5) {
        material = temperature < 0.0 ? 2 : height < -600.0 ? 0 : 1;
    } else if (temperature + fract(height) * 0.0065 < 0.0) { material = 4; }
    else if (height < 5.0) { material = 3; }
    else if (height > 2400.0) { material = 5; }
    else if (temperature > 18.0 && moisture < 0.30) { material = 6; }
    else if (temperature > 21.0 && moisture < 0.48) { material = 7; }
    else if (temperature > 21.0 && moisture > 0.59) { material = 8; }
    else if (temperature < 9.0) { material = 9; }
    else { material = moisture > 0.43 ? 10 : 11; }
    return EarthSurfaceColors[material];
}

// Blend resolved material colors across cache resolutions. Classifying interpolated climate
// instead turns an innocent tile fade into a hard rectangular biome boundary.
vec4 continentalMaterial(vec3 p) { return continentalSample(p, true); }
