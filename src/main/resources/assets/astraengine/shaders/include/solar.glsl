// Shared solar presentation. Caller supplies noise(), fbm(), Time, Evolution and
// SolarLight. All distances below are angular; canonical descriptors stay intact.
// Evolution = depletion, collapse progress, explosion age seconds (-1 before), remnant.
// SolarLight = relative illumination, one-shot flash, envelope radius scale, reserved.

vec3 evolvingSolarRadiance(vec3 background, vec3 ray, vec3 center, float physicalRadius,
                          vec3 stellarColor, float seed, float pixelAngle) {
    float along = dot(ray, center);
    if (along <= 0.0) { return background; }
    vec3 perpendicular = center - ray * along;
    float separation = length(perpendicular);
    float angle = atan(separation, along);
    float depletion = clamp(Evolution.x, 0.0, 1.0);
    float collapse = clamp(Evolution.y, 0.0, 1.0);
    float age = Evolution.z;
    float remnant = clamp(Evolution.w, 0.0, 1.0);
    bool erupted = age >= 0.0 || remnant > 0.5;
    // Keep the dramatic envelope finite for observers already near the Sun.
    // Healthy radiusScale=1 preserves the exact original angular radius.
    float radius = min(physicalRadius * SolarLight.z, max(physicalRadius, 0.80));
    radius = max(radius, 1e-12);
    float critical = smoothstep(0.68, 0.98, depletion) * (1.0 - collapse);
    float pulse = 1.0 + critical * 0.065 * sin(Time * 3.0 + seed);
    float coolingColor = smoothstep(0.08, 0.60, depletion);
    // Channel attenuation preserves a warm spectrum after display compression;
    // linear mixing with white otherwise makes the distended star look like stone.
    vec3 hotColor = stellarColor * exp(-vec3(0.0, 2.25, 5.8) * coolingColor);
    hotColor = mix(hotColor, vec3(0.46, 0.73, 1.0), collapse * 0.9);
    vec3 color = background;

    if (!erupted) {
        float ratio = separation / radius;
        float footprint = min(1.0, radius * radius / max(pixelAngle * pixelAngle, 1e-20));
        float beyond = max(0.0, ratio - 1.0);
        float corona = exp(-beyond * 14.0) * 0.10 + exp(-beyond * 4.5) * 0.013;
        if (ratio > 1.0 && ratio < 3.8) {
            vec3 radial = normalize(ray - center * along);
            float streamers = fbm(radial * 8.0 + vec3(seed * 0.01));
            corona += pow(streamers, 3.0) * exp(-beyond * 5.0) * 0.25;
            // Slow, irregular bright arcs become stronger as the envelope destabilizes.
            float arcHeight = 1.04 + 0.16 * noise(radial * 4.0 + vec3(Time * 0.026));
            float arc = exp(-pow((ratio - arcHeight) / (0.017 + critical * 0.014), 2.0));
            float activeRegions = smoothstep(0.57, 0.75, streamers);
            corona += arc * activeRegions * (0.12 + depletion * 0.72);
        }
        color += mix(hotColor, vec3(1.0, 0.55, 0.12), (1.0 - collapse) * 0.25)
               * corona * footprint * pulse * (0.4 + SolarLight.x * 0.6) * 5.0;
        float discriminant = radius * radius - dot(perpendicular, perpendicular);
        float coverage = radius < pixelAngle
                ? exp(-separation * separation / max(pixelAngle * pixelAngle, 1e-20)) * footprint
                : 1.0 - smoothstep(radius - pixelAngle, radius + pixelAngle, separation);
        if (coverage > 0.00001) {
            // The reconstruction kernel preserves subpixel flux, not an inflated geometric disc.
            vec3 normal = normalize(-perpendicular - ray * sqrt(max(discriminant, 0.0)) + vec3(1e-20));
            float grain = fbm(normal * 95.0 + vec3(Time * 0.004));
            float granulation = smoothstep(0.25, 0.71, grain);
            float cells = fbm(normal * 18.0 + vec3(Time * 0.0008));
            float activity = fbm(normal * 7.0 + vec3(seed * 0.071));
            float spots = (1.0 - smoothstep(0.215, 0.29, activity)) * 0.62;
            float limb = 0.28 + 0.72 * pow(max(dot(normal, -ray), 0.0), 0.45);
            vec3 photosphere = mix(hotColor, vec3(1.0, 0.93, 0.80), (1.0 - coolingColor) * 0.18)
                    * (1.02 + granulation * 0.65 + cells * 0.23) * limb * (1.0 - spots);
            photosphere *= pulse * (0.36 + SolarLight.x * 0.64) * (1.0 + collapse * 0.8) * 6.0;
            color = mix(color, photosphere, coverage);
        }
        return color;
    }

    float elapsed = age >= 0.0 ? clamp(age, 0.0, 16.0) : 16.0;
    float progress = elapsed / 16.0;
    // Expansion is deliberately time-compressed fiction. A shared physical-radius
    // multiple gives a larger angular event near the Sun without unbounded inputs.
    float shellRadius = min(physicalRadius * (1.0 + 102.0 * sqrt(progress)), 0.85);
    float width = max(pixelAngle * 1.5, shellRadius * (0.09 + progress * 0.075));
    vec3 field = (ray - center * along) / max(shellRadius, 1e-8) * 5.0;
    float turbulent = fbm(field * 2.2 + vec3(seed * 0.013));
    float shellShape = shellRadius * (0.86 + turbulent * 0.27);
    float shell = exp(-pow((angle - shellShape) / width, 2.0));
    float ridges = pow(1.0 - abs(fbm(field * 9.0 + vec3(13.1)) - 0.49) * 2.0, 6.0);
    float haze = exp(-pow(angle / max(shellRadius, 1e-8), 2.0) * 1.8);
    float cooling = smoothstep(0.6, 9.0, elapsed);
    vec3 gas = mix(vec3(1.5, 0.45, 0.055),
                   mix(vec3(0.018, 0.42, 0.95), vec3(1.0, 0.032, 0.18),
                       smoothstep(0.34, 0.66, turbulent)), cooling);
    float ignition = smoothstep(0.04, 0.65, elapsed);
    float shock = exp(-pow((angle - shellRadius * 1.11) / max(width * 0.16, pixelAngle), 2.0));
    float shockFade = (1.0 - smoothstep(5.0, 15.5, elapsed)) * ignition;
    color += gas * shell * (0.15 + ridges * 0.85) * ignition * 2.5;
    color += gas * haze * turbulent * 0.09 * ignition;
    color += vec3(0.28, 0.58, 1.0) * shock * shockFade * 2.2;
    float core = exp(-pow(angle / max(physicalRadius * 0.35, pixelAngle), 2.0));
    color += vec3(0.42, 0.72, 1.0) * core * (0.9 + exp(-elapsed * 0.9) * 8.0);
    // Exactly one server-aged flash: finite, spatially bounded, and never replayed by Time.
    float flashWidth = max(physicalRadius * 12.0, 0.045);
    color += vec3(1.0, 0.86, 0.68) * SolarLight.y
           * (0.065 + 20.0 * exp(-pow(angle / flashWidth, 2.0)));
    return color;
}
