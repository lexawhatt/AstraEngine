#version 150
uniform vec2 ProbeClip;
uniform vec2 ProbeScreenSize;
uniform mat4 ProbeInverseView;
uniform mat3 ProbeBodyRotation;
uniform int ProbeCalendar;
uniform vec2 ProbeAngles;
uniform int ProbeSteps;
uniform int ProbeMode;
uniform vec2 CloudWind;
#moj_import <astraengine:cloud_density.glsl>
#moj_import <astraengine:earth_optics.glsl>
#moj_import <astraengine:earth_clouds.glsl>
out vec4 fragColor;

vec3 worldRay(vec2 clip) {
    vec4 point = ProbeInverseView * vec4(clip, 1.0, 1.0);
    return normalize(point.xyz / point.w);
}
vec3 bodyRay(vec3 ray) {
    if (ProbeCalendar != 0) { return ProbeBodyRotation * ray; }
    float c = cos(ProbeAngles.x), s = sin(ProbeAngles.x);
    vec3 tilted = vec3(ray.x, c * ray.y + s * ray.z, -s * ray.y + c * ray.z);
    c = cos(ProbeAngles.y); s = sin(ProbeAngles.y);
    return vec3(c * tilted.x + s * tilted.z, tilted.y, -s * tilted.x + c * tilted.z);
}
void main() {
    vec2 clip = 2.0 * (floor((ProbeClip * 0.5 + 0.5) * ProbeScreenSize) + 0.5) / ProbeScreenSize - 1.0;
    vec3 world = worldRay(clip), ray = bodyRay(world);
    // Use the actual full-resolution pixel footprint for every integration budget, not this 1x1 target.
    float pixelAngle = 0.65 * max(length(worldRay(clip + vec2(2.0 / ProbeScreenSize.x, 0)) - world),
            length(worldRay(clip + vec2(0, 2.0 / ProbeScreenSize.y)) - world));
    pixelAngle = max(pixelAngle, 0.25 / max(ProbeScreenSize.x, ProbeScreenSize.y));
    float limitKm = length(CloudPlanet.xyz) + CloudPlanet.w + CloudLayer.y;
    float cloudDistance = 0.0;
    if (ProbeMode == 0 || ProbeMode == 2) {
        vec4 transport = earthCloudTransport(CloudPlanet.xyz, ray, limitKm, pixelAngle, ProbeSteps, cloudDistance);
        if (ProbeMode == 0) { fragColor = transport; return; }
    }
    vec2 intervals[6];
    int intervalSteps[6];
    int intervalCount;
    if (ProbeSteps <= 12) {
        intervalCount = earthCloudViewIntervals(CloudPlanet.xyz, ray, limitKm, ProbeSteps, intervals, intervalSteps);
    } else {
        intervals[0] = earthCloudViewRange(CloudPlanet.xyz, ray, limitKm);
        intervalSteps[0] = ProbeSteps;
        intervalCount = intervals[0].y > intervals[0].x ? 1 : 0;
    }
    float transmission = 1.0, stepWeight = 0.0, spanWeight = 0.0, occupiedCells = 0.0;
    vec3 typeWeight = vec3(0.0);
    int intervalIndex = 0, cellIndex = 0;
    for (int i = 0; i < 256; i++) {
        if (i >= ProbeSteps || intervalIndex >= intervalCount || transmission < 0.004) { break; }
        vec2 segment = intervals[intervalIndex];
        float stepKm = (segment.y - segment.x) / float(intervalSteps[intervalIndex]);
        float distance = segment.x + stepKm * (float(cellIndex) + 0.5);
        cellIndex++;
        if (cellIndex == intervalSteps[intervalIndex]) { intervalIndex++; cellIndex = 0; }
        vec3 point = CloudPlanet.xyz + ray * distance;
        float radial = dot(normalize(point), ray);
        float footprint = pixelAngle * distance;
        float weatherFootprint = max(footprint, stepKm * 0.5 * sqrt(max(0.0, 1.0 - radial * radial)));
        vec3 mass, tops, support;
        float density = earthCloudDensity(point, ray, stepKm, footprint, weatherFootprint,
                earthCloudStepHeights(CloudPlanet.xyz, ray, distance, stepKm), true, mass, tops, support);
        if (density <= 0.0001) { continue; }
        float tau = density * 36.0 * stepKm;
        if (ProbeMode <= 2) {
            fragColor = ProbeMode == 1 ? vec4(mass / density, tau)
                    : vec4(stepKm, support.z, cloudDistance, length(point) - CloudPlanet.w);
            return;
        }
        float opacity = 1.0 - exp(-tau);
        float weight = transmission * opacity;
        typeWeight += weight * mass / density;
        stepWeight += weight * stepKm; spanWeight += weight * support.z; occupiedCells += 1.0;
        transmission *= 1.0 - opacity;
    }
    float weightSum = 1.0 - transmission;
    if (ProbeMode == 3) { fragColor = vec4(typeWeight / max(0.00001, weightSum), transmission); return; }
    if (ProbeMode == 4) {
        fragColor = vec4(stepWeight / max(0.00001, weightSum), spanWeight / max(0.00001, weightSum), occupiedCells, weightSum);
        return;
    }
    fragColor = vec4(0.0);
}
