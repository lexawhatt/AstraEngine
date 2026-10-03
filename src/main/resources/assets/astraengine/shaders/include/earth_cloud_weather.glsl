// Regional weather and vertically integrated optical mass for the canonical Earth field.
// Mirrored by EarthCloudWeather: all fields are body-fixed, with no longitude texture seam or host-chart state.
// Expected nonlinear coverage over a finite field footprint. The integral keeps subpixel gaps
// partially transparent instead of thresholding averaged noise into either holes or a solid blanket.
vec3 earthCloudRampIntegral(vec3 value) {
    vec3 x = clamp(value, 0.0, 1.0);
    return x * x * x * (1.0 - x * 0.5) + max(vec3(0.0), value - 1.0);
}
float earthCloudNoiseVariance(float footprint) {
    float resolved = 1.0 - smoothstep(0.25, 1.0, footprint);
    // The first term accounts for unresolved noise; the second integrates variation within a resolved cell.
    return 0.0344 * min(1.0, 1.0 - resolved * resolved + footprint * footprint / 12.0);
}
vec3 earthCloudFilteredMass(vec3 region, float field, float variance) {
    vec3 threshold = vec3(0.58) - region * 0.20;
    float spread = sqrt(max(0.0, 3.0 * variance));
    if (spread < 0.00001) { return region * smoothstep(threshold - 0.12, threshold + 0.12, vec3(field)); }
    vec3 start = (vec3(field - spread) - threshold + 0.12) / 0.24;
    vec3 finish = (vec3(field + spread) - threshold + 0.12) / 0.24;
    return region * clamp((earthCloudRampIntegral(finish) - earthCloudRampIntegral(start))
            / (2.0 * spread / 0.24), 0.0, 1.0);
}

// Mesoscale200/60 km flow structure and90 km body-space breakup survive orbital pixels.
// Storm callers supply spiral-aligned coordinates, so these are frontal filaments rather than an orbit overlay.
vec3 earthCloudTexturedMass(vec3 pointKm, vec3 bodyPointKm, vec3 region, float footprintKm, float bodyFootprintKm) {
    if (max(region.x, max(region.y, region.z)) < 0.00001) { return region; }
    float broad = cloudResolvedNoise(pointKm * 0.005 + vec3(3, 23, 11), footprintKm * 0.005);
    float warp = cloudResolvedNoise(pointKm * 0.005 + vec3(37, 7, 29), footprintKm * 0.005);
    vec3 p = pointKm + vec3(broad - 0.5, warp - 0.5, broad - warp) * 80.0;
    float middle = cloudResolvedNoise(p / 60.0 + vec3(13, 43, 5), footprintKm / 60.0);
    float cellular = cloudResolvedNoise(bodyPointKm / 90.0 + vec3(31, 17, 47), bodyFootprintKm / 90.0);
    float field = broad * 0.30 + warp * 0.15 + middle * 0.25 + cellular * 0.30;
    float variance = (0.30 * 0.30 + 0.15 * 0.15) * earthCloudNoiseVariance(footprintKm * 0.005)
            + 0.25 * 0.25 * earthCloudNoiseVariance(footprintKm / 60.0)
            + 0.30 * 0.30 * earthCloudNoiseVariance(bodyFootprintKm / 90.0);
    return earthCloudFilteredMass(region, field, variance);
}

// A geometrically absent component cannot affect the other independent cloud types. Keep the
// original helper's tiny-mass threshold based on the UNMASKED region: masking before that test
// would turn a small filtered active component into a different, unfiltered value.
vec3 earthCloudActiveMass(vec3 pointKm, vec3 bodyPointKm, vec3 region, float footprintKm,
                         float bodyFootprintKm, vec3 activeTypes) {
    vec3 active = region * activeTypes;
    if (max(active.x, max(active.y, active.z)) == 0.0) { return vec3(0.0); }
    return earthCloudTexturedMass(pointKm, bodyPointKm, region, footprintKm, bodyFootprintKm) * activeTypes;
}

// One regularized logarithmic phase for arms and flow. The smooth core has no angular branch cut;
// outside it theta=pitch*log(r/reference)+constant describes an expanding spiral, not equal-spaced rings.
vec3 earthCloudSpiral(vec2 q, float pitch) {
    float squared = dot(q, q);
    float twist = -0.5 * pitch * log((squared + 0.0144) / 0.3744);
    float c = cos(twist), s = sin(twist);
    float radial = squared / (sqrt(squared + 0.0144) + 0.12);
    return vec3(c * q.x - s * q.y, s * q.x + c * q.y, radial);
}

float earthCloudSpiralArm(vec3 spiral, float radius, float pitch, float offset) {
    float along = cos(offset) * spiral.x - sin(offset) * spiral.y;
    float shear = pitch * radius * radius / (radius * radius + 0.0144);
    return sqrt(max(0.0, 0.5 * radius * (radius - along)) / (1.0 + shear * shear));
}

// Rotation plus bounded radial shear has determinant1. Its exact planar singular value and
// the third coordinate's derivative give a conservative footprint, finite at every radius.
float earthCloudFlowFootprint(float radius, float pitch) {
    float shear = abs(pitch) * radius * radius / (radius * radius + 0.0144);
    float singular = (sqrt(shear * shear + 4.0) + shear) * 0.5;
    return sqrt(0.65 * 0.65 * singular * singular + 0.35 * 0.35);
}

vec3 earthCloudSystem(vec3 n, vec3 center, float radiusKm, float rotation, float strength, bool tropical,
                     vec2 regionalWeather, float footprintKm, vec3 activeTypes) {
    float facing = dot(n, center);
    if (facing < 0.82) { return vec3(0); }
    vec3 east = normalize(vec3(-center.z, 0, center.x));
    vec3 north = cross(east, center);
    vec2 q = vec2(dot(n, east), dot(n, north)) / facing * (6371.0 / radiusKm);
    float c = cos(rotation), s = sin(rotation);
    q = vec2(c * q.x - s * q.y, (s * q.x + c * q.y) * sign(center.y));
    q += (regionalWeather - 0.5) * 0.13;
    float r = length(q);
    if (r > 1.8) { return vec3(0); }
    // Above the low decks only frontal convection can remain. Its exact support is the tail
    // at q.x>0.8; reject its zero factors before logarithmic arms and mesoscale noise are evaluated.
    if (!tropical && activeTypes.x == 0.0 && activeTypes.y == 0.0
            && (q.x <= 0.8 || q.x >= 1.65 || abs(q.y + 0.38 + q.x * q.x * 0.35) >= 0.17)) {
        return vec3(0.0);
    }
    float pitch = tropical ? 2.4 : 1.8;
    vec3 spiral = earthCloudSpiral(q, pitch);
    vec3 flow = spiral * vec3(0.65, 0.65, 0.35) * radiusKm;
    float armDistance = earthCloudSpiralArm(spiral, r, pitch, 0.0);
    float flowFootprint = footprintKm * earthCloudFlowFootprint(r, pitch) / (facing * facing);
    if (tropical) {
        float arm = 1.0 - smoothstep(0.055, 0.20, armDistance);
        float eye = smoothstep(0.045, 0.11, r);
        float envelope = 1.0 - smoothstep(0.4, 1.35, r);
        float mass = eye * envelope * max(arm, (1.0 - smoothstep(0.20, 0.45, r)) * 0.75) * strength;
        return earthCloudActiveMass(flow, n * 6371.0, mass * vec3(0.25, 0.6, 1.0),
                flowFootprint, footprintKm, activeTypes);
    }
    float arm = (1.0 - smoothstep(0.055, 0.16, armDistance))
            * smoothstep(0.12, 0.27, r) * (1.0 - smoothstep(0.85, 1.35, r)) * smoothstep(-0.5, 0.15, q.y + q.x * 0.65);
    float head = (1.0 - smoothstep(0.40, 0.77, length(q * vec2(0.9, 1.2))))
            * (0.3 + 0.7 * smoothstep(-0.45, 0.10, q.y + q.x * 0.65));
    float tail = (1.0 - smoothstep(0.075, 0.17, abs(q.y + 0.38 + q.x * q.x * 0.35)))
            * smoothstep(-0.15, 0.18, q.x) * (1.0 - smoothstep(1.15, 1.65, q.x));
    float dry = (1.0 - smoothstep(0.045, 0.12, earthCloudSpiralArm(spiral, r, pitch, 0.75)))
            * smoothstep(0.20, 0.45, r) * (1.0 - smoothstep(0.8, 1.4, r));
    float sheet = max(head, max(arm * 0.85, tail * 0.55)) * (1.0 - dry * 0.85);
    return earthCloudActiveMass(flow, n * 6371.0,
            vec3(sheet, max(tail, arm * 0.45), tail * smoothstep(0.8, 1.4, q.x) * 0.22) * strength,
            flowFootprint, footprintKm, activeTypes);
}

vec3 earthCloudRegion(vec3 pointKm, float footprintKm, vec3 activeTypes) {
    vec3 n = normalize(pointKm + vec3(CloudWind.x, 0, CloudWind.y) * 10.0);
    float longitude = atan(n.z, n.x);
    float regional = cloudResolvedNoise(n * 7.0 + vec3(17, 3, 29), footprintKm * 7.0 / CloudPlanet.w);
    float broken = cloudResolvedNoise(n * 19.0 + vec3(41, 13, 7), footprintKm * 19.0 / CloudPlanet.w);
    float wet = (earthCloudCoverage(pointKm) - 0.45) * 0.65 + EarthCloudParams.z * 0.2;
    float tropicalCenter = EarthCloudParams.y * 0.075 + sin(longitude * 3.0 + 0.8) * 0.045
            + sin(longitude * 5.0 - 0.7) * 0.035;
    float convergence = 1.0 - smoothstep(0.050, 0.140 + wet * 0.04, abs(n.y - tropicalCenter));
    float tropical = convergence * smoothstep(0.49 - wet, 0.78 - wet, regional);
    float middle = smoothstep(0.25, 0.40, abs(n.y)) * (1.0 - smoothstep(0.68, 0.85, abs(n.y)));
    vec3 region = vec3(middle * smoothstep(0.60 - wet, 0.84 - wet, regional) * 0.35,
            tropical * (0.30 + 0.40 * broken), tropical * smoothstep(0.40, 0.76, broken) * 0.75);
    region = earthCloudActiveMass(pointKm + vec3(CloudWind.x, 0, CloudWind.y) * 10.0,
            pointKm + vec3(CloudWind.x, 0, CloudWind.y) * 10.0, region, footprintKm, footprintKm, activeTypes);
    region = max(region, earthCloudSystem(n, vec3(0.590807258, 0.743144825, -0.314137791), 2100.0, -0.30, 1.00, false, vec2(regional, broken), footprintKm, activeTypes));
    region = max(region, earthCloudSystem(n, vec3(0.450266299, -0.681998360, 0.576314582), 1900.0, 1.10, 0.90, false, vec2(regional, broken), footprintKm, activeTypes));
    region = max(region, earthCloudSystem(n, vec3(-0.293892626, 0.809016994, 0.509036960), 2300.0, 0.80, 0.85, false, vec2(regional, broken), footprintKm, activeTypes));
    region = max(region, earthCloudSystem(n, vec3(-0.430108863, -0.766044443, -0.477684286), 1800.0, -0.70, 0.90, false, vec2(regional, broken), footprintKm, activeTypes));
    region = max(region, earthCloudSystem(n, vec3(-0.262002630, 0.642787610, -0.719846310), 1500.0, 2.20, 0.70, false, vec2(regional, broken), footprintKm, activeTypes));
    region = max(region, earthCloudSystem(n, vec3(-0.859521021, 0.292371705, 0.419216413), 800.0, 0.40, 0.80, true, vec2(regional, broken), footprintKm, activeTypes));
    return clamp(region * (0.65 + earthCloudCoverage(pointKm) * 0.65) * (0.8 + broken * 0.4), 0.0, 1.0);
}

vec3 earthCloudRegion(vec3 pointKm, float footprintKm) {
    return earthCloudRegion(pointKm, footprintKm, vec3(1.0));
}

float earthCloudSmoothIntegral(float x) {
    if (x <= 0.0) { return 0.0; }
    if (x >= 1.0) { return x - 0.5; }
    return x * x * x * (1.0 - x * 0.5);
}
float earthCloudProfileIntegral(float altitude, float base, float top) {
    altitude = clamp(altitude, base, top);
    float span = top - base;
    return earthCloudSmoothIntegral((altitude - base) / (span * 0.15)) * (span * 0.15)
            - earthCloudSmoothIntegral((altitude - base - span * 0.70) / (span * 0.30)) * (span * 0.30);
}
float earthCloudProfile(float base, float top, vec2 heights) {
    if (heights.y <= base || heights.x >= top) { return 0.0; }
    float span = top - base;
    if (heights.y - heights.x < 0.00001) {
        return smoothstep(base, base + span * 0.15, heights.x) - smoothstep(base + span * 0.70, top, heights.x);
    }
    return clamp((earthCloudProfileIntegral(heights.y, base, top)
            - earthCloudProfileIntegral(heights.x, base, top)) / (heights.y - heights.x), 0.0, 1.0);
}

// Exact finite segment radial bounds, including its closest point. Grazing steps cannot skip a thin deck.
vec2 earthCloudStepHeights(vec3 origin, vec3 ray, float distance, float stepKm) {
    float start = max(0.0, distance - stepKm * 0.5), finish = distance + stepKm * 0.5;
    float closest = clamp(-dot(origin, ray), start, finish);
    float minimum = length(origin + ray * closest) - CloudPlanet.w;
    float maximum = max(length(origin + ray * start), length(origin + ray * finish)) - CloudPlanet.w;
    return vec2(minimum, maximum);
}

float earthCloudDensity(vec3 pointKm, vec3 ray, float stepKm, float pixelFootprintKm, float weatherFootprintKm,
                       vec2 heights, bool fineDetail, out vec3 lightingMass, out vec3 lightingTops,
                       out vec3 occupiedSupport) {
    lightingMass = vec3(0.0);
    lightingTops = vec3(1.25, 2.4, 4.8);
    occupiedSupport = vec3(0.0);
    if (EarthCloudParams.x == 0.0) { return 0.0; }
    vec3 profiles = vec3(earthCloudProfile(0.85, 1.85, heights), earthCloudProfile(1.3, 4.2, heights),
            earthCloudProfile(1.4, 8.5, heights));
    if (max(profiles.x, max(profiles.y, profiles.z)) <= 0.0) { return 0.0; }
    // Only hard support exclusion is allowed. Do not prune small positive profiles or reuse another
    // point's weather: tangential cells can cross different decks and actual frontal boundaries.
    vec3 activeTypes = vec3(heights.y > 0.85 && heights.x < 1.85 ? 1.0 : 0.0,
            heights.y > 1.3 && heights.x < 4.2 ? 1.0 : 0.0,
            heights.y > 1.4 && heights.x < 8.5 ? 1.0 : 0.0);
    vec3 region = earthCloudRegion(pointKm, weatherFootprintKm, activeTypes);
    vec3 tops = vec3(1.25, 2.4, 4.8) + vec3(0.6, 1.8, 3.7)
            * smoothstep(vec3(0.02), vec3(0.6, 0.7, 0.7), region);
    profiles = vec3(earthCloudProfile(0.85, tops.x, heights), earthCloudProfile(1.3, tops.y, heights),
            earthCloudProfile(1.4, tops.z, heights));
    region *= profiles;
    if (max(region.x, max(region.y, region.z)) <= 0.00001) { return 0.0; }
    occupiedSupport = earthCloudOccupiedSupport(pointKm, ray, stepKm * 0.5, region, tops);
    if (occupiedSupport.z <= 0.0) { return 0.0; }
    // Detail belongs to the actual occupied deck, not the empty portion of the 8.5 km global shell cell.
    // The regional field and exact vertical profile integral above are still evaluated once per cell.
    float footprintKm = max(pixelFootprintKm, occupiedSupport.z * 0.5);
    float detailOffset = earthCloudOccupiedPoint(pointKm, ray, stepKm * 0.5, region, tops,
            (occupiedSupport.x + occupiedSupport.y) * 0.5);
    vec3 p = pointKm + ray * detailOffset + vec3(CloudWind.x, 0, CloudWind.y) * 10.0;
    float resolution = 1.0 - smoothstep(0.25, 0.90, footprintKm * 0.18);
    // Mean optical mass of the resolved shared atlas/body remap, measured over131072 deterministic samples.
    float broad = 0.5, body = 0.5143;
    if (resolution > 0.0) {
        broad = cloudResolvedNoise(p * 0.18 + vec3(0, 11.7, 0), footprintKm * 0.18);
        float billowResolution = 1.0 - smoothstep(0.25, 0.90, footprintKm * 0.75);
        float billow = 0.70;
        if (billowResolution > 0.0) {
            billow = mix(0.70, 1.0 - abs(cloudVolumeNoise(p * 0.75 + vec3(0, 31.1, 0)) * 2.0 - 1.0), billowResolution);
        }
        float detailed = smoothstep(0.30, 0.72, broad * 0.72 + billow * 0.28);
        float erosion = fineDetail ? cloudResolvedNoise(p * 3.0 + vec3(0, 71.3, 0), footprintKm * 3.0) : 0.5;
        detailed *= 0.72 + erosion * 0.28;
        // Filter optical mass after the nonlinear billow evaluation, never threshold a mean noise value.
        body = mix(0.5143, detailed, resolution);
    }
    vec3 mass = region * vec3(0.075 * (0.65 + broad * 0.70), 0.18 * body, 0.14 * body);
    // Reuse these evaluated components when lighting the visible optical centroid; no second weather lookup.
    lightingMass = mass;
    lightingTops = tops;
    return mass.x + mass.y + mass.z;
}
