// A hue-preserving highlight shoulder followed by the host framebuffer's display encoding.
// Mapping by peak channel preserves emission colors instead of whitening each channel separately.
vec3 celestialDisplay(vec3 radiance, float exposure) {
    vec3 value = clamp(radiance * exposure, vec3(0.0), vec3(60000.0));
    float peak = max(value.r, max(value.g, value.b));
    return pow(value / (1.0 + peak), vec3(1.0 / 2.2));
}

// The atmosphere/fog inputs are host display colors. Decode the same shoulder so
// exposure=1 reproduces their colors and blends the horizon before final composition.
vec3 celestialRadiance(vec3 displayColor) {
    vec3 value = pow(clamp(displayColor, vec3(0.0), vec3(0.995)), vec3(2.2));
    return value / max(0.001, 1.0 - max(value.r, max(value.g, value.b)));
}
