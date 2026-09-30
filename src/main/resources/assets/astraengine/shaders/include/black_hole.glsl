// Bounded analytic Schwarzschild-inspired presentation, not a null-geodesic integrator.
// R is the descriptor's physical horizon radius. The capture curve uses bcrit=3sqrt(3)M,
// R=2M; the emissive disk begins at the Schwarzschild ISCO=6M=3R.
// Disk images below sample one material via a finite-source thin-lens mapping, rather
// than painting an unrelated arc. See https://arxiv.org/html/1906.00873v2 .

vec3 safeUnit(vec3 value, vec3 fallback) {
    float squared = dot(value, value);
    return squared > 1e-18 ? value * inversesqrt(squared) : fallback;
}

vec3 perpendicularAxis(vec3 axis) {
    vec3 reference = abs(axis.y) < 0.8 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    return safeUnit(cross(axis, reference), vec3(0, 0, 1));
}

float captureAngle(float radius) {
    if (radius >= 1.0) { return PI; }
    float critical = clamp(2.598076211 * radius * sqrt(max(0.0, 1.0 - radius)), 0.0, 1.0);
    float angle = asin(critical);
    // Static observers between the horizon and photon sphere see the complementary cone.
    return radius > 0.666666667 ? PI - angle : angle;
}

float lensSupport(float radius) {
    return min(PI, max(radius * 18.0, captureAngle(radius) * 1.4));
}

vec3 gravitationalRay(vec3 ray, vec3 center, float radius, float pixelAngle) {
    float along = clamp(dot(ray, center), -1.0, 1.0);
    vec3 perpendicular = ray - center * along;
    float sine = length(perpendicular);
    float theta = atan(sine, along);
    float support = lensSupport(radius);
    if (theta >= support || radius >= 1.0) { return ray; }
    vec3 radial = safeUnit(perpendicular, perpendicularAxis(center));
    float cutoff = 1.0 - smoothstep(support * 0.60, support, theta);
    float impact = max(sine, max(radius * 2.6, pixelAngle));
    // Weak-field 2R/b shape, bounded before the strong-field capture curve. The small
    // log term is artistic compression of the rapid change close to that curve.
    float nearCurve = max(theta - captureAngle(radius), pixelAngle);
    float bend = 2.0 * radius / impact + 0.075 * log(1.0 + radius / nearCurve);
    bend = min(bend, 1.7) * cutoff;
    float sourceAngle = theta - bend;
    return safeUnit(center * cos(sourceAngle) + radial * sin(sourceAngle), ray);
}

vec3 diskNormal(float tilt) {
    return vec3(0.0, cos(tilt), sin(tilt));
}

// Source-plane footprint filtering is explicit because these functions run inside
// divergent ray/disk intersections. Unresolved noise converges to its mean instead
// of contributing unstable high-frequency samples to a magnified lens image.
float accretionNoise(vec3 point, float footprint) {
    float result = 0.0;
    float weight = 0.52;
    for (int octave = 0; octave < 5; octave++) {
        if (octave >= Detail) { break; }
        float visibility = 1.0 - smoothstep(0.18, 0.75, footprint);
        float sampleValue = 0.5;
        if (visibility > 0.0) { sampleValue = mix(0.5, noise(point), visibility); }
        result += sampleValue * weight;
        point = mat3(0.0, 0.8, 0.6, -0.8, 0.36, -0.48, -0.6, -0.48, 0.64)
              * point * 2.07 + vec3(13.1, 7.7, 19.2);
        footprint *= 2.07;
        weight *= 0.48;
    }
    return result;
}

float resolvedBand(float phase, float frequency, float footprint) {
    // Frequencies approaching one cycle in two source-plane pixels lose contrast;
    // frequencies beyond that limit disappear instead of folding into a moire beat.
    return sin(phase) * (1.0 - smoothstep(1.0, 2.0, frequency * footprint));
}

vec4 accretionMaterial(vec3 local, vec3 normal, vec3 observer, float seed, float footprint) {
    float r = length(local);
    float edge = clamp(footprint, 0.012, 1.0);
    float coverage = smoothstep(3.0 - edge, 3.12 + edge, r)
                   * (1.0 - smoothstep(7.2 - edge, 11.0 + edge, r));
    if (coverage <= 0.0) { return vec4(0.0); }
    vec3 axisX = perpendicularAxis(normal);
    vec3 axisY = cross(normal, axisX);
    float angle = atan(dot(local, axisY), dot(local, axisX));
    float rotation = Time * 0.11 / max(pow(r / 3.0, 1.5), 1.0);
    vec3 flow = vec3(cos(angle - rotation), sin(angle - rotation), r * 0.35);
    float turbulence = accretionNoise(flow * 7.0 + vec3(seed * 0.017), footprint * 7.0);
    float bands = 0.56 + resolvedBand(r * 9.0 + turbulence * 2.0, 9.0, footprint) * 0.22
                 + resolvedBand(r * 22.0 + turbulence * 3.0, 22.0, footprint) * 0.22
                 + resolvedBand(r * 71.0 - angle * 2.0, 71.0, footprint) * 0.12;
    float filaments = 0.30 + 1.1 * smoothstep(0.25, 0.72, turbulence);
    float heat = pow(3.0 / max(r, 3.0), 2.3);
    // Orbital velocity is defined in the disk plane, so asymmetric brightness follows
    // orientation instead of being attached to the screen's left or right side.
    vec3 velocity = safeUnit(cross(normal, local), axisX);
    float approaching = dot(velocity, observer);
    float doppler = clamp(pow(1.0 / max(0.62, 1.0 - approaching * 0.32), 3.0), 0.40, 2.45);
    vec3 spectrum = mix(vec3(1.0, 0.42, 0.10), vec3(1.0, 0.93, 0.81), smoothstep(0.10, 0.60, heat));
    spectrum = mix(spectrum, vec3(1.0, 0.95, 0.82), max(approaching, 0.0) * heat * 0.30);
    float radiance = (0.015 + heat * heat * 10.0) * bands * filaments * doppler;
    return vec4(spectrum * radiance, coverage * 0.98);
}

vec4 directAccretion(vec3 ray, vec3 center, float radius, vec3 normal, float seed,
                     float pixelAngle, float farSuppression, float darkness) {
    float denominator = dot(ray, normal);
    float planeOffset = dot(center, normal);
    float footprint = pixelAngle / max(radius * max(abs(denominator), 0.09), 1e-12);
    vec4 material = vec4(0.0);
    if (abs(denominator) > 0.000001) {
        float distance = planeOffset / denominator;
        if (distance > 0.0) {
            vec3 local = (ray * distance - center) / max(radius, 1e-12);
            material = accretionMaterial(local, normal, -center, seed, footprint);
            float farWeight = smoothstep(0.0, 0.6, dot(local, center));
            material.a *= 1.0 - farWeight * max(farSuppression, darkness);
        }
    }
    // A disk of finite thickness still has a front face when the observer lies in
    // its midplane. Blend that small slab continuously, rather than special-casing
    // one exactly parallel ray and leaving a black row on either side.
    float height = max(radius * 0.028, pixelAngle * 0.65);
    float slabWeight = 1.0 - smoothstep(height, height * 3.0, abs(planeOffset));
    if (slabWeight > 0.0) {
        float closest = dot(ray, center);
        vec3 projected = ray * closest - center;
        float planeHeight = dot(projected, normal);
        float coverage = 1.0 - smoothstep(height * 0.2, height, abs(planeHeight));
        if (closest > 0.0 && coverage > 0.0) {
            float x = length(projected - normal * planeHeight) / max(radius, 1e-12);
            float distance = closest - radius * sqrt(max(0.0, 4.1 * 4.1 - x * x));
            vec3 local = (ray * distance - center) / max(radius, 1e-12);
            local -= normal * dot(local, normal);
            vec4 slab = accretionMaterial(local, normal, -center, seed, pixelAngle / max(radius, 1e-12));
            slab.a *= coverage;
            material = mix(material, slab, slabWeight);
        }
    }
    return material;
}

vec4 lensedAccretion(vec3 ray, vec3 center, float radius, vec3 normal, float seed,
                     float pixelAngle) {
    vec3 axisX = safeUnit(cross(normal, center), perpendicularAxis(center));
    vec3 axisY = cross(normal, axisX);
    if (dot(axisY, center) < 0.0) { axisY = -axisY; }
    vec3 screenY = cross(center, axisX);
    float along = clamp(dot(ray, center), -1.0, 1.0);
    vec3 radial = ray - center * along;
    float theta = atan(length(radial), along);
    if (along <= 0.0) { return vec4(0.0); }
    vec2 q = vec2(dot(radial, axisX), dot(radial, screenY))
           * (theta / max(length(radial), 1e-8)) / max(radius, 1e-12);
    float qSquared = max(dot(q, q), 0.0001);
    float projected = dot(axisY, screenY);
    float depth = max(dot(axisY, center), 0.0);
    float inclinationFade = smoothstep(0.12, 0.45, depth);
    if (inclinationFade <= 0.0) { return vec4(0.0); }
    // beta = theta - 2*R*D_ls*theta/(D_l*theta^2), with D_ls given by
    // the same tilted disk plane. Solving for source-plane v yields both the
    // upright outer image and inverted lower image without hardcoded arcs.
    float denominator = projected + 2.0 * depth * q.y / qSquared;
    if (abs(denominator) < 0.00001) { return vec4(0.0); }
    float v = q.y / denominator;
    if (v <= 0.0 || v > 12.0) { return vec4(0.0); }
    float u = q.x * (1.0 - 2.0 * v * depth / qSquared);
    vec3 local = axisX * u + axisY * v;
    float footprint = pixelAngle / max(radius, 1e-12)
                    * min(12.0, 1.0 + 1.0 / max(abs(denominator), 0.08));
    vec4 material = accretionMaterial(local, normal, -center, seed, footprint);
    material.a *= inclinationFade;
    material.rgb *= 0.82;
    return material;
}

vec3 blackHoleRadiance(vec3 background, vec3 ray, int index, float pixelAngle) {
    vec3 center = BodyDirectionRadius[index].xyz;
    float radius = BodyDirectionRadius[index].w;
    if (radius >= 1.0) { return vec3(0.0); }
    float along = clamp(dot(ray, center), -1.0, 1.0);
    float theta = atan(length(ray - center * along), along);
    if (theta > lensSupport(radius)) { return background; }
    float shadow = captureAngle(radius);
    float darkness = 1.0 - smoothstep(shadow - pixelAngle, shadow + pixelAngle, theta);
    vec3 color = mix(background, vec3(0.0), darkness);
    if (along <= 0.0 || radius < pixelAngle * 0.01) { return color; }
    vec3 normal = index == NucleusBodyIndex ? safeUnit(NucleusAxis, vec3(0, 1, 0))
            : diskNormal(BodyLightTilt[index].w);
    float seed = BodySurface[index].x;
    // A near-horizon observer cannot see an external disk in the captured directions.
    float diskVisibility = 1.0 - smoothstep(0.45, 0.85, radius);
    vec4 secondary = lensedAccretion(ray, center, radius, normal, seed, pixelAngle);
    secondary.a *= (1.0 - darkness) * diskVisibility;
    color = mix(color, secondary.rgb, secondary.a);
    float farSuppression = smoothstep(0.12, 0.45, length(cross(normal, center)));
    vec4 direct = directAccretion(ray, center, radius, normal, seed, pixelAngle, farSuppression, darkness);
    direct.a *= diskVisibility;
    color = mix(color, direct.rgb, direct.a);
    return color;
}
