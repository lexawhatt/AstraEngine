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
uniform vec4 Evolution;
uniform vec4 SolarLight;

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
    float width = max(0.035, max(fwidth(grid.x), fwidth(grid.y)) * 0.7);
    float spot = exp(-dot(offset, offset) / (width * width)) * min(1.0, 0.003 / (width * width));
    vec3 tint = mix(vec3(0.50, 0.70, 1.0), vec3(1.0, 0.76, 0.49), hash31(key + 9.1));
    return tint * spot * (1.0 - step(density, random)) * (0.65 + random * 3.0);
}

vec3 nightSky(vec3 ray) {
    float band = exp(-pow(abs(dot(ray, normalize(vec3(0.38, 0.74, -0.55)))) * 6.0, 1.35));
    float cloud = fbm(ray * 10.0 + vec3(5.2, 1.3, 9.7));
    float dust = smoothstep(0.46, 0.7, fbm(ray * 24.0 + vec3(1.3)));
    vec3 color = vec3(0.010, 0.015, 0.035) * band * cloud * (1.0 - dust * 0.9);
    color += mix(vec3(0.001, 0.007, 0.013), vec3(0.014, 0.003, 0.009), cloud)
           * band * cloud * cloud;
    color += stars(ray, 120.0, 0.035 + band * 0.045) * 1.3;
    if (Detail > 3) { color += stars(ray, 380.0, 0.01 + band * 0.035); }
    return color;
}

vec3 moon(vec3 background, vec3 ray, float pixelAngle, float visibility) {
    vec3 center = -SunDirection;
    float along = dot(ray, center);
    if (along <= 0.0) { return background; }
    vec3 perpendicular = center - ray * along;
    const float radius = 1737400.0 / 384400000.0;
    float discriminant = radius * radius - dot(perpendicular, perpendicular);
    if (discriminant <= 0.0) { return background; }
    vec3 normal = (-perpendicular - ray * sqrt(discriminant)) / radius;
    vec3 right = normalize(cross(vec3(0.0, 0.0, 1.0), center));
    float phase = float(MoonPhase) * PI / 4.0;
    vec3 light = -center * cos(phase) + right * sin(phase);
    float diffuse = max(dot(normal, light), 0.0);
    float terrain = noise(normal * 28.0) * 0.14 + fbm(normal * 8.0) * 0.3;
    vec3 albedo = vec3(0.43, 0.48, 0.59) * (0.51 + terrain);
    float edge = 1.0 - smoothstep(radius - pixelAngle, radius + pixelAngle, length(perpendicular));
    return mix(background, albedo * (0.016 + diffuse * 0.86), edge * visibility);
}

void main() {
    vec4 reconstructed = InverseViewProjection * vec4(clipPosition, 1.0, 1.0);
    vec3 ray = normalize(reconstructed.xyz / reconstructed.w);
    float pixelAngle = max(length(dFdx(ray)), length(dFdy(ray))) * 0.65;
    pixelAngle = max(pixelAngle, 0.25 / max(ScreenSize.x, ScreenSize.y));
    float day = smoothstep(-0.18, 0.12, SunDirection.y);
    float brightness = sqrt(clamp(SolarLight.x, 0.0, 1.0));
    float light = day * brightness;
    float altitude = pow(clamp(ray.y, 0.0, 1.0), 0.48);
    float clear = (1.0 - Weather.x * 0.86) * (1.0 - Weather.y * 0.6);
    vec3 zenith = mix(SkyColor * vec3(0.65, 0.83, 1.0), vec3(0.21, 0.43, 0.81), 0.55);
    vec3 horizon = mix(SkyColor, vec3(0.57, 0.70, 0.89), 0.35);
    vec3 skyDisplay = mix(vec3(0.012, 0.022, 0.048), mix(horizon, zenith, altitude), day);
    skyDisplay *= 0.075 + brightness * 0.925;
    float towardSun = pow(max(dot(ray, SunDirection), 0.0), 5.0);
    float twilight = exp(-pow(SunDirection.y / 0.17, 2.0));
    float nearHorizon = exp(-max(ray.y, 0.0) * 5.0);
    skyDisplay += vec3(0.76, 0.22, 0.045) * twilight * nearHorizon * (0.12 + towardSun * 0.88)
                * clear * brightness;
    skyDisplay = mix(skyDisplay, vec3(dot(skyDisplay, vec3(0.27, 0.57, 0.16))) * 0.74,
                     Weather.x * 0.7 + Weather.y * 0.25);
    // Host atmosphere is display-valued; celestial emission is linear HDR radiance.
    skyDisplay /= max(1.0, max(skyDisplay.r, max(skyDisplay.g, skyDisplay.b)) / 0.92);
    vec3 color = celestialRadiance(skyDisplay);
    float night = 1.0 - smoothstep(0.05, 0.5, light);
    if (night > 0.0) { color += nightSky(ray) * night * clear; }
    color = moon(color, ray, pixelAngle, clear * (0.12 + night * 0.88));
    // Atmospheric forward scattering makes a soft aureole, without enlarging the solar disc.
    float sunAlong = dot(ray, SunDirection);
    float sunAngle = atan(length(SunDirection - ray * sunAlong), sunAlong);
    color += vec3(1.0, 0.69, 0.36) * exp(-max(0.0, sunAngle - SunRadius) * 28.0)
           * 0.065 * day * clear * min(SolarLight.x, 1.0);
    vec3 solar = evolvingSolarRadiance(color, ray, SunDirection, SunRadius,
                                     vec3(1.0, 0.93, 0.78), 588.0, pixelAngle);
    color = mix(color, solar, clear);
    float horizonBlend = 1.0 - smoothstep(-0.08, 0.065, ray.y);
    color = mix(color, celestialRadiance(AtmosphereFog), horizonBlend);
    fragColor = vec4(HdrOutput == 1 ? max(color, vec3(0.0)) : celestialDisplay(color, Exposure), 1.0);
}
