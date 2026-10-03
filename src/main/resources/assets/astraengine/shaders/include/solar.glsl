// Shared solar presentation. Caller supplies noise(), fbm(), Detail, Time, Evolution and
// SolarLight. All distances below are angular; canonical descriptors stay intact.
// Evolution = depletion, collapse progress, explosion age seconds (-1 before), remnant.
// SolarLight = relative illumination, one-shot flash, envelope radius scale, reserved.

// Shell samples share a three-dimensional comoving field. There is no
// azimuth lookup seam or animation clock: expansion carries the same knots outwards.
vec3 ejectaFilaments(vec3 position, vec3 offset, float footprint, float cooling, float layer) {
    vec3 direction = position / max(length(position), 0.0001);
    float clumps = noise(position * 3.6 + offset);
    float flow = noise(direction * 6.0 + position * 5.0 + offset + clumps * 1.8);
    float detail = noise(position * 29.0 + offset + flow * 2.3);
    float resolved = 1.0 / (1.0 + footprint * 29.0);
    detail = mix(0.5, detail, resolved);
    float thread = pow(max(0.0, 1.0 - abs(detail * 2.0 - 1.0)), 8.0)
                 * smoothstep(0.54, 0.73, flow);
    float knots = pow(max(0.0, clumps * 0.55 + flow * 0.3 + detail * 0.15 - 0.33), 2.0) * 5.0;
    float r = length(position);
    float outer = exp(-pow((r - (0.84 + (clumps - 0.5) * 0.34)) / 0.14, 2.0));
    float inner = exp(-pow((r - 0.49) / 0.23, 2.0)) * 0.38;
    float density = (outer + inner) * (0.018 + thread * 0.50 + knots)
                  * (1.0 - smoothstep(0.92, 1.20, r));
    vec3 hot = mix(vec3(1.0, 0.23, 0.045), vec3(1.0, 0.76, 0.37), layer * 0.55 + knots * 0.35);
    vec3 ions = mix(vec3(0.82, 0.12, 0.15), vec3(0.10, 0.40, 0.76),
                    smoothstep(0.40, 0.64, clumps + layer * 0.05));
    ions += vec3(0.17, 0.20, 0.25) * knots;
    return mix(hot, ions, cooling) * density;
}

// Short emissive integral through finite ellipsoidal bounds, without a secondary
// light march or a physical hydrodynamic simulation. Both gas layers share the field.
vec3 stellarEjectaRadiance(vec3 ray, vec3 center, float angularRadius, float elapsed,
                          float seed, float pixelAngle) {
    float along = dot(ray, center);
    if (along <= 0.0) { return vec3(0.0); }
    vec3 transverse = ray - center * along;
    float separation = length(transverse);
    float angle = atan(separation, along);
    float radius = max(angularRadius, 1e-8);
    if (angle > radius * 1.65 + pixelAngle * 2.0) { return vec3(0.0); }
    vec3 projected = transverse * (angle / max(separation, 1e-8)) / radius;
    vec3 axis = normalize(vec3(sin(seed * 0.73 + 0.6), 0.43, cos(seed * 0.91 + 1.2)));
    // A mildly elongated, seeded volume gives an identifiable remnant from different views.
    vec3 point = projected - axis * dot(projected, axis) * 0.26;
    vec3 sight = center - axis * dot(center, axis) * 0.26;
    vec3 offset = vec3(seed * 0.013, seed * 0.027 + 7.4, seed * 0.019 + 19.1);
    float a = max(dot(sight, sight), 0.1);
    float b = dot(point, sight);
    float closestSquared = max(0.0, dot(point, point) - b * b / a);
    float footprint = pixelAngle / radius;
    float cooling = smoothstep(0.8, 11.0, elapsed);
    vec3 gas = vec3(0.0);
    float halfDepth = sqrt(max(0.0, 1.44 - closestSquared) / a);
    int samples = Detail <= 3 ? 6 : (Detail >= 5 ? 10 : 8);
    float stepLength = halfDepth * 2.0 / float(samples);
    if (halfDepth <= 0.00001) { return gas; }
    vec3 middle = point - sight * (b / a);
    for (int sampleIndex = 0; sampleIndex < 10; sampleIndex++) {
        if (sampleIndex >= samples) { break; }
        float distance = -halfDepth + (float(sampleIndex) + 0.5) * stepLength;
        vec3 samplePosition = middle + sight * distance;
        gas += ejectaFilaments(samplePosition, offset, footprint, cooling,
                              1.0 - smoothstep(0.2, 1.0, length(samplePosition))) * stepLength;
    }
    float ignition = smoothstep(0.04, 0.65, elapsed);
    // Finite cooling follows only the authoritative phase age, meeting the mature remnant
    // continuously at sixteen seconds. It does not pulse or restart with shader Time.
    // A modest visual lift for gas filaments only. Flash energy, exposure and
    // incident sky/cloud illumination retain their separately authored bounds.
    return gas * ignition * (0.30 + 4.0 * exp(-elapsed * 0.18)) * 1.22;
}

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
    float emitted = clamp(SolarLight.x / 0.035, 0.0, 1.0);
    color += stellarEjectaRadiance(ray, center, shellRadius, elapsed, seed, pixelAngle) * emitted;
    float core = exp(-pow(angle / max(physicalRadius * 0.35, pixelAngle), 2.0));
    color += vec3(0.42, 0.72, 1.0) * core * (0.35 + exp(-elapsed * 0.9) * 8.0) * emitted;
    // Exactly one server-aged flash: finite, spatially bounded, and never replayed by Time.
    float flashWidth = max(physicalRadius * 12.0, 0.045);
    // Optical wings fall off from the source. A constant hemispherical pedestal would
    // wash every unobscured background pixel brown, independently of its angular distance.
    float flashCore = 20.0 * exp(-pow(angle / flashWidth, 2.0));
    float flashWing = 0.065 * exp(-pow(angle / (flashWidth * 4.0), 2.0));
    color += vec3(1.0, 0.86, 0.68) * SolarLight.y
           * (flashWing + flashCore);
    // The directional cutoff must not expose a bright hemispherical seam when the
    // source is nearly behind the camera, particularly inside a large flash envelope.
    return mix(background, color, smoothstep(0.0, 0.15, along));
}
