#version 150

uniform mat4 InverseViewProjection;
uniform int HdrOutput;
uniform float Exposure;
uniform vec2 ScreenSize;
uniform vec3 SunDirection;
uniform float SunRadius;
uniform float Time;
uniform int MoonPhase;
uniform vec2 Weather;
uniform int Detail;
uniform vec3 SkyColor;
uniform vec3 AtmosphereFog;
uniform vec3 SkyNorth;
uniform vec3 SkyEast;
uniform vec3 SkyPole;
// Aerosol, cloud coverage, light pollution and annual orbital phase.
uniform vec4 AtmosphereParams;
uniform vec2 CloudOffset;
uniform vec2 CloudWind;
uniform vec4 Evolution;
uniform vec4 SolarLight;
uniform sampler2D CloudTransport;
uniform int VolumeEnabled;

in vec2 clipPosition;
out vec4 fragColor;
const float PI = 3.14159265359;

float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(cell), hash31(cell + vec3(1, 0, 0)), f.x),
                   mix(hash31(cell + vec3(0, 1, 0)), hash31(cell + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash31(cell + vec3(0, 0, 1)), hash31(cell + vec3(1, 0, 1)), f.x),
                   mix(hash31(cell + vec3(0, 1, 1)), hash31(cell + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float value = 0.0;
    float weight = 0.52;
    for (int i = 0; i < 5; i++) {
        if (i >= Detail) { break; }
        value += noise(p) * weight;
        p = mat3(0.0, 0.8, 0.6, -0.8, 0.36, -0.48, -0.6, -0.48, 0.64)
          * p * 2.07 + vec3(13.1, 7.7, 19.2);
        weight *= 0.48;
    }
    return value;
}

#moj_import <astraengine:solar.glsl>
#moj_import <astraengine:celestial_display.glsl>
#moj_import <astraengine:atmosphere.glsl>
#moj_import <astraengine:surface_geography.glsl>
#moj_import <astraengine:lunar.glsl>

vec4 filteredCloudTransport(vec2 uv) {
    vec2 texel = 1.0 / vec2(textureSize(CloudTransport, 0));
    vec4 center = texture(CloudTransport, uv);
    vec4 sum = center * 2.0;
    float total = 2.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = i == 0 ? vec2(1, 0) : i == 1 ? vec2(-1, 0)
                    : i == 2 ? vec2(0, 1) : vec2(0, -1);
        vec4 neighbor = texture(CloudTransport, uv + offset * texel);
        float weight = exp(-abs(neighbor.a - center.a) * 12.0);
        sum += neighbor * weight;
        total += weight;
    }
    return sum / total;
}

vec3 stars(vec3 ray, float scale, float density) {
    vec3 a = abs(ray);
    vec2 uv;
    float face;
    if (a.x > a.y && a.x > a.z) { uv = ray.yz / a.x; face = ray.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y > a.z) { uv = ray.xz / a.y; face = ray.y > 0.0 ? 2.0 : 3.0; }
    else { uv = ray.xy / a.z; face = ray.z > 0.0 ? 4.0 : 5.0; }
    vec2 grid = uv * scale;
    vec2 cell = floor(grid);
    vec3 key = vec3(cell, face * 73.0);
    float random = hash31(key);
    vec2 point = vec2(hash31(key + 1.3), hash31(key + 5.7)) * 0.6 + 0.2;
    vec2 offset = fract(grid) - point;
    float width = max(0.07, max(fwidth(grid.x), fwidth(grid.y)) * 0.65);
    float spot = exp(-dot(offset, offset) / (width * width)) * min(1.0, 0.003 / (width * width));
    vec3 tint = mix(vec3(0.72, 0.82, 1.0), vec3(1.0, 0.85, 0.67), hash31(key + 9.1));
    return tint * spot * (1.0 - step(density, random)) * (0.65 + random * 3.0);
}

vec3 nightSky(vec3 ray, float pollution) {
    vec3 galacticPole = normalize(vec3(0.38, 0.74, -0.55));
    float latitude = abs(dot(ray, galacticPole));
    float band = exp(-pow(latitude * 6.0, 1.35));
    float cloud = fbm(ray * 11.0 + vec3(5.2, 1.3, 9.7));
    float dustField = fbm(ray * 29.0 + vec3(1.3));
    float dust = smoothstep(0.37, 0.65, dustField) * exp(-latitude * 10.0);
    float core = pow(max(dot(ray, normalize(vec3(-0.65, 0.64, 0.42))), 0.0), 8.0);
    float structure = smoothstep(0.12, 0.75, cloud) * (1.0 - dust * 0.90);
    vec3 color = mix(vec3(0.013, 0.019, 0.033), vec3(0.061, 0.045, 0.026), core)
               * band * structure * exp(-pollution * 5.5);
    float starVisibility = mix(1.0, 0.12, pollution);
    color += stars(ray, 120.0, 0.025 + band * 0.055) * 1.7 * starVisibility;
    if (Detail > 3) { color += stars(ray, 320.0, 0.009 + band * 0.027) * starVisibility; }
    return color;
}

vec3 moon(vec3 background, vec3 ray, float pixelAngle, float visibility, float incidentRadiance) {
    vec3 center = -SunDirection;
    float along = dot(ray, center);
    if (along <= 0.0) { return background; }
    vec3 perpendicular = center - ray * along;
    const float radius = 1737400.0 / 384400000.0;
    float discriminant = radius * radius - dot(perpendicular, perpendicular);
    if (discriminant <= 0.0) { return background; }
    vec3 normal = (-perpendicular - ray * sqrt(discriminant)) / radius;
    vec3 reference = abs(center.z) < 0.95 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 right = normalize(cross(reference, center));
    float phase = float(MoonPhase) * PI / 4.0;
    vec3 light = -center * cos(phase) + right * sin(phase);
    // A fixed face in the lunar frame avoids texture rotation as the host Sun
    // crosses the sky. Host phases still own illumination; this is not a new orbit.
    vec3 up = cross(center, right);
    vec3 p = vec3(dot(normal, right), dot(normal, up), -dot(normal, center));
    vec3 localLight = vec3(dot(light, right), dot(light, up), -dot(light, center));
    vec3 localView = vec3(dot(-ray, right), dot(-ray, up), dot(ray, center));
    float footprint = pixelAngle / max(radius * max(0.025, dot(normal, -ray)), 1e-8);
    vec3 reflected = lunarRadiance(p, localLight, localView, 18.0, 0x4D4F4F4Eu,
                                  footprint, 1737400.0, false);
    float edge = 1.0 - smoothstep(radius - pixelAngle, radius + pixelAngle, length(perpendicular));
    return mix(background, reflected * incidentRadiance, edge * visibility);
}

void main() {
    vec4 reconstructed = InverseViewProjection * vec4(clipPosition, 1.0, 1.0);
    vec3 ray = normalize(reconstructed.xyz / reconstructed.w);
    float pixelAngle = max(length(dFdx(ray)), length(dFdy(ray))) * 0.65;
    pixelAngle = max(pixelAngle, 0.25 / max(ScreenSize.x, ScreenSize.y));
    float day = smoothstep(-0.12, 0.08, SunDirection.y);
    // Match the world-lighting snapshot in linear radiance. Taking a square
    // root overlights the remnant by more than five times; clipping at one
    // instead suppresses the authored supernova flash. SolarVisual bounds x at two.
    float incidentRadiance = clamp(SolarLight.x, 0.0, 2.0);
    float aerosol = clamp(AtmosphereParams.x, 0.05, 4.0);
    float pollution = clamp(AtmosphereParams.z, 0.0, 1.0);
    float clear = (1.0 - Weather.x * 0.72) * (1.0 - Weather.y * 0.70);
    float coverage = AtmosphereParams.y <= 0.001 ? 0.0
                   : clamp(AtmosphereParams.y, 0.0, 1.0);
    vec3 color = atmosphericSky(ray, SunDirection, aerosol) * incidentRadiance;
    // A very small host contribution keeps biome sky tint while scattering owns
    // the day/night gradient. Night has no unconditional blue daytime sky floor.
    color += pow(clamp(SkyColor, vec3(0.0), vec3(1.0)), vec3(2.2)) * 0.008 * day * incidentRadiance;
    float night = 1.0 - smoothstep(-0.19, -0.055, SunDirection.y) * min(incidentRadiance, 1.0);
    float upperSky = smoothstep(-0.025, 0.12, ray.y);
    vec3 inertial = normalize(vec3(dot(ray, SkyEast), dot(ray, SkyPole), dot(ray, SkyNorth)));
    color += vec3(0.00024, 0.00045, 0.0010) * night;
    if (night > 0.001) {
        color += nightSky(inertial, pollution) * night * clear * upperSky;
        color += vec3(0.024, 0.012, 0.0045) * pollution * night
               * exp(-max(ray.y, 0.0) * 3.0) * (1.0 + coverage * 0.65);
    }
    color = moon(color, ray, pixelAngle, clear * upperSky * (0.05 + night * 0.95), incidentRadiance);
    // Extinction affects the solar spectrum before HDR bloom; the displayed disc
    // scale is explicit presentation input and never changes the Sol descriptor.
    vec3 transmission = atmosphereTransmission(max(SunDirection.y, 0.0), 0.05, aerosol);
    float discHorizon = smoothstep(-SunRadius, SunRadius, ray.y + 0.004);
    vec3 solar = evolvingSolarRadiance(vec3(0.0), ray, SunDirection, SunRadius,
                                      vec3(1.0, 0.96, 0.90), 588.0, pixelAngle);
    // The Sun has much greater surface radiance than the scattered sky. Keep
    // sufficient headroom for its halo/bloom instead of a dim colored pinprick.
    // Preserve the healthy disc and initial flash calibration. Once the flash
    // passes, the same server-authored eruption age removes its presentation
    // gain so remnant ejecta cannot remain a daylight-bright sky behind dark land.
    float postFlash = smoothstep(0.6, 4.0, max(Evolution.z, 0.0));
    float solarGain = 18.0 * mix(1.0, min(incidentRadiance, 1.0), postFlash);
    color += solar * transmission * clear * discHorizon * solarGain;
    float sunAlong = dot(ray, SunDirection);
    float sunAngle = atan(length(SunDirection - ray * sunAlong), sunAlong);
    color += transmission * exp(-max(0.0, sunAngle - SunRadius) * 23.0)
           * 0.70 * day * clear * incidentRadiance * discHorizon;
    float moonAboveHorizon = smoothstep(-0.01, 0.10, -SunDirection.y);
    float moonlight = max(0.0, cos(float(MoonPhase) * PI / 4.0))
                    * night * incidentRadiance * moonAboveHorizon;
    // This is the distant background, not a foreground fog sheet. Apply it
    // before volume transport so clouds remain visible from above the layer.
    float belowHorizon = 1.0 - smoothstep(-0.30, -0.015, ray.y);
    if (VolumeEnabled == 1) {
        color = mix(color, celestialRadiance(AtmosphereFog), belowHorizon);
    }
    if (VolumeEnabled == 1) {
        vec4 transport = filteredCloudTransport(clipPosition * 0.5 + 0.5);
        color = color * transport.a + transport.rgb;
    } else {
        color = cloudLayer(color, ray, coverage, aerosol, incidentRadiance, moonlight);
    }
    // Rain dims and desaturates the atmosphere while cloud geometry retains its
    // illuminated edges. Land/depth still belongs to Minecraft's later passes.
    float grey = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(color, vec3(grey) * vec3(0.81, 0.86, 0.94), Weather.x * 0.46);
    color *= 1.0 - Weather.y * 0.55;
    // Do not paint over the sunset or the solar disc at the geometric horizon.
    // Blend toward host fog only below it, where terrain will normally occlude us.
    if (VolumeEnabled == 0) {
        color = mix(color, celestialRadiance(AtmosphereFog), belowHorizon);
    }
    fragColor = vec4(HdrOutput == 1 ? max(color, vec3(0.0)) : celestialDisplay(color, Exposure), 1.0);
}
