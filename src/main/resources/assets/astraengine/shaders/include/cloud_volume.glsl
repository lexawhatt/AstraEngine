// Shared cloud/air transport, in world kilometres. The cloud slab has real height,
// so the same density can be viewed from below, inside, or above. RGB is linear
// premultiplied in-scattering; alpha is transmittance, not opacity.
// Caller provides normalized directions, Detail, Weather, SunDirection, CloudWind,
// and atmosphereTransmission(). Camera XZ may wrap every 64 km without a seam.
const float CLOUD_VOLUME_BASE_KM = 0.36;
const float CLOUD_VOLUME_TOP_KM = 0.86;
const float CLOUD_VOLUME_PERIOD_KM = 64.0;
const float CLOUD_VOLUME_RANGE_KM = 16.0;
const float CLOUD_VOLUME_EXTINCTION = 36.0;

float cloudVolumeHash(vec3 cell) {
    vec3 p = fract(cell * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

float cloudVolumeNoise(vec3 p, float period) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    // Wrap each corner, including the last/first lattice interpolation interval.
    // Wrapping only the observer or the base corner produces a visible seam.
    vec3 a = vec3(mod(cell.x, period), cell.y, mod(cell.z, period));
    vec3 b = vec3(mod(cell.x + 1.0, period), cell.y + 1.0, mod(cell.z + 1.0, period));
    float x00 = mix(cloudVolumeHash(a), cloudVolumeHash(vec3(b.x, a.y, a.z)), f.x);
    float x10 = mix(cloudVolumeHash(vec3(a.x, b.y, a.z)),
                   cloudVolumeHash(vec3(b.x, b.y, a.z)), f.x);
    float x01 = mix(cloudVolumeHash(vec3(a.x, a.y, b.z)),
                   cloudVolumeHash(vec3(b.x, a.y, b.z)), f.x);
    float x11 = mix(cloudVolumeHash(vec3(a.x, b.y, b.z)), cloudVolumeHash(b), f.x);
    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z);
}

float cloudVolumeDensity(vec3 worldKm, float coverage, bool fineDetail) {
    float height = (worldKm.y - CLOUD_VOLUME_BASE_KM) / (CLOUD_VOLUME_TOP_KM - CLOUD_VOLUME_BASE_KM);
    if (height <= 0.0 || height >= 1.0 || coverage <= 0.001) { return 0.0; }
    vec3 p = worldKm;
    p.xz = mod(p.xz + CloudWind * 0.10, CLOUD_VOLUME_PERIOD_KM);
    // Integral frequency ratios keep every octave exactly periodic over 64 km.
    float broad = cloudVolumeNoise(p * vec3(2.0, 2.4, 2.0) + vec3(0.0, 11.7, 0.0), 128.0);
    float billows = cloudVolumeNoise(p * 6.0 + vec3(0.0, 31.1, 0.0), 384.0);
    billows = 1.0 - abs(billows * 2.0 - 1.0);
    float field = broad * 0.70 + billows * 0.30 - 0.055;
    float lowerEnvelope = smoothstep(0.0, 0.13, height);
    float upperEnvelope = 1.0 - smoothstep(0.54, 1.0, height);
    // The rising threshold closes the top into rounded billows rather than a
    // flat lid. Lower edges feather over finite optical thickness.
    float threshold = mix(0.72, 0.22, coverage) + height * height * 0.12;
    float body = max(0.0, field - threshold);
    if (body <= 0.0) { return 0.0; }
    if (fineDetail) {
        float erosion = cloudVolumeNoise(p * 24.0 + vec3(0.0, 71.3, 0.0), 1536.0);
        body = max(0.0, body - (1.0 - erosion) * 0.13);
    }
    float density = 1.0 - exp(-body * 6.0);
    return density * lowerEnvelope * upperEnvelope;
}

vec2 cloudVolumeSlab(vec3 originKm, vec3 ray, float lowKm, float highKm, float limitKm) {
    if (abs(ray.y) < 0.00001) {
        return originKm.y >= lowKm && originKm.y <= highKm ? vec2(0.0, limitKm) : vec2(0.0);
    }
    vec2 distances = (vec2(lowKm, highKm) - originKm.y) / ray.y;
    float enter = max(0.0, min(distances.x, distances.y));
    float leave = min(limitKm, max(distances.x, distances.y));
    return leave > enter ? vec2(enter, leave) : vec2(0.0);
}

float cloudVolumeSunlight(vec3 pointKm, float coverage, int shadowSteps) {
    vec2 slab = cloudVolumeSlab(pointKm, SunDirection, CLOUD_VOLUME_BASE_KM,
                               CLOUD_VOLUME_TOP_KM, CLOUD_VOLUME_RANGE_KM);
    if (slab.y <= slab.x) { return 1.0; }
    slab.y = min(slab.y, slab.x + 3.0);
    int count = clamp(shadowSteps, 1, 6);
    float opticalDepth = 0.0;
    float previousDistance = slab.x;
    for (int i = 0; i < 6; i++) {
        if (i >= count) { break; }
        // More resolution near the scattering point gives soft self-shadowed
        // billows; coarse distant samples cover the remaining light path.
        float fraction = float(i + 1) / float(count);
        float nextDistance = mix(slab.x, slab.y, fraction * fraction);
        float stepKm = nextDistance - previousDistance;
        vec3 sampleKm = pointKm + SunDirection * (previousDistance + stepKm * 0.5);
        opticalDepth += cloudVolumeDensity(sampleKm, coverage, false) * stepKm * CLOUD_VOLUME_EXTINCTION;
        if (opticalDepth > 7.0) { return 0.000912; }
        previousDistance = nextDistance;
    }
    return exp(-opticalDepth);
}

float cloudVolumePhase(float cosine, float anisotropy) {
    float square = anisotropy * anisotropy;
    return (1.0 - square) / (12.5663706 * pow(max(0.025, 1.0 + square - 2.0 * anisotropy * cosine), 1.5));
}

void cloudVolumeSegment(inout vec3 scattering, inout float transmittance,
                        vec3 originKm, vec3 ray, vec2 interval, int count, bool inCloud,
                        float coverage, float aerosol, float incidentRadiance, float moonlight,
                        float shaftStrength, int shadowSteps) {
    if (interval.y <= interval.x || count <= 0) { return; }
    float intervalKm = interval.y - interval.x;
    float cosine = clamp(dot(ray, SunDirection), -1.0, 1.0);
    float cloudPhase = 0.84 * cloudVolumePhase(cosine, 0.64) + 0.16 * cloudVolumePhase(cosine, -0.24);
    float airPhase = cloudVolumePhase(cosine, 0.72);
    float day = smoothstep(-0.09, 0.12, SunDirection.y);
    float stratumPhase = cloudVolumeHash(vec3(gl_FragCoord.xy, 0.314));
    for (int i = 0; i < 64; i++) {
        if (i >= count || transmittance < 0.004) { break; }
        // Deterministic stratification breaks the stacked sampling planes visible
        // at grazing angles. It has no frame counter or history to reset.
        float offset = 0.12 + 0.76 * fract(stratumPhase + float(i) * 0.618033989);
        float lowFraction = float(i) / float(count);
        float highFraction = float(i + 1) / float(count);
        // Resolve the first visible billows most finely. Once a foreground
        // cloud becomes opaque, distant samples can terminate altogether.
        if (inCloud) {
            lowFraction *= lowFraction;
            highFraction *= highFraction;
        }
        float stepKm = (highFraction - lowFraction) * intervalKm;
        vec3 pointKm = originKm + ray * (interval.x + lowFraction * intervalKm + offset * stepKm);
        float density = inCloud ? cloudVolumeDensity(pointKm, coverage, true) : 0.0;
        if (inCloud && density <= 0.0001) { continue; }
        float extinction = inCloud ? density * CLOUD_VOLUME_EXTINCTION
                                  : 0.018 * aerosol * shaftStrength;
        if (extinction <= 0.00001) { continue; }
        vec3 solarTransmission = atmosphereTransmission(SunDirection.y, max(0.05, pointKm.y), aerosol);
        float sunlight = 0.0;
        if (incidentRadiance > 0.00001 && max(solarTransmission.r, max(solarTransmission.g, solarTransmission.b)) > 0.00001) {
            sunlight = cloudVolumeSunlight(pointKm, coverage, shadowSteps);
        }
        vec3 source;
        if (inCloud) {
            float height = clamp((pointKm.y - CLOUD_VOLUME_BASE_KM)
                         / (CLOUD_VOLUME_TOP_KM - CLOUD_VOLUME_BASE_KM), 0.0, 1.0);
            vec3 skyBounce = mix(vec3(0.040, 0.060, 0.092), vec3(0.12, 0.17, 0.25), height);
            skyBounce *= day * incidentRadiance * (1.0 - Weather.x * 0.45);
            vec3 lunarBounce = vec3(0.009, 0.013, 0.022) * moonlight;
            vec3 starlight = vec3(0.00010, 0.00016, 0.00027);
            float powder = 0.50 + 0.50 * (1.0 - exp(-density * 5.0));
            vec3 direct = solarTransmission * incidentRadiance * sunlight * cloudPhase * 7.0 * powder;
            source = skyBounce + lunarBounce + starlight + direct;
        } else {
            // Under-cloud aerosols see the same shadow field as the cloud body.
            // Lit gaps scatter more sunlight than blocked columns: genuine
            // world-space shafts that can end at the geometry's depth.
            source = solarTransmission * incidentRadiance * sunlight * airPhase * 16.0;
        }
        float opacity = 1.0 - exp(-extinction * stepKm);
        scattering += transmittance * opacity * source;
        transmittance *= 1.0 - opacity;
    }
}

vec4 cloudTransport(vec3 originKm, vec3 ray, float maxDistanceKm, float coverage,
                    float aerosol, float incidentRadiance, float moonlight, float shaftStrength,
                    int viewSteps, int shadowSteps) {
    coverage = clamp(coverage, 0.0, 1.0);
    float limitKm = clamp(maxDistanceKm, 0.0, CLOUD_VOLUME_RANGE_KM);
    if (coverage <= 0.001 || limitKm <= 0.00001) { return vec4(0.0, 0.0, 0.0, 1.0); }
    aerosol = clamp(aerosol, 0.05, 4.0);
    incidentRadiance = clamp(incidentRadiance, 0.0, 2.0);
    moonlight = clamp(moonlight, 0.0, 2.0);
    // This optional solar-shaft medium fades with available sunlight. It must
    // not become a dark screen filter when the star is gone or below the horizon.
    shaftStrength = clamp(shaftStrength, 0.0, 2.0)
                  * smoothstep(-0.035, 0.02, SunDirection.y) * min(incidentRadiance, 1.0);
    int budget = clamp(viewSteps, 1, 64);
    vec2 cloud = cloudVolumeSlab(originKm, ray, CLOUD_VOLUME_BASE_KM, CLOUD_VOLUME_TOP_KM, limitKm);
    vec2 air = cloudVolumeSlab(originKm, ray, -0.064, CLOUD_VOLUME_BASE_KM, limitKm);
    bool hasCloud = cloud.y > cloud.x;
    bool hasAirInterval = air.y > air.x;
    bool hasAir = hasAirInterval && shaftStrength > 0.001;
    // Disabling shafts leaves the cloud sample locations unchanged.
    int airCount = hasAirInterval ? (hasCloud ? budget / 3 : budget) : 0;
    int cloudCount = hasCloud ? max(1, budget - airCount) : 0;
    // Keep cloud strata invariant while short world-depth paths use fewer air
    // samples. A nearby wall does not need the entire long-range view budget.
    airCount = min(airCount, max(2, int(ceil((air.y - air.x) / 0.06))));
    vec3 scattering = vec3(0.0);
    float transmittance = 1.0;
    if (hasAir && (!hasCloud || air.x <= cloud.x)) {
        cloudVolumeSegment(scattering, transmittance, originKm, ray, air, airCount, false,
                           coverage, aerosol, incidentRadiance, moonlight, shaftStrength, shadowSteps);
    }
    if (hasCloud) {
        cloudVolumeSegment(scattering, transmittance, originKm, ray, cloud, cloudCount, true,
                           coverage, aerosol, incidentRadiance, moonlight, shaftStrength, shadowSteps);
    }
    if (hasAir && hasCloud && air.x > cloud.x) {
        cloudVolumeSegment(scattering, transmittance, originKm, ray, air, airCount, false,
                           coverage, aerosol, incidentRadiance, moonlight, shaftStrength, shadowSteps);
    }
    return vec4(max(scattering, vec3(0.0)), clamp(transmittance, 0.0, 1.0));
}
