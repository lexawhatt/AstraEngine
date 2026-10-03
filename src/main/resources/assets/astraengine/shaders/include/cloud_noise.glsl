// One shared padded RG8 lookup supplies cloud body, shadow and shaft density.
#ifndef CLOUD_NOISE_EXTERNAL_SAMPLER
uniform sampler2D CloudNoise;
#endif
uniform vec4 CloudNoiseLayout; // period, padded tile edge, atlas edge, tiles per row
float cloudVolumeNoise(vec3 p) {
    vec3 cell = mod(floor(p), CloudNoiseLayout.x);
    vec3 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    vec2 tile = vec2(mod(cell.z, CloudNoiseLayout.w), floor(cell.z / CloudNoiseLayout.w));
    vec2 uv = (tile * CloudNoiseLayout.y + cell.xy + vec2(1.5) + f.xy) / CloudNoiseLayout.z;
    vec2 slices = texture(CloudNoise, uv).rg;
    return mix(slices.r, slices.g, f.z);
}
