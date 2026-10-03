#version 150
const float PI = 3.141592653589793;
uniform sampler2D ProbeData;
uniform vec2 ProbeUv;
uniform int ProbeMaterial;
uniform int ProbeMode;
uniform vec3 ProbeDirection;
#moj_import <astraengine:continental_surface.glsl>
out vec4 fragColor;

// Retained pre-optimization evaluator. This intentionally does not call the lazy selection helper:
// native comparisons must detect missing globe/fractional/coarser contributions independently.
vec4 eagerContinentalSample(vec3 p, bool material) {
    if (ContinentalReady == 0) {
        vec4 fallback = vec4(0.0, 12.0, 0.5, 0.0);
        return material ? continentalClimateMaterial(fallback) : fallback;
    }
    vec2 globeSize = vec2(textureSize(ContinentalGlobe, 0));
    vec2 angular = vec2(atan(-p.z, p.x) / (2.0 * PI) + 0.5,
                        asin(clamp(p.y, -1.0, 1.0)) / PI + 0.5);
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

void main() {
    if (ProbeMode == 0) { fragColor = continentalField(ProbeData, ProbeUv, ProbeMaterial != 0); }
    else if (ProbeMode == 1 || (ProbeMode == 3 && gl_FragCoord.x < 1.0)) {
        fragColor = continentalSample(ProbeDirection, ProbeMaterial != 0);
    }
    else { fragColor = eagerContinentalSample(ProbeDirection, ProbeMaterial != 0); }
}
