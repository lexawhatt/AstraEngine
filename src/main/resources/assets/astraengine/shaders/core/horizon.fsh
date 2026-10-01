#version 150

uniform mat4 InverseViewProjection;
uniform float HorizonRadius;
uniform float EyeAltitude;
uniform float OceanSurfaceOffset;
uniform int FlatComparison;
uniform int ShowTowers;
uniform vec3 SunDirection;
uniform vec3 MarineWaterColor;
uniform vec3 MarineFogColor;
uniform vec3 TowerCenter[3];
uniform mat3 TowerInverseBasis[3];
in vec2 clipPosition;
out vec4 fragColor;

// Tangent-camera origin is (0, R+h, 0) in radial space. Keeping altitude separate
// avoids subtracting two Earth-radius floats to recover a human eye height.
float oceanDistance(vec3 ray) {
    float heightAboveWater = EyeAltitude - OceanSurfaceOffset;
    if (FlatComparison != 0) {
        return ray.y < -1e-9 ? -heightAboveWater / ray.y : -1.0;
    }
    float b = (HorizonRadius + EyeAltitude) * ray.y;
    // (R+h)^2 - (R+surfaceOffset)^2, without losing the sub-meter water surface offset.
    float c = heightAboveWater * (2.0 * HorizonRadius + EyeAltitude + OceanSurfaceOffset);
    float discriminant = b * b - c;
    if (b >= 0.0 || discriminant < 0.0) { return -1.0; }
    // Product of the roots divided by the well-conditioned far root.
    return c / (-b + sqrt(max(0.0, discriminant)));
}

float boxDistance(vec3 origin, vec3 ray, out vec3 point) {
    vec3 halfSize = vec3(100.0, 60.0, 100.0);
    float nearT = 0.0;
    float farT = 1e20;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(ray[axis]) < 1e-8) {
            if (abs(origin[axis]) > halfSize[axis]) { return -1.0; }
        } else {
            float a = (-halfSize[axis] - origin[axis]) / ray[axis];
            float b = (halfSize[axis] - origin[axis]) / ray[axis];
            nearT = max(nearT, min(a, b));
            farT = min(farT, max(a, b));
        }
    }
    if (farT < nearT || farT < 0.0) { return -1.0; }
    point = origin + ray * nearT;
    return nearT;
}

vec3 daylightSky(vec3 ray, vec3 sun, float daylight) {
    float atmosphere = exp(-EyeAltitude / 18000.0);
    float toward = pow(max(dot(ray, sun), 0.0), 10.0);
    float horizonBand = exp(-abs(ray.y) * 4.0);
    vec3 upper = mix(vec3(0.002, 0.004, 0.012), vec3(0.12, 0.32, 0.62), daylight * atmosphere);
    vec3 haze = mix(vec3(0.009, 0.015, 0.035), vec3(0.58, 0.72, 0.85), daylight);
    vec3 sky = mix(upper, haze, horizonBand * atmosphere * 0.7);
    float dusk = (1.0 - smoothstep(0.02, 0.35, abs(sun.y))) * atmosphere;
    sky += vec3(0.65, 0.18, 0.045) * toward * dusk * horizonBand;
    float angularSun = 0.00465;
    float footprint = max(fwidth(dot(ray, sun)), 1e-7);
    float disc = smoothstep(cos(angularSun) - footprint, cos(angularSun) + footprint, dot(ray, sun));
    sky += vec3(1.0, 0.88, 0.64) * disc * smoothstep(-0.05, 0.02, sun.y);
    return sky;
}

void main() {
    // Two finite unprojected points also work with the host's short far plane.
    vec4 nearPoint = InverseViewProjection * vec4(clipPosition, -1.0, 1.0);
    vec4 farPoint = InverseViewProjection * vec4(clipPosition, 0.0, 1.0);
    vec3 ray = normalize(farPoint.xyz / farPoint.w - nearPoint.xyz / nearPoint.w);
    vec3 sun = normalize(SunDirection);
    float daylight = smoothstep(-0.12, 0.18, sun.y);
    vec3 sky = daylightSky(ray, sun, daylight);
    float ocean = oceanDistance(ray);
    vec3 color = sky;
    float nearest = ocean < 0.0 ? 1e20 : ocean;
    if (ocean >= 0.0) {
        vec3 point = ray * ocean;
        vec3 normal = FlatComparison != 0 ? vec3(0.0, 1.0, 0.0)
                : normalize(vec3(point.x, HorizonRadius + EyeAltitude + point.y, point.z));
        float facing = clamp(-dot(ray, normal), 0.0, 1.0);
        float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);
        // The bounded host/DH view and the planetary ocean share one marine fog radiance.
        // This colors the ocean material; it never covers foreground blocks or changes their depth.
        vec3 water = mix(MarineWaterColor, MarineFogColor, fresnel * 0.68);
        float glint = pow(max(dot(reflect(-sun, normal), -ray), 0.0), 400.0);
        water += vec3(0.7, 0.65, 0.45) * glint * daylight;
        float haze = (1.0 - exp(-ocean / 110000.0)) * exp(-EyeAltitude / 18000.0) * 0.55;
        color = mix(water, MarineFogColor, haze);
    }
    if (ShowTowers != 0) {
        for (int i = 0; i < 3; i++) {
            vec3 localPoint;
            float distance = boxDistance(TowerInverseBasis[i] * -TowerCenter[i], TowerInverseBasis[i] * ray, localPoint);
            if (distance >= 0.0 && distance < nearest) {
                nearest = distance;
                float stripe = step(0.5, fract((localPoint.y + 60.0) / 40.0));
                color = mix(vec3(0.95, 0.32, 0.08), vec3(0.92, 0.93, 0.89), stripe) * (0.12 + 0.88 * daylight);
                color *= 0.8 + 0.2 * step(99.5, abs(localPoint.z));
            }
        }
    }
    fragColor = vec4(clamp(color, vec3(0.0), vec3(1.0)), 1.0);
}
