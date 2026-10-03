// Earth materials inherit Minecraft display-color textures. Convert their response through the
// same bounded inverse display shoulder before incident light is applied. This is a renderer-relative
// material calibration, not physical reflectance; the opaque host world remains an LDR path.
vec3 earthMaterialResponse(vec3 displayColor) {
    vec3 value = pow(clamp(displayColor, vec3(0.0), vec3(0.995)), vec3(2.2));
    return min(value / max(0.001, 1.0 - max(value.r, max(value.g, value.b))), vec3(32.0));
}

// Neutral water/ice particles use the same material-white reference as a bright Earth surface.
// It belongs to the scattering coefficients, before the shared stellar source, never to exposure.
vec3 earthCloudMaterialResponse() { return earthMaterialResponse(vec3(0.90)); }
