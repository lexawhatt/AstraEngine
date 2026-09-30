// Shared atlas geometry is expressed in each galaxy's radius and oriented axes.
// This is bounded artistic radiative transfer, not an N-body or spectral simulation.
bool atlasSegment(vec3 observer, vec3 ray, vec3 extent, out float first, out float last) {
    vec3 scaledRay = ray / extent;
    float scale = length(scaledRay);
    vec3 direction = scaledRay / scale;
    vec3 origin = observer / extent;
    float along = -dot(origin, direction);
    vec3 perpendicular = origin + direction * along;
    float chord2 = 1.0 - dot(perpendicular, perpendicular);
    if (chord2 <= 0.0) { return false; }
    float chord = sqrt(chord2);
    first = max(0.0, (along - chord) / scale);
    last = (along + chord) / scale;
    return last > first;
}

vec3 atlasRay(vec3 worldRay, int galaxy) {
    return vec3(dot(worldRay, GalaxyAxisX[galaxy]), dot(worldRay, GalaxyAxisY[galaxy]),
                dot(worldRay, GalaxyAxisZ[galaxy]));
}

// Footprint filtering remains defined inside divergent volume intersections.
float atlasNoise(vec3 position, float footprint) {
    float a = 1.0 - smoothstep(0.18, 0.85, footprint);
    return mix(0.5, noise(position), a);
}

float atlasCloud(vec3 position, float footprint) {
    float value = atlasNoise(position, footprint) * 0.62;
    if (Detail > 3) { value += atlasNoise(position * 2.13 + vec3(17.2, 9.1, 3.7), footprint * 2.13) * 0.26; }
    else { value += 0.13; }
    if (Detail > 4) { value += atlasNoise(position * 4.57 + vec3(3.1, 23.2, 13.7), footprint * 4.57) * 0.12; }
    else { value += 0.06; }
    return value;
}

// Isotropic stellar kernels avoid interpreting the value-noise interpolation
// lattice as square stars. Unresolved kernels preserve their population average.
float atlasGranules(vec3 position, float footprint) {
    vec3 cell = floor(position);
    vec3 random = hash33(cell + vec3(71.2, 13.9, 3.8));
    vec3 offset = fract(position) - mix(vec3(0.25), vec3(0.75), random);
    float width = sqrt(0.0049 + footprint * footprint * 0.35);
    float kernel = exp(-dot(offset, offset) / (width * width)) * pow(0.07 / width, 3.0);
    float resolved = 1.0 - smoothstep(0.10, 0.24, footprint);
    return mix(0.0019, kernel, resolved);
}

float atlasEnvelope(vec3 point, int galaxy) {
    vec4 shape = GalaxyShape[galaxy];
    float radial = length(point.xz);
    if (shape.z > 0.5 && shape.z < 1.5) {
        float q = length(point / vec3(1.0, 0.55, 0.72));
        return exp(-3.0 * q) * (1.0 - smoothstep(0.8, 1.0, q));
    }
    float height = abs(point.y) / shape.x;
    float disk = exp(-3.0 * radial - height) * (1.0 - smoothstep(0.82, 1.0, radial));
    float core = exp(-length(point) / shape.y);
    if (shape.z < 0.5) {
        float angle = radial > 1e-7 ? atan(point.z, point.x) : 0.0;
        float phase = angle - GalaxyStructure[galaxy].x * log(max(radial, 0.035));
        float arm = pow(0.5 + 0.5 * cos(shape.w * phase), 4.0);
        return clamp(disk * (0.15 + 0.85 * arm) + core * 0.75, 0.0, 1.0);
    }
    float phase = GalaxyObserver[galaxy].w * 0.001533981;
    float lobes = 0.5 + 0.5 * sin(point.x * 8.0 + phase) * cos(point.z * 7.0 - phase * 0.6);
    return clamp(disk * (0.25 + 0.75 * lobes) + core * 0.35, 0.0, 1.0);
}

vec4 atlasMatter(vec3 point, int galaxy, float footprint, bool nucleus) {
    vec4 shape = GalaxyShape[galaxy];
    float seed = GalaxyObserver[galaxy].w;
    vec3 offset = vec3(seed * 0.013, seed * 0.007, 17.2);
    float radius = length(point.xz);
    float core = shape.y;
    if (nucleus) {
        // A compact, irregular stellar population replaces the former smooth Gaussian egg.
        // The central hole itself is a physical body in the nucleus system, not this volume.
        vec3 p = point / core;
        float q2 = dot(p, p);
        float cloud = atlasCloud(p * 4.5 + offset, footprint / core * 4.5);
        float grain = atlasNoise(p * 155.0 + offset, footprint / core * 155.0);
        float density = pow(1.0 + q2 * 3.0, -1.85) * (1.0 - smoothstep(2.1, 3.5, sqrt(q2)));
        float population = atlasGranules(p * 155.0 + offset, footprint / core * 155.0);
        float knots = 0.025 + pow(cloud, 5.0) * 1.3 + population * 165.0;
        float lane = exp(-abs(p.y + (cloud - 0.5) * 0.24) * 15.0)
                   * smoothstep(0.35, 0.64, atlasNoise(p * vec3(6, 13, 6) + offset, footprint / core * 13.0));
        vec3 tint = mix(vec3(1.0, 0.60, 0.29), vec3(0.87, 0.92, 1.0), grain * 0.62);
        return vec4(tint * density * knots * (0.45 / core), lane * density * (9.0 / core));
    }
    float cloud = atlasCloud(point * vec3(28, 70, 28) + offset, footprint * 70.0);
    float fine = atlasNoise(point * 180.0 + offset * 1.7, footprint * 180.0);
    if (shape.z > 0.5 && shape.z < 1.5) {
        float density = atlasEnvelope(point, galaxy);
        float population = 0.22 + cloud * cloud * 1.9 + pow(fine, 13.0) * 15.0;
        return vec4(vec3(1.0, 0.78, 0.48) * density * population * 1.5,
                    density * smoothstep(0.56, 0.75, cloud) * 0.6);
    }
    float edge = 1.0 - smoothstep(0.80, 1.02, radius);
    float height = abs(point.y) / shape.x;
    float radial = exp(-radius * 2.7) * edge;
    float angle = radius > 1e-7 ? atan(point.z, point.x) : 0.0;
    float phase = angle - GalaxyStructure[galaxy].x * log(max(radius, core * 4.0));
    float arm = pow(0.5 + 0.5 * cos(phase * max(2.0, shape.w) + (cloud - 0.5) * 2.3), 4.0);
    arm *= smoothstep(core * 2.0, core * 5.0, radius) * (0.18 + cloud * cloud * 2.7);
    float dustArm = pow(0.5 + 0.5 * cos(phase * max(2.0, shape.w) + 0.27 + (cloud - 0.5) * 1.1), 10.0);
    dustArm *= smoothstep(core * 2.0, core * 5.0, radius);
    if (shape.z > 1.5) {
        arm = pow(cloud, 2.0) * (0.3 + 0.7 * sin(point.x * 8.0 + seed) * sin(point.x * 8.0 + seed));
        dustArm = cloud * cloud;
    }
    float stellar = radial * exp(-height) * (0.12 + arm * 1.9);
    if (shape.z > 1.5) {
        float fragments = exp(-dot(point - vec3(0.29, 0.04, 0.15), point - vec3(0.29, 0.04, 0.15)) / 0.075)
                        + 0.8 * exp(-dot(point - vec3(-0.35, -0.06, 0.20), point - vec3(-0.35, -0.06, 0.20)) / 0.045)
                        + 0.7 * exp(-dot(point - vec3(-0.05, 0.02, -0.35), point - vec3(-0.05, 0.02, -0.35)) / 0.06);
        stellar *= fragments * 4.0 * smoothstep(0.23, 0.60, cloud);
    }
    float dust = radial * exp(-height * 2.0) * (0.2 + dustArm * 2.8)
               * smoothstep(0.24, 0.70, cloud * 0.75 + fine * 0.25);
    vec3 tint = mix(vec3(1.0, 0.80, 0.51), vec3(0.39, 0.61, 1.0), smoothstep(core, 0.35, radius));
    vec3 emission = tint * stellar * (0.13 + cloud * cloud * 2.2 + pow(fine, 14.0) * 6.0) * (0.14 / shape.x);
    emission += vec3(1.0, 0.08, 0.28) * pow(fine, 7.0) * arm * stellar * (0.22 / shape.x);
    return vec4(emission, dust * (1.15 / shape.x));
}

vec4 atlasVolume(vec3 observer, vec3 ray, int galaxy, float pixelAngle, bool nucleus) {
    vec4 shape = GalaxyShape[galaxy];
    vec3 extent = nucleus ? vec3(shape.y * 3.5)
            : shape.z > 0.5 && shape.z < 1.5 ? vec3(1.01, 0.56, 0.73)
            : vec3(1.03, min(1.03, shape.x * 6.0), 1.03);
    float first;
    float last;
    if (!atlasSegment(observer, ray, extent, first, last)) { return vec4(0, 0, 0, 1); }
    int samples = Detail <= 3 ? 12 : Detail == 4 ? 20 : 28;
    if (nucleus) { samples = Detail <= 3 ? 10 : Detail == 4 ? 14 : 18; }
    float stepLength = (last - first) / float(samples);
    vec3 emission = vec3(0);
    float transmission = 1.0;
    for (int i = 0; i < 28; i++) {
        if (i >= samples) { break; }
        float distance = first + (float(i) + 0.5) * stepLength;
        vec4 matter = atlasMatter(observer + ray * distance, galaxy, pixelAngle * distance, nucleus);
        float attenuation = exp(-matter.a * stepLength);
        float integral = matter.a > 1e-5 ? (1.0 - attenuation) / matter.a : stepLength;
        emission += transmission * matter.rgb * integral;
        transmission *= attenuation;
    }
    return vec4(emission, transmission);
}

// Fixed world cells, never screen cells or frame-random seeds. The optical point
// spread fades before reaching a cell's guarded border, so DDA boundaries cannot
// cut a star in half. Unresolved frequencies merge into the continuous population.
vec3 atlasPopulation(vec3 observerCells, vec3 ray, float seed, float pixelAngle, float gain) {
    vec3 cell = floor(observerCells);
    vec3 direction = mix(vec3(-1), vec3(1), step(vec3(0), ray));
    vec3 delta = 1.0 / max(abs(ray), vec3(1e-7));
    vec3 next = (mix(cell, cell + 1.0, step(vec3(0), ray)) - observerCells) / (direction / delta);
    float reach = Detail <= 3 ? 6.0 : Detail == 4 ? 8.0 : 10.0;
    vec3 result = vec3(0);
    for (int i = 0; i < 40; i++) {
        vec3 random = hash33(cell + vec3(seed, seed * 0.71, 193.7));
        vec3 source = cell + mix(vec3(0.24), vec3(0.76), random) - observerCells;
        float along = dot(source, ray);
        if (along > 0.0 && along < reach) {
            vec3 perpendicular = source - ray * along;
            float width = max(0.004, pixelAngle * along * 0.8);
            float optical = 1.0 - smoothstep(0.07, 0.15, width);
            float fade = 1.0 - smoothstep(reach * 0.65, reach, along);
            float signal = exp(-dot(perpendicular, perpendicular) / (width * width));
            float energy = min(1.0, 0.000035 / (width * width));
            vec3 tint = mix(vec3(0.56, 0.72, 1.0), vec3(1.0, 0.75, 0.43), random.z);
            result += tint * signal * energy * optical * fade * (0.25 + random.y * random.y * 8.0)
                    / (1.0 + along * along * 0.025);
        }
        float nearest = min(next.x, min(next.y, next.z));
        if (nearest > reach) { break; }
        vec3 crossed = step(next, vec3(nearest + 1e-6));
        cell += direction * crossed;
        next += delta * crossed;
    }
    return result * gain;
}

vec4 atlasRegionMatter(vec3 point, int kind, vec3 tint, float seed, float footprint) {
    float q = length(point);
    float edge = 1.0 - smoothstep(0.72, 1.0, q);
    vec3 offset = vec3(seed * 0.017, seed * 0.009, 37.1);
    float cloud = atlasCloud(point * 5.0 + offset, footprint * 5.0);
    float fine = atlasNoise(point * 38.0 + offset, footprint * 38.0);
    if (kind == 1 || kind == 2) {
        float irregularEdge = 1.0 - smoothstep(0.28, 0.96, q + (cloud - 0.5) * 0.6);
        float cavity = smoothstep(0.23, 0.53, length(point - vec3(-0.21, 0.22, 0.08)))
                     * smoothstep(0.13, 0.37, length(point - vec3(0.39, -0.24, 0.16)));
        float density = irregularEdge * cavity * smoothstep(0.30, 0.73, cloud) * (0.25 + fine * 1.4);
        float folds = pow(1.0 - abs(cloud - 0.49) * 2.0, 12.0);
        vec3 color = mix(tint * 0.12, vec3(0.09, 0.24, 0.33), smoothstep(0.61, 0.78, cloud));
        color += tint * folds * (0.12 + fine * fine * 1.2);
        return vec4(kind == 2 ? tint * density * 0.006 : color * density * 2.4,
                    density * (kind == 2 ? 7.0 : 2.2));
    }
    if (kind == 6) {
        float axis = abs(point.y);
        float radial = length(point.xz);
        float beamRadius = 0.013 + axis * 0.075;
        float beam = exp(-pow(radial / beamRadius, 2.0)) * (1.0 - smoothstep(0.70, 1.0, axis));
        float knots = 0.50 + pow(cloud, 3.0) * 4.0;
        float lobes = exp(-pow((axis - 0.69) / 0.14, 2.0) - radial * radial / 0.018);
        float nucleus = exp(-q * q / 0.0009);
        return vec4(tint * beam * knots * 4.0 + vec3(0.34, 0.29, 1.0) * lobes * (0.2 + cloud)
                    + vec3(0.79, 0.89, 1.0) * nucleus * 35.0, 0.0);
    }
    float compactness = kind == 3 ? 5.0 : 20.0;
    float density = pow(1.0 + q * q * compactness, -1.5) * edge;
    float grains = atlasGranules(point * 45.0 + offset, footprint * 45.0) * 180.0;
    return vec4(tint * density * (0.08 + cloud * cloud * 0.55 + grains) * 2.4, density * 0.18);
}

// Integrate the transverse Gaussian analytically rather than slicing a narrow
// jet with coarse volume steps. Axial rays use the finite sphere chord limit.
vec3 atlasQuasar(vec3 observer, vec3 ray, vec3 tint, float seed, float first, float last, float pixelAngle) {
    float transverse = dot(ray.xz, ray.xz);
    float closest = transverse > 1e-8 ? -dot(observer.xz, ray.xz) / transverse : (first + last) * 0.5;
    vec3 result = vec3(0);
    for (int side = 0; side < 2; side++) {
        float low = side == 0 ? -0.99 : 0.05;
        float high = side == 0 ? -0.05 : 0.99;
        float enter = first;
        float leave = last;
        if (abs(ray.y) < 1e-7) {
            if (observer.y < low || observer.y > high) { continue; }
        } else {
            float a = (low - observer.y) / ray.y;
            float b = (high - observer.y) / ray.y;
            enter = max(enter, min(a, b));
            leave = min(leave, max(a, b));
        }
        if (leave <= enter) { continue; }
        float distance = clamp(closest, enter, leave);
        vec3 point = observer + ray * distance;
        float axis = abs(point.y);
        float radius = length(point.xz);
        float physicalWidth = 0.003 + axis * 0.035;
        float width = max(physicalWidth, pixelAngle * distance * 0.8);
        float integral = min(leave - enter, 1.77245385 * width / sqrt(max(transverse, 1e-8)));
        // A finite launch cavity separates this parsec-scale optical volume from
        // the physical local accretion disk. Looking from inside the cavity never
        // integrates a backwards segment or an omnidirectional luminous jet base.
        float end = (1.0 - smoothstep(0.75, 0.99, axis));
        float clouds = atlasCloud(vec3(point.y * 8.0, seed * 0.013, 7.3), pixelAngle * distance * 8.0);
        float knots = 0.6 + pow(clouds, 3.0) * 5.0;
        float spine = exp(-radius * radius / (width * width));
        float sheath = exp(-radius * radius / (width * width * 6.0));
        float filtering = physicalWidth * physicalWidth / (width * width);
        result += (tint * spine * 22.0 + vec3(0.22, 0.39, 1.0) * sheath * 2.3)
                * integral * end * knots * filtering;
        vec3 center = vec3(0, side == 0 ? -0.7 : 0.7, 0);
        vec3 relative = center - observer;
        float along = dot(relative, ray);
        if (along > 0.0) {
            vec3 off = relative - ray * along;
            result += vec3(0.37, 0.25, 0.85) * exp(-dot(off, off) / 0.016) * 0.12;
        }
    }
    float observerDistance = length(observer);
    float nucleusVisibility = smoothstep(0.025, 0.08, observerDistance);
    float along = -dot(observer, ray);
    if (along > 0.0 && nucleusVisibility > 0.0) {
        vec3 off = observer + ray * along;
        float nucleusWidth = max(0.007, pixelAngle * along * 0.8);
        result += vec3(0.84, 0.91, 1.0) * exp(-dot(off, off) / (nucleusWidth * nucleusWidth))
                * 1.8 * min(1.0, 0.000049 / (nucleusWidth * nucleusWidth)) * nucleusVisibility;
    }
    return result;
}

vec3 atlasRegion(vec3 background, vec3 worldRay, int index, float pixelAngle) {
    vec4 descriptor = RegionObserver[index];
    vec4 structure = RegionStructure[index];
    vec4 material = RegionColor[index];
    if (material.a <= 0.0) { return background; }
    int galaxy = int(structure.y + 0.5);
    int kind = int(descriptor.w + 0.5);
    vec3 observer = descriptor.xyz;
    vec3 ray = atlasRay(worldRay, galaxy);
    float first;
    float last;
    if (!atlasSegment(observer, ray, vec3(1), first, last)) { return background; }
    if (kind == 6) {
        return background + atlasQuasar(observer, ray, material.rgb, structure.x, first, last, pixelAngle) * material.a;
    }
    if (kind == 5) {
        // Intersect the shell surfaces explicitly; a thin remnant cannot vanish between volume samples.
        float along = -dot(observer, ray);
        vec3 perpendicular = observer + ray * along;
        float chord = sqrt(max(0.0, 1.0 - dot(perpendicular, perpendicular)));
        float shell = 0.0;
        vec3 shellColor = vec3(0);
        for (int side = 0; side < 2; side++) {
            float distance = along + (side == 0 ? -chord : chord);
            if (distance < 0.0) { continue; }
            vec3 point = observer + ray * distance;
            float footprint = pixelAngle * distance;
            float cloud = atlasCloud(point * 9.0 + vec3(structure.x * 0.013), footprint * 9.0);
            float filaments = pow(1.0 - abs(cloud - 0.5) * 2.0, 18.0);
            float fine = atlasNoise(point * 95.0 + vec3(structure.x * 0.021), footprint * 95.0);
            float patches = smoothstep(0.35, 0.65,
                    atlasNoise(point * 3.1 + vec3(structure.x * 0.011), footprint * 3.1));
            float layer = patches * (0.002 + filaments * (0.08 + fine * fine * 1.4))
                        / max(0.18, abs(dot(point, ray)));
            shellColor += mix(material.rgb, vec3(1.0, 0.12, 0.29), smoothstep(0.51, 0.62, cloud)) * layer;
            shell += layer;
        }
        return background * exp(-shell * material.a * 0.035) + shellColor * material.a * 0.28;
    }
    int samples = Detail <= 3 ? 12 : Detail == 4 ? 18 : 24;
    float stepLength = (last - first) / float(samples);
    vec3 emission = vec3(0);
    float transmission = 1.0;
    for (int i = 0; i < 24; i++) {
        if (i >= samples) { break; }
        float distance = first + (float(i) + 0.5) * stepLength;
        vec4 matter = atlasRegionMatter(observer + ray * distance, kind, material.rgb, structure.x,
                                       pixelAngle * distance);
        matter *= material.a;
        float attenuation = exp(-matter.a * stepLength);
        float integral = matter.a > 1e-5 ? (1.0 - attenuation) / matter.a : stepLength;
        emission += transmission * matter.rgb * integral;
        transmission *= attenuation;
    }
    if (kind == 0 || kind == 3 || kind == 4) {
        float local = (1.0 - smoothstep(0.55, 1.15, length(observer))) * material.a;
        emission += atlasPopulation(observer * 18.0, ray, structure.x, pixelAngle, local * 2.0);
    }
    return background * transmission + emission;
}

vec3 galacticSky(vec3 worldRay, float pixelAngle) {
    vec3 color = vec3(0.00001, 0.000015, 0.000025);
    // A faint unresolved extragalactic residue is direction-only; local stars below
    // occupy fixed three-dimensional cells and catalog stars use real CPU descriptors.
    color += stars(worldRay, 370.0, 0.012, 0.07);
    for (int galaxy = 0; galaxy < 9; galaxy++) {
        if (galaxy >= GalaxyCount) { break; }
        vec3 observer = GalaxyObserver[galaxy].xyz;
        vec3 ray = atlasRay(worldRay, galaxy);
        vec4 disk = atlasVolume(observer, ray, galaxy, pixelAngle, false);
        vec4 core = atlasVolume(observer, ray, galaxy, pixelAngle, true);
        float local = atlasEnvelope(observer, galaxy);
        float inside = exp(-abs(observer.y) / max(GalaxyShape[galaxy].x * 2.0, 0.001))
                     * (1.0 - smoothstep(0.8, 1.05, length(observer.xz)));
        float gain = 1.0 / (1.0 + inside * 18.0);
        color = color * disk.a + (disk.rgb + core.rgb * sqrt(disk.a)) * gain;
        if (local > 0.00001) {
            color += atlasPopulation(observer * (GalaxyStructure[galaxy].y / 12.0), ray,
                                     GalaxyObserver[galaxy].w, pixelAngle, sqrt(local) * 0.7);
        }
    }
    for (int region = 0; region < 24; region++) {
        if (region >= RegionCount) { break; }
        color = atlasRegion(color, worldRay, region, pixelAngle);
    }
    for (int star = 0; star < 24; star++) {
        if (star >= CatalogStarCount) { break; }
        vec4 source = CatalogStarDirection[star];
        float along = dot(worldRay, source.xyz);
        if (along <= 0.0) { continue; }
        vec3 offset = source.xyz - worldRay * along;
        float width = max(0.00004, pixelAngle * 0.8);
        color += CatalogStarColor[star] * source.w * exp(-dot(offset, offset) / (width * width));
    }
    return max(color, vec3(0));
}
