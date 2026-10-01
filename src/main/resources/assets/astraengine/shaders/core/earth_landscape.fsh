#version 150
uniform vec3 LightColor;
uniform vec3 HazeColor;
uniform float EyeAltitude;
uniform float Flash;
uniform float NearCoverage;
uniform vec2 BandAltitude;
in vec3 localPosition;
in vec3 localNormal;
in vec3 materialColor;
in vec2 materialPosition;
out vec4 fragColor;
float materialHash(ivec2 p) {
    uvec2 cell = uvec2(p) & uvec2(255u);
    uint value = cell.x * 1664525u ^ cell.y * 1013904223u;
    value ^= value >> 16u; value *= 2246822519u; value ^= value >> 13u;
    return float(value & 65535u) / 65535.0;
}
float materialNoise(vec2 p) {
    ivec2 cell = ivec2(floor(p));
    vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(materialHash(cell), materialHash(cell + ivec2(1, 0)), f.x),
               mix(materialHash(cell + ivec2(0, 1)), materialHash(cell + ivec2(1, 1)), f.x), f.y);
}
void main() {
    float surfaceAltitude = localPosition.y + EyeAltitude;
    if (max(abs(localPosition.x), abs(localPosition.z)) < NearCoverage
            && surfaceAltitude > BandAltitude.x && surfaceAltitude <= BandAltitude.y) { discard; }
    vec3 normal = normalize(localNormal);
    // The native block renderer shades an upward face at full skylight. Match its face weights
    // so a flat beach does not suddenly become dark where the voxel view ends.
    float hostShade = normal.x * normal.x * 0.6 + normal.z * normal.z * 0.8
                    + normal.y * normal.y * (normal.y >= 0.0 ? 1.0 : 0.5);
    float pixelMeters = max(length(dFdx(materialPosition)), length(dFdy(materialPosition)));
    float broad = (materialNoise(materialPosition / 128.0) - 0.5) * (1.0 - smoothstep(64.0, 384.0, pixelMeters));
    float fine = (materialNoise(materialPosition / 16.0) - 0.5) * (1.0 - smoothstep(8.0, 48.0, pixelMeters));
    float land = 1.0 - smoothstep(1.3, 2.0, materialColor.b / max(0.02, materialColor.r));
    float snow = smoothstep(0.65, 0.82, min(materialColor.r, min(materialColor.g, materialColor.b)));
    vec3 albedo = materialColor * (1.0 + land * (1.0 - snow * 0.8) * (broad * 0.25 + fine * 0.12));
    vec3 color = albedo * LightColor * hostShade;
    // A smooth water surface reflects the sky most strongly at grazing angles. Keep illumination tied
    // to the same stellar light as land; this is neither emissive water nor a second exposure clock.
    float fresnel = pow(1.0 - clamp(dot(normal, normalize(-localPosition)), 0.0, 1.0), 5.0);
    color = mix(color, mix(color, HazeColor * 0.8, 0.04 + fresnel * 0.15), 1.0 - land);
    color += materialColor * Flash * 0.25;
    float distance = length(localPosition);
    float haze = 1.0 - exp(-distance / (22000.0 * exp(max(0.0, EyeAltitude) / 8000.0)));
    fragColor = vec4(mix(color, HazeColor, haze), 1.0);
}
