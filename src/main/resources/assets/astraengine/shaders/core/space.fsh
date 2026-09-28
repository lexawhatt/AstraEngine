#version 150

uniform mat4 InverseViewProjection;
uniform float Time;
uniform float Seed;
uniform float Temperature;
uniform float Resource;
uniform int Stage;
uniform int Planets;
uniform float Transit;
uniform float TravelProgress;
uniform vec3 SunDirection;
uniform float Daylight;
uniform int Planetary;
uniform int Rings;
uniform float RingTilt;

in vec2 clipPosition;
out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(cell), hash(cell + vec3(1,0,0)), f.x),
                   mix(hash(cell + vec3(0,1,0)), hash(cell + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash(cell + vec3(0,0,1)), hash(cell + vec3(1,0,1)), f.x),
                   mix(hash(cell + vec3(0,1,1)), hash(cell + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(p);
        p = p * 2.03 + 7.1;
        amplitude *= 0.5;
    }
    return value;
}

vec3 stars(vec3 ray) {
    // A directional lattice follows camera rotation but not the player's block position.
    vec3 color = vec3(0.001, 0.002, 0.004);
    for (int layer = 0; layer < 3; layer++) {
        float scale = 110.0 + float(layer) * 73.0;
        vec3 grid = ray * scale;
        vec3 cell = floor(grid);
        float h = hash(cell + Seed + float(layer) * 31.7);
        vec3 offset = vec3(hash(cell + 1.7), hash(cell + 2.3), hash(cell + 4.1));
        float distance = length(fract(grid) - offset);
        float size = mix(0.018, 0.09, h * h);
        float point = 1.0 - smoothstep(0.0, size, distance);
        point *= step(0.75, h) * mix(0.4, 2.1, h);
        color += point * mix(vec3(0.52, 0.71, 1.0), vec3(1.0, 0.83, 0.62), hash(cell + 9.2));
    }
    return color;
}

float sphereHit(vec3 ray, vec3 center, float radius) {
    float along = dot(ray, center);
    float discriminant = along * along - dot(center, center) + radius * radius;
    if (discriminant < 0.0 || along < 0.0) { return -1.0; }
    return along - sqrt(discriminant);
}

vec3 starColor() {
    float cool = smoothstep(3500.0, 9000.0, Temperature);
    return mix(vec3(1.0, 0.36, 0.065), vec3(0.48, 0.72, 1.0), cool);
}

void main() {
    vec4 view = InverseViewProjection * vec4(clipPosition, 1.0, 1.0);
    vec3 ray = normalize(view.xyz / view.w);
    vec3 stellarDirection = normalize(SunDirection);
    float warp = sin(TravelProgress * 3.14159265) * Transit;
    vec3 backgroundRay = normalize(ray + stellarDirection * warp * 0.75);
    float angle = acos(clamp(dot(ray, stellarDirection), -1.0, 1.0));
    vec3 color;
    vec3 center = stellarDirection * 9.0;

    if (Stage == 2) {
        float deflection = 0.015 / max(angle, 0.055);
        // Artistic lensing of procedural background only; foreground voxels remain untouched.
        backgroundRay = normalize(backgroundRay + (backgroundRay - stellarDirection) * deflection);
    }
    color = stars(backgroundRay);
    float nebula = fbm(backgroundRay * 3.0 + Seed * 0.003);
    float band = exp(-abs(backgroundRay.y + 0.2 * backgroundRay.x) * 16.0);
    color += vec3(0.011, 0.009, 0.021) * band * pow(nebula, 2.0);

    if (Planetary == 1) {
        float horizon = exp(-abs(ray.y) * 4.0);
        float twilight = exp(-abs(stellarDirection.y) * 9.0);
        vec3 atmosphere = mix(vec3(0.025, 0.09, 0.23), vec3(0.26, 0.49, 0.73), horizon) * Daylight;
        float towardSun = pow(max(dot(normalize(vec3(ray.x, 0, ray.z)),
                                      normalize(vec3(stellarDirection.x, 0, stellarDirection.z))), 0.0), 4.0);
        atmosphere += vec3(0.95, 0.2, 0.045) * twilight * horizon * (0.15 + towardSun * 0.85);
        color = color * (1.0 - Daylight * 0.95) + atmosphere;
    }
    float bodyHit = sphereHit(ray, center, Stage == 2 ? 0.88 : 1.25);
    if (Stage != 2) {
        vec3 temperatureColor = starColor();
        float edge = max(angle - 0.138, 0.0);
        color += temperatureColor * exp(-edge * 34.0) * 0.16 * step(0.13, angle);
        if (bodyHit > 0.0) {
            vec3 normal = normalize(ray * bodyHit - center);
            float cells = fbm(normal * 13.0 + vec3(Time * 0.06, Seed * 0.001, 0));
            float fine = noise(normal * 53.0 - Time * 0.08);
            float limb = pow(max(dot(normal, -ray), 0.0), 0.35);
            float instability = (1.0 - Resource) * 0.25 * sin(Time * 3.0 + cells * 18.0);
            color = temperatureColor * (0.5 + cells * 1.0 + fine * 0.18 + instability) * (0.45 + limb * 0.8);
        }
    } else {
        if (bodyHit > 0.0) { color = vec3(0); }
        float ring = exp(-pow((angle - 0.101) / 0.004, 2.0));
        color += vec3(1.0, 0.69, 0.27) * ring * 0.75;
        vec3 normal = normalize(vec3(0.05, 0.94, 0.34));
        float denominator = dot(ray, normal);
        if (abs(denominator) > 0.0001) {
            float distance = dot(center, normal) / denominator;
            vec3 hit = ray * distance - center;
            float radius = length(hit);
            if (distance > 0.0 && radius > 1.1 && radius < 3.0 && (bodyHit < 0.0 || distance < bodyHit)) {
                float polar = atan(hit.z, hit.x);
                float streaks = noise(vec3(radius * 26.0, polar * 7.0 - Time * 0.4, Seed * 0.01));
                float falloff = (1.0 - smoothstep(1.2, 3.0, radius)) * smoothstep(1.1, 1.35, radius);
                vec3 disk = mix(vec3(1.0, 0.19, 0.025), vec3(1.0, 0.91, 0.66), falloff);
                color += disk * falloff * (0.35 + streaks * 1.2);
            }
        }
    }

    // Analytic spheres in the sky shader: planets allocate no entities or chunks.
    for (int i = 0; i < 4; i++) {
        if (i >= Planets) { break; }
        float index = float(i);
        vec3 planetCenter = normalize(vec3(-0.62 + index * 0.48, -0.16 - index * 0.13,
                                          0.9 - index * 0.15)) * (5.0 + index);
        float radius = 0.43 - index * 0.06;
        float hit = sphereHit(ray, planetCenter, radius);
        if (hit > 0.0) {
            vec3 normal = normalize(ray * hit - planetCenter);
            float continents = fbm(normal * 4.0 + vec3(Seed * 0.007 + index * 30.0, Time * 0.006, 0));
            vec3 land = mix(vec3(0.12, 0.06, 0.035), vec3(0.36, 0.27, 0.13), continents);
            vec3 surface = mix(vec3(0.012, 0.065, 0.12), land, smoothstep(0.45, 0.51, continents));
            float light = max(dot(normal, normalize(center - planetCenter)), 0.0);
            float atmosphere = pow(1.0 - max(dot(normal, -ray), 0.0), 3.0);
            color = surface * (0.045 + light * 1.4) + vec3(0.12, 0.38, 0.58) * atmosphere * light * 0.5;
        }
    }

    if (Rings == 1) {
        vec3 planetCenter = normalize(vec3(-0.42, 0.26, 1.0)) * 8.0;
        float radius = 0.94;
        float planetHit = sphereHit(ray, planetCenter, radius);
        if (planetHit > 0.0) {
            vec3 normal = normalize(ray * planetHit - planetCenter);
            float bands = noise(vec3(normal.y * 19.0, Seed * 0.001, 1.0));
            vec3 surface = mix(vec3(0.22, 0.105, 0.055), vec3(0.77, 0.51, 0.29), bands);
            float diffuse = max(dot(normal, stellarDirection), 0.0);
            color = surface * (0.045 + diffuse * 1.5);
            color += vec3(0.08, 0.17, 0.25) * pow(1.0 - max(dot(normal, -ray), 0.0), 3.0) * diffuse;
        }
        vec3 ringNormal = normalize(vec3(0.16, RingTilt, 0.7));
        float denominator = dot(ray, ringNormal);
        if (abs(denominator) > 0.00001) {
            float distance = dot(planetCenter, ringNormal) / denominator;
            vec3 relative = ray * distance - planetCenter;
            float ringRadius = length(relative);
            if (distance > 0.0 && ringRadius > 1.25 && ringRadius < 2.4
                    && (planetHit < 0.0 || distance < planetHit)) {
                // Ray from ring toward the sun: sphere intersection casts the planet's own shadow.
                float along = dot(-relative, stellarDirection);
                float discriminant = along * along - dot(relative, relative) + radius * radius;
                float visibility = (along > 0.0 && discriminant > 0.0) ? 0.035 : 1.0;
                float bands = 0.5 + 0.5 * noise(vec3(ringRadius * 130.0, Seed, 0));
                float gap = 1.0 - smoothstep(0.025, 0.07, abs(ringRadius - 1.86));
                float edge = smoothstep(1.25, 1.32, ringRadius) * (1.0 - smoothstep(2.3, 2.4, ringRadius));
                float diffuse = 0.18 + 0.82 * abs(dot(ringNormal, stellarDirection));
                vec3 ringColor = mix(vec3(0.36, 0.23, 0.13), vec3(0.94, 0.78, 0.51), bands);
                color = mix(color, ringColor * diffuse * visibility, edge * (1.0 - gap * 0.82));
            }
        }
    }
    if (Planetary == 1) {
        // Atmospheric ground haze below the horizon; only the sky is affected.
        color *= mix(0.12, 1.0, smoothstep(-0.15, 0.02, ray.y));
    }

    if (Transit > 0.5) {
        color *= 0.2 + 0.8 * smoothstep(0.6, 1.0, TravelProgress);
    }
    color = vec3(1.0) - exp(-color * 1.15);
    fragColor = vec4(pow(max(color, vec3(0)), vec3(0.8)), 1.0);
}
