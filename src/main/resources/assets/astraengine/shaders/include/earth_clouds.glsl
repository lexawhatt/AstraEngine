// Canonical Earth only. Body-fixed kilometers, the same density for ground, orbit and solar shadows.
// Caller imports cloud_density and earth_optics, and provides CloudWind.
uniform vec4 EarthCloudParams; // coverage override (-1 auto), seasonal sine, rain, relative stellar source
uniform vec4 CloudPlanet; // body-fixed observer kilometers, sea-level radius kilometers; zero disables
uniform mat3 CloudLocalToBody;
uniform vec3 EarthCloudSun;

float earthCloudCoverage(vec3 pointKm) {
    if (EarthCloudParams.x >= 0.0) { return EarthCloudParams.x; }
    float latitude = asin(clamp(normalize(pointKm).y, -1.0, 1.0));
    float warmth = 0.5 + 0.5 * EarthCloudParams.y * clamp(latitude / 0.523598776, -1.0, 1.0);
    return clamp(0.38 + (1.0 - warmth) * 0.09 + EarthCloudParams.z * 0.48, 0.0, 1.0);
}

vec2 earthCloudSphere(vec3 origin, vec3 ray, float radius) {
    float along = dot(origin, ray);
    vec3 closest = origin - ray * along;
    float closestRadius = length(closest);
    float determinant = (radius - closestRadius) * (radius + closestRadius);
    if (determinant <= 0.0) { return vec2(1.0, -1.0); }
    float root = sqrt(determinant);
    return vec2(-along - root, -along + root);
}

// Intersections relative to the finite ray-cell midpoint. A shell can contribute two disjoint intervals.
// Empty pairs have start > end; callers never turn the clear interior of a shell into occupied cloud.
vec4 earthCloudLayerIntervals(vec3 pointKm, vec3 ray, float halfStepKm, float baseKm, float topKm) {
    vec2 outer = earthCloudSphere(pointKm, ray, CloudPlanet.w + topKm);
    float start = max(-halfStepKm, outer.x), finish = min(halfStepKm, outer.y);
    if (finish <= start) { return vec4(1, -1, 1, -1); }
    vec2 inner = earthCloudSphere(pointKm, ray, CloudPlanet.w + baseKm);
    if (inner.y <= inner.x || inner.y <= start || inner.x >= finish) {
        return vec4(start, finish, 1, -1);
    }
    return vec4(start, min(finish, inner.x), max(start, inner.y), finish);
}

vec3 earthCloudOccupiedSupport(vec3 pointKm, vec3 ray, float halfStepKm, vec3 mass, vec3 tops) {
    vec3 bounds = vec3(halfStepKm, -halfStepKm, 0.0);
    vec3 bases = vec3(0.85, 1.3, 1.4);
    for (int i = 0; i < 3; i++) {
        if (mass[i] <= 0.000001) { continue; }
        vec4 intervals = earthCloudLayerIntervals(pointKm, ray, halfStepKm, bases[i], tops[i]);
        for (int j = 0; j < 2; j++) {
            vec2 interval = j == 0 ? intervals.xy : intervals.zw;
            if (interval.y <= interval.x) { continue; }
            bounds.x = min(bounds.x, interval.x);
            bounds.y = max(bounds.y, interval.y);
            bounds.z = max(bounds.z, interval.y - interval.x);
        }
    }
    return bounds;
}

float earthCloudOccupiedPoint(vec3 pointKm, vec3 ray, float halfStepKm, vec3 mass, vec3 tops, float desiredKm) {
    float nearest = desiredKm, error = 1e20;
    vec3 bases = vec3(0.85, 1.3, 1.4);
    for (int i = 0; i < 3; i++) {
        if (mass[i] <= 0.000001) { continue; }
        vec4 intervals = earthCloudLayerIntervals(pointKm, ray, halfStepKm, bases[i], tops[i]);
        for (int j = 0; j < 2; j++) {
            vec2 interval = j == 0 ? intervals.xy : intervals.zw;
            if (interval.y <= interval.x) { continue; }
            float inset = min(0.0001, (interval.y - interval.x) * 0.001);
            float point = clamp(desiredKm, interval.x + inset, interval.y - inset);
            float delta = abs(point - desiredKm);
            if (delta < error) { nearest = point; error = delta; }
        }
    }
    return nearest;
}

#moj_import <astraengine:earth_cloud_weather.glsl>
#moj_import <astraengine:earth_material.glsl>

vec2 earthCloudInterval(vec3 origin, vec3 ray, float limitKm) {
    vec2 outer = earthCloudSphere(origin, ray, CloudPlanet.w + CloudLayer.y);
    float start = max(0.0, outer.x), finish = min(limitKm, outer.y);
    vec2 solid = earthCloudSphere(origin, ray, CloudPlanet.w);
    if (solid.y > solid.x && solid.x >= 0.0) { finish = min(finish, solid.x); }
    vec2 inner = earthCloudSphere(origin, ray, CloudPlanet.w + CloudLayer.x);
    if (inner.y > inner.x) {
        if (length(origin) < CloudPlanet.w + CloudLayer.x) { start = max(start, inner.y); }
        else if (inner.x > start) { finish = min(finish, inner.x); }
    }
    return finish > start ? vec2(start, finish) : vec2(0.0);
}

// The view can meet both sides of a cloud shell without hitting the planet. Keep the whole visible
// outer range here; the strata below explicitly exclude its clear interior. Solar-shadow budgets
// and their existing interval remain independent of this view quadrature.
vec2 earthCloudViewRange(vec3 origin, vec3 ray, float limitKm) {
    vec2 outer = earthCloudSphere(origin, ray, CloudPlanet.w + CloudLayer.y);
    float start = max(0.0, outer.x), finish = min(limitKm, outer.y);
    vec2 solid = earthCloudSphere(origin, ray, CloudPlanet.w);
    if (solid.y > solid.x && solid.x >= 0.0) { finish = min(finish, solid.x); }
    return finish > start ? vec2(start, finish) : vec2(0.0);
}

// At most two intervals per height stratum. Every live interval gets one cell, then largest
// remainders distribute the remaining fixed budget. A radial ray receives6/4/2 cells at budget12:
// thin low decks no longer share only one cell with several kilometers of empty upper atmosphere.
int earthCloudViewIntervals(vec3 originKm, vec3 ray, float limitKm, int budget,
                           out vec2 intervals[6], out int intervalSteps[6]) {
    float weights[6];
    for (int i = 0; i < 6; i++) {
        intervals[i] = vec2(0.0); intervalSteps[i] = 0; weights[i] = 0.0;
    }
    vec2 range = earthCloudViewRange(originKm, ray, limitKm);
    if (range.y <= range.x) { return 0; }
    vec3 bases = vec3(0.85, 1.85, 4.2), tops = vec3(1.85, 4.2, 8.5);
    vec3 priorities = vec3(6.0, 4.0, 2.0);
    int size = 0;
    float totalWeight = 0.0;
    for (int layer = 0; layer < 3; layer++) {
        vec2 outer = earthCloudSphere(originKm, ray, CloudPlanet.w + tops[layer]);
        float start = max(range.x, outer.x), finish = min(range.y, outer.y);
        if (finish <= start) { continue; }
        vec2 inner = earthCloudSphere(originKm, ray, CloudPlanet.w + bases[layer]);
        vec4 pieces = vec4(start, finish, 0.0, 0.0);
        if (inner.y > inner.x && inner.y > start && inner.x < finish) {
            pieces = vec4(start, min(finish, inner.x), max(start, inner.y), finish);
        }
        for (int side = 0; side < 2; side++) {
            vec2 piece = side == 0 ? pieces.xy : pieces.zw;
            if (piece.y <= piece.x) { continue; }
            float weight = (piece.y - piece.x) * priorities[layer] / (tops[layer] - bases[layer]);
            // Insertion keeps front/back and above/below observers in true ray order.
            int slot = size;
            for (int j = 5; j > 0; j--) {
                if (j > size || j != slot) { continue; }
                if (intervals[j - 1].x <= piece.x) { break; }
                intervals[j] = intervals[j - 1]; weights[j] = weights[j - 1]; slot--;
            }
            intervals[slot] = piece; weights[slot] = weight;
            totalWeight += weight; size++;
        }
    }
    if (size == 0) { return 0; }
    int remaining = max(0, budget - size), assigned = size;
    for (int i = 0; i < 6; i++) {
        if (i >= size) { break; }
        float quota = float(remaining) * weights[i] / totalWeight;
        int cells = int(floor(quota));
        intervalSteps[i] = 1 + cells; assigned += cells; weights[i] = quota - float(cells);
    }
    for (int extra = 0; extra < 6; extra++) {
        if (assigned >= budget) { break; }
        int winner = 0;
        for (int i = 1; i < 6; i++) {
            if (i < size && weights[i] > weights[winner]) { winner = i; }
        }
        intervalSteps[winner]++; weights[winner] = -1.0; assigned++;
    }
    return size;
}

float earthCloudShadowFiltered(vec3 pointKm, vec3 sunlight, int count, float footprintKm) {
    if (CloudPlanet.w <= 0.0 || EarthCloudParams.x == 0.0) { return 1.0; }
    vec2 segment = earthCloudInterval(pointKm, sunlight, 800.0);
    if (segment.y <= segment.x) { return 1.0; }
    float opticalDepth = 0.0;
    float stepKm = (segment.y - segment.x) / float(count);
    for (int i = 0; i < 6; i++) {
        if (i >= count) { break; }
        float distance = mix(segment.x, segment.y, (float(i) + 0.5) / float(count));
        vec3 unusedMass, unusedTops, unusedSupport;
        vec3 point = pointKm + sunlight * distance;
        float radial = dot(normalize(point), sunlight);
        float weatherFootprint = max(footprintKm, stepKm * 0.5 * sqrt(max(0.0, 1.0 - radial * radial)));
        opticalDepth += earthCloudDensity(point, sunlight, stepKm, footprintKm, weatherFootprint,
                earthCloudStepHeights(pointKm, sunlight, distance, stepKm), false, unusedMass, unusedTops,
                unusedSupport) * stepKm * 36.0;
        if (opticalDepth > 7.0) { return 0.000912; }
    }
    return exp(-opticalDepth);
}

float earthCloudShadowSteps(vec3 pointKm, vec3 sunlight, int count) { return earthCloudShadowFiltered(pointKm, sunlight, count, 0.0); }
float earthCloudShadow(vec3 pointKm, vec3 sunlight) { return earthCloudShadowSteps(pointKm, sunlight, 6); }

float earthCloudPhase(float cosine, float g) {
    return (1.0 - g * g) / (12.5663706 * pow(max(0.025, 1.0 + g * g - 2.0 * g * cosine), 1.5));
}

// Conditional mean scattering depth for a homogeneous step with integrated optical depth tau.
// Thin steps tend to their midpoint; opaque steps are seen from one mean free path behind the entry.
// This changes source quadrature only: the integrated density, extinction and opacity stay unchanged.
float earthCloudScatteringFraction(float opticalDepth) {
    float tau = max(0.0, opticalDepth);
    if (tau < 0.05) { return 0.5 - tau / 12.0 + tau * tau * tau / 720.0; }
    if (tau > 20.0) { return 1.0 / tau; }
    return 1.0 / tau - 1.0 / (exp(tau) - 1.0);
}

vec4 earthCloudTransport(vec3 originKm, vec3 ray, float limitKm, float pixelAngle, int requestedSteps,
                        out float scatteringDistanceKm) {
    scatteringDistanceKm = 0.0;
    if (CloudPlanet.w <= 0.0 || EarthCloudParams.x == 0.0) { return vec4(0, 0, 0, 1); }
    // No temporal resolve follows this pass: screen-space random steps would become permanent grain.
    // Distant shells use stable, footprint-filtered quadrature; close observers retain the full volume budget.
    float observerAltitude = max(0.0, length(originKm) - CloudPlanet.w);
    int count = clamp(requestedSteps, 8, 48);
    if (observerAltitude > 24.0) { count = min(count, 12); }
    vec2 intervals[6];
    int intervalSteps[6];
    int intervalCount = earthCloudViewIntervals(originKm, ray, limitKm, count, intervals, intervalSteps);
    if (intervalCount == 0) { return vec4(0, 0, 0, 1); }
    float cosine = dot(ray, EarthCloudSun);
    float phase = 0.84 * earthCloudPhase(cosine, 0.64) + 0.16 * earthCloudPhase(cosine, -0.24);
    float transmission = 1.0;
    vec3 scattering = vec3(0.0);
    int intervalIndex = 0, cellIndex = 0;
    for (int i = 0; i < 48; i++) {
        if (i >= count || transmission < 0.004) { break; }
        vec2 segment = intervals[intervalIndex];
        float stepKm = (segment.y - segment.x) / float(intervalSteps[intervalIndex]);
        float distance = segment.x + stepKm * (float(cellIndex) + 0.5);
        cellIndex++;
        if (cellIndex == intervalSteps[intervalIndex]) { intervalIndex++; cellIndex = 0; }
        vec3 point = originKm + ray * distance;
        // Filter both the screen pixel footprint and the integration interval, rather than point-sampling
        // billows smaller than a step. This also avoids their texture fetches once genuinely unresolved.
        float pixelFootprint = pixelAngle * distance;
        // Weather lives across the spherical surface: radial integration spans must not blur its angular detail.
        float radial = dot(normalize(point), ray);
        float weatherFootprint = max(pixelFootprint, stepKm * 0.5 * sqrt(max(0.0, 1.0 - radial * radial)));
        vec3 lightingMass, lightingTops, occupiedSupport;
        float density = earthCloudDensity(point, ray, stepKm, pixelFootprint, weatherFootprint,
                earthCloudStepHeights(originKm, ray, distance, stepKm), true, lightingMass, lightingTops, occupiedSupport);
        if (density <= 0.0001) { continue; }
        float opticalDepth = density * 36.0 * stepKm;
        float sourceOffset = mix(occupiedSupport.x, occupiedSupport.y, earthCloudScatteringFraction(opticalDepth));
        float sourceDistance = distance + earthCloudOccupiedPoint(point, ray, stepKm * 0.5,
                lightingMass, lightingTops, sourceOffset);
        vec3 sourcePoint = originKm + ray * sourceDistance;
        vec3 componentHeight = clamp((vec3(length(sourcePoint) - CloudPlanet.w) - vec3(0.85, 1.3, 1.4))
                / (lightingTops - vec3(0.85, 1.3, 1.4)), 0.0, 1.0);
        float height = dot(lightingMass, componentHeight) / max(0.00001, density);
        vec3 solar = EarthCloudParams.w > 0.0
                ? earthSunTransmission(sourcePoint, CloudPlanet.w, EarthCloudSun, 0.00465) : vec3(0.0);
        float sunlight = 0.0;
        if (max(solar.r, max(solar.g, solar.b)) > 0.0) {
            sunlight = earthCloudShadowFiltered(sourcePoint, EarthCloudSun, observerAltitude > 24.0 ? 2 : 3, pixelAngle * sourceDistance);
        }
        float incident = earthIncidentIrradiance(EarthCloudParams.w);
        // A bounded neutral higher-order bounce fills cloud heads, while attenuation darkens their undersides.
        // It consumes the same stellar irradiance and spectral air transport as the direct normalized phase.
        float multiple = (0.16 + 0.62 * height) * (0.25 + 0.75 * pow(sunlight, 0.3)) / 3.14159265;
        vec3 skyBounce = earthDiffuseSkyIrradiance(sourcePoint, CloudPlanet.w, EarthCloudSun) / EARTH_PI;
        vec3 scatteringResponse = earthCloudMaterialResponse()
                * (skyBounce + solar * (sunlight * phase * 0.90 + multiple));
        vec3 source = incident * scatteringResponse;
        float opacity = 1.0 - exp(-opticalDepth);
        scatteringDistanceKm += transmission * opacity * sourceDistance;
        scattering += transmission * opacity * source;
        transmission *= 1.0 - opacity;
    }
    scatteringDistanceKm /= max(0.00001, 1.0 - transmission);
    return vec4(scattering, transmission);
}

// Optional shafts keep the same cloud-shadow field and stop at actual copied world depth.
// Cloud controls and geometry select the medium; only its reflected source follows stellar intensity.
vec4 earthCloudAirTransport(vec3 originKm, vec3 ray, float limitKm, float strength, out float firstKm) {
    firstKm = 0.0;
    if (EarthCloudParams.x == 0.0 || strength <= 0.0) { return vec4(0, 0, 0, 1); }
    float lit = smoothstep(-0.035, 0.02, dot(normalize(originKm), EarthCloudSun));
    strength *= lit;
    vec2 outer = earthCloudSphere(originKm, ray, CloudPlanet.w + CloudLayer.x);
    firstKm = max(0.0, outer.x);
    float finish = min(min(limitKm, 16.0), outer.y);
    vec2 solid = earthCloudSphere(originKm, ray, CloudPlanet.w);
    if (solid.x >= 0.0 && solid.y > solid.x) { finish = min(finish, solid.x); }
    if (finish <= firstKm || strength <= 0.001) { return vec4(0, 0, 0, 1); }
    float stepKm = (finish - firstKm) / 12.0;
    float transmission = 1.0;
    vec3 scattering = vec3(0.0);
    float phase = earthCloudPhase(dot(ray, EarthCloudSun), 0.72);
    for (int i = 0; i < 12; i++) {
        vec3 point = originKm + ray * (firstKm + (float(i) + 0.5) * stepKm);
        vec3 source = earthSunTransmission(point, CloudPlanet.w, EarthCloudSun, 0.00465)
                * earthIncidentIrradiance(EarthCloudParams.w);
        if (max(source.r, max(source.g, source.b)) > 0.0) {
            source *= earthCloudShadowSteps(point, EarthCloudSun, 3) * phase;
        }
        float opacity = 1.0 - exp(-0.018 * strength * stepKm);
        scattering += transmission * opacity * source;
        transmission *= 1.0 - opacity;
    }
    return vec4(scattering, transmission);
}
