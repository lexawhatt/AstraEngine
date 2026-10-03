// Earth-like optical transport. The radius-only LUT contains integrated density
// along the actual unobstructed spherical Sun chord, never a painted limb color.
uniform sampler2D EarthOpticalColumns;
uniform int EarthOpticsReady;
uniform float EarthOpticsRadius;
const vec3 EARTH_RAYLEIGH = vec3(0.0058, 0.0135, 0.0331);
const vec3 EARTH_MIE_EXTINCTION = vec3(0.0032);
const vec3 EARTH_OZONE = vec3(0.00065, 0.00188, 0.00008);
const float EARTH_SHELL_KM = 80.0;
const float EARTH_PI = 3.141592653589793;
// Shared renderer-relative incident irradiance. Host display-calibrated material
// response uses E/pi; normalized atmosphere/cloud phases consume the same E.
const float EARTH_REFERENCE_IRRADIANCE = EARTH_PI * (1.35 / 1.354);
float earthIncidentIrradiance(float relativeSolarSource) {
    return max(0.0, relativeSolarSource) * EARTH_REFERENCE_IRRADIANCE;
}

vec3 earthAirDensity(float height) {
    return vec3(exp(-height / 8.0), exp(-height / 1.2), max(0.0, 1.0 - abs(height - 25.0) / 15.0));
}

vec3 earthOpticalColumns(float radius, float height, float mu) {
    height = clamp(height, 0.0, EARTH_SHELL_KM);
    float r = radius + height;
    float rho = sqrt(height * (2.0 * radius + height));
    float horizon = sqrt(EARTH_SHELL_KM * (2.0 * radius + EARTH_SHELL_KM));
    mu = max(mu, -rho / r);
    float remaining = (EARTH_SHELL_KM - height) * (2.0 * radius + EARTH_SHELL_KM + height);
    float along = r * mu;
    float root = sqrt(max(0.0, along * along + remaining));
    float distance = along >= 0.0 ? remaining / max(1e-7, root + along) : root - along;
    float minimum = EARTH_SHELL_KM - height;
    vec2 uv = clamp(vec2((distance - minimum) / max(1e-7, rho + horizon - minimum), rho / horizon), 0.0, 1.0);
    if (EarthOpticsReady != 0 && abs(radius - EarthOpticsRadius) < 0.01) {
        vec2 size = vec2(textureSize(EarthOpticalColumns, 0));
        return texture(EarthOpticalColumns, (vec2(0.5) + uv * (vec2(128.0, 64.0) - 1.0)) / size).rgb;
    }
    // Bounded first-frame/reload fallback. The immutable CPU table replaces this
    // path as soon as it is ready; an unready or different-radius table is never sampled.
    vec3 columns = vec3(0.0);
    for (int j = 0; j < 12; j++) {
        float d = distance * (float(j) + 0.5) / 12.0;
        float q = height * (2.0 * radius + height) + d * (d + 2.0 * r * mu);
        float sampleHeight = max(0.0, q / (sqrt(max(0.0, radius * radius + q)) + radius));
        columns += earthAirDensity(sampleHeight) * (distance / 12.0);
    }
    return columns;
}

// Upper-hemisphere first-order sky irradiance divided by incident irradiance.
// The appended tile excludes the direct beam, ground reflection and absorbed ozone energy.
// An unready/different-radius table contributes zero diffuse light until the same owned bake completes.
vec3 earthDiffuseSkyIrradiance(vec3 position, float radius, vec3 sunlight) {
    if (EarthOpticsReady == 0 || abs(radius - EarthOpticsRadius) >= 0.01) { return vec3(0.0); }
    float height = max(0.0, length(position) - radius);
    if (height >= EARTH_SHELL_KM) { return vec3(0.0); }
    float mu = clamp(dot(normalize(position), sunlight), -1.0, 1.0);
    vec2 uv = vec2(0.5 + 0.5 * sign(mu) * sqrt(abs(mu)), sqrt(height / EARTH_SHELL_KM));
    vec2 texel = vec2(0.5, 64.5) + uv * vec2(47.0, 23.0);
    return texture(EarthOpticalColumns, texel / vec2(textureSize(EarthOpticalColumns, 0))).rgb;
}

vec3 earthSunTransmission(vec3 position, float radius, vec3 sunlight, float sunRadius) {
    float r = length(position);
    float height = max(0.0, r - radius);
    float mu = dot(position / r, sunlight);
    float horizon = -sqrt(max(0.0, height * (2.0 * radius + height))) / r;
    float edge = max(0.0001, sunRadius);
    float visibility = smoothstep(horizon - edge, horizon + edge, mu);
    if (visibility <= 0.0) { return vec3(0.0); }
    if (height >= EARTH_SHELL_KM) {
        float top = radius + EARTH_SHELL_KM;
        float along = r * mu;
        float discriminant = along * along + top * top - r * r;
        if (along >= 0.0 || discriminant <= 0.0) { return vec3(visibility); }
        // Keep the actual impact parameter for an observer above the atmosphere.
        position += sunlight * max(0.0, -along - sqrt(discriminant));
        r = length(position);
        height = EARTH_SHELL_KM;
        mu = dot(position / r, sunlight);
    }
    vec3 columns = earthOpticalColumns(radius, height, mu);
    return exp(-(EARTH_RAYLEIGH * columns.x + EARTH_MIE_EXTINCTION * columns.y
            + EARTH_OZONE * columns.z)) * visibility;
}

// Beer-Lambert transport over a finite camera-to-cloud/surface segment. All
// positions and distances are kilometers in one common planet-centered frame.
// Ground clips the optical interval; callers own opaque terrain visibility.
vec3 earthViewTransmission(vec3 origin, vec3 ray, float distance, float radius) {
    float top = radius + EARTH_SHELL_KM;
    float along = dot(origin, ray);
    float squared = dot(origin, origin);
    float outer = along * along + top * top - squared;
    if (outer <= 0.0 || distance <= 0.0) { return vec3(1.0); }
    float root = sqrt(outer);
    float start = max(0.0, -along - root);
    float finish = min(distance, -along + root);
    float ground = along * along + radius * radius - squared;
    if (ground >= 0.0) {
        float entry = -along - sqrt(ground);
        if (entry >= start) { finish = min(finish, entry); }
    }
    if (finish <= start) { return vec3(1.0); }
    vec3 first = origin + ray * start;
    vec3 last = origin + ray * finish;
    float firstRadius = length(first), lastRadius = length(last);
    float firstHeight = clamp(firstRadius - radius, 0.0, EARTH_SHELL_KM);
    float lastHeight = clamp(lastRadius - radius, 0.0, EARTH_SHELL_KM);
    float firstMu = dot(first, ray) / firstRadius;
    float lastMu = dot(last, ray) / lastRadius;
    float horizon = -sqrt(firstHeight * (2.0 * radius + firstHeight)) / firstRadius;
    vec3 columns;
    if (firstMu >= horizon) {
        columns = earthOpticalColumns(radius, firstHeight, firstMu)
                - earthOpticalColumns(radius, lastHeight, lastMu);
    } else {
        // A downward ray may hit the solid planet after the target. Integrate
        // the same finite segment backward using unobstructed outward columns.
        columns = earthOpticalColumns(radius, lastHeight, -lastMu)
                - earthOpticalColumns(radius, firstHeight, -firstMu);
    }
    columns = max(columns, vec3(0.0));
    return exp(-(EARTH_RAYLEIGH * columns.x + EARTH_MIE_EXTINCTION * columns.y + EARTH_OZONE * columns.z));
}


float earthRaySample(float unit, float start, float finish, float closest) {
    if (closest <= start) { return mix(start, finish, unit * unit); }
    if (closest >= finish) { return mix(finish, start, (1.0 - unit) * (1.0 - unit)); }
    return unit < 0.5 ? mix(closest, start, (1.0 - unit * 2.0) * (1.0 - unit * 2.0))
            : mix(closest, finish, (unit * 2.0 - 1.0) * (unit * 2.0 - 1.0));
}

// Finite-segment single scattering used by both orbital air and the foreground
// of ground clouds. Positions/distances are kilometers; the source is the same
// relative solar irradiance and the output remains linear renderer radiance.
vec3 earthAirScattering(vec3 origin, vec3 ray, float radius, vec3 sunlight,
                       float relativeSource, float sunRadius, float limitKm, int requestedSteps,
                       out vec3 transmission) {
    transmission = vec3(1.0);
    if (limitKm <= 0.0) { return vec3(0.0); }
    if (length(origin) - radius < 0.002) { origin = normalize(origin) * (radius + 0.002); }
    float along = dot(origin, ray);
    vec3 perpendicular = origin - ray * along;
    float perpendicularSquared = dot(perpendicular, perpendicular);
    float top = radius + EARTH_SHELL_KM;
    float outerSquared = top * top - perpendicularSquared;
    if (outerSquared <= 0.0) { return vec3(0.0); }
    float outerRoot = sqrt(outerSquared);
    float start = max(0.0, -along - outerRoot);
    float finish = min(limitKm, -along + outerRoot);
    float solidSquared = radius * radius - perpendicularSquared;
    if (solidSquared > 0.0) {
        float entry = -along - sqrt(solidSquared);
        if (entry > 0.0) { finish = min(finish, entry); }
    }
    if (finish <= start) { return vec3(0.0); }
    float closest = clamp(-along, start, finish);
    int steps = clamp(requestedSteps, 1, 16);
    float mu = clamp(dot(ray, sunlight), -1.0, 1.0);
    float rayleighPhase = 0.0596831 * (1.0 + mu * mu);
    float g = 0.76;
    float miePhase = 0.07957747 * (1.0 - g * g) / pow(max(0.001, 1.0 + g * g - 2.0 * g * mu), 1.5);
    vec3 scattering = vec3(0.0);
    for (int i = 0; i < 16; i++) {
        if (i >= steps) { break; }
        float a = earthRaySample(float(i) / float(steps), start, finish, closest);
        float b = earthRaySample(float(i + 1) / float(steps), start, finish, closest);
        vec3 position = origin + ray * ((a + b) * 0.5);
        vec3 density = earthAirDensity(max(0.0, length(position) - radius));
        vec3 extinction = EARTH_RAYLEIGH * density.x + EARTH_MIE_EXTINCTION * density.y + EARTH_OZONE * density.z;
        vec3 stepTransmission = exp(-extinction * (b - a));
        vec3 direct = relativeSource > 0.0
                ? earthSunTransmission(position, radius, sunlight, sunRadius) * earthIncidentIrradiance(relativeSource)
                : vec3(0.0);
        // Aerosol single-scattering albedo0.9; normalized phases use the shared source exactly once.
        vec3 source = (EARTH_RAYLEIGH * density.x * rayleighPhase
                + EARTH_MIE_EXTINCTION * (0.9 * density.y * miePhase)) * direct;
        scattering += transmission * source * (vec3(1.0) - stepTransmission) / max(extinction, vec3(1e-7));
        transmission *= stepTransmission;
    }
    return scattering;
}
