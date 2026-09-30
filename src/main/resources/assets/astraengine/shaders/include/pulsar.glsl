// Bounded artistic neutron-star beams, not an accretion disk or radio simulation.
// Spin is a wrapped CPU presentation phase from the shared occupied catalog clock.
vec3 pulsarBeam(vec3 ray, vec3 center, vec3 axis, float radius, float pixelAngle, float spin) {
    float alignment = dot(ray, axis);
    float denominator = max(0.00001, 1.0 - alignment * alignment);
    float coordinate = (alignment * dot(ray, center) - dot(axis, center)) / denominator;
    coordinate = clamp(coordinate, radius * 1.2, radius * 48.0);
    vec3 point = center + axis * coordinate;
    float along = dot(point, ray);
    if (along <= 0.0) { return vec3(0.0); }
    float distance = length(point - ray * along);
    float lengthRadii = coordinate / max(radius, 1e-20);
    float intrinsicWidth = radius * (0.24 + lengthRadii * 0.042);
    float width = max(intrinsicWidth, pixelAngle * along);
    float resolved = min(1.0, intrinsicWidth / max(width, 1e-20));
    float beam = exp(-pow(distance / max(width, 1e-20), 2.0)) * resolved;
    float envelope = (1.0 - smoothstep(25.0, 48.0, lengthRadii))
            * smoothstep(1.2, 2.3, lengthRadii) / (1.0 + lengthRadii * 0.055);
    float striation = 0.96 + 0.04 * cos(lengthRadii * 0.22 - spin * 2.0);
    return mix(vec3(0.12, 0.35, 1.0), vec3(0.46, 0.82, 1.0), exp(-lengthRadii * 0.12))
            * beam * envelope * striation * 2.2;
}

vec3 pulsarRadiance(vec3 background, vec3 ray, vec3 center, float radius,
                    vec3 tint, float tilt, float spin, float seed, float pixelAngle) {
    float c = cos(tilt);
    float s = sin(tilt);
    vec3 spinAxis = vec3(0.0, c, s);
    vec3 axisZ = vec3(0.0, -s, c);
    float obliquity = 0.38 + fract(seed * 0.173) * 0.56;
    vec3 magnetic = spinAxis * cos(obliquity)
            + (vec3(1, 0, 0) * cos(spin) + axisZ * sin(spin)) * sin(obliquity);
    vec3 color = background + pulsarBeam(ray, center, magnetic, radius, pixelAngle, spin)
            + pulsarBeam(ray, center, -magnetic, radius, pixelAngle, spin);
    float along = dot(ray, center);
    if (along <= 0.0) { return color; }
    vec3 perpendicular = center - ray * along;
    float separation = length(perpendicular);
    float footprint = min(1.0, radius * radius / max(pixelAngle * pixelAngle, 1e-20));
    float pulse = pow(abs(dot(magnetic, -center)), 36.0);
    float coverage = radius < pixelAngle
            ? exp(-separation * separation / max(pixelAngle * pixelAngle, 1e-20)) * footprint
            : 1.0 - smoothstep(radius - pixelAngle, radius + pixelAngle, separation);
    if (coverage > 0.000001) {
        float edge = sqrt(max(0.0, 1.0 - pow(separation / max(radius, 1e-20), 2.0)));
        vec3 surface = mix(tint, vec3(0.76, 0.88, 1.0), 0.48) * (4.0 + pulse * 12.0)
                * (0.65 + edge * 0.35);
        color = mix(color, surface, coverage);
    }
    return color;
}
