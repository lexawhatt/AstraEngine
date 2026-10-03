#version 150
uniform vec3 ProbePoint;
uniform vec2 CloudWind;
uniform int ProbeMode;
uniform float ProbeSource;
uniform mat4 ProbeInverseView;
uniform mat3 ProbeBodyRotation;
uniform int ProbeCalendar;
uniform vec2 ProbeAngles;
uniform vec3 ProbeRay;
uniform vec3 ProbeMass;
uniform vec3 ProbeTops;
uniform vec2 ProbeScreenSize;
uniform int ProbeSteps;
uniform vec2 ProbeHeights;
uniform vec2 ProbeFootprints;
in vec2 probeClip;
#moj_import <astraengine:cloud_density.glsl>
#moj_import <astraengine:earth_optics.glsl>
#moj_import <astraengine:earth_clouds.glsl>
#moj_import <astraengine_verify:earth_cloud_eager.glsl>
#moj_import <astraengine:celestial_display.glsl>
out vec4 fragColor;
void main() {
    if (ProbeMode == 15) {
        vec2 intervals[6];
        int steps[6];
        int count = earthCloudViewIntervals(ProbePoint, ProbeRay, ProbeSource, ProbeSteps, intervals, steps);
        int index = clamp(ProbeCalendar, 0, 5);
        fragColor = vec4(intervals[index], float(steps[index]), float(count));
        return;
    }
    if (ProbeMode == 13 || ProbeMode == 14) {
        fragColor = ProbeMode == 13
                ? vec4(earthDiffuseSkyIrradiance(ProbePoint, 6371.0, ProbeRay) * earthIncidentIrradiance(ProbeSource), 1.0)
                : vec4(earthSunTransmission(ProbePoint, 6371.0, ProbeRay, 0.00465), 1.0);
        return;
    }
    if (ProbeMode >= 9 && ProbeMode <= 12) {
        vec3 mass, tops, support;
        float density;
        if (ProbeMode == 9 || ProbeMode == 11) {
            density = earthCloudDensity(ProbePoint, ProbeRay, ProbeSource, ProbeFootprints.x,
                    ProbeFootprints.y, ProbeHeights, ProbeCalendar != 0, mass, tops, support);
        } else {
            density = earthCloudDensityEager(ProbePoint, ProbeRay, ProbeSource, ProbeFootprints.x,
                    ProbeFootprints.y, ProbeHeights, ProbeCalendar != 0, mass, tops, support);
        }
        if (ProbeMode <= 10) { fragColor = vec4(mass, density); }
        else {
            vec3 localHeight = clamp((vec3(ProbeAngles.x) - vec3(0.85, 1.3, 1.4))
                    / (tops - vec3(0.85, 1.3, 1.4)), 0.0, 1.0);
            float sourceHeight = dot(mass, localHeight) / max(0.00001, mass.x + mass.y + mass.z);
            fragColor = vec4(support, sourceHeight);
        }
        return;
    }
    if (ProbeMode == 8) {
        vec3 support = earthCloudOccupiedSupport(ProbePoint, ProbeRay, ProbeAngles.x, ProbeMass, ProbeTops);
        float source = earthCloudOccupiedPoint(ProbePoint, ProbeRay, ProbeAngles.x, ProbeMass, ProbeTops, ProbeAngles.y);
        fragColor = vec4(support, source);
        return;
    }
    if (ProbeMode == 7) {
        fragColor = vec4(earthMaterialResponse(vec3(ProbeSource)), earthCloudMaterialResponse().r);
        return;
    }
    if (ProbeMode == 6) {
        fragColor = vec4(earthCloudFilteredMass(vec3(ProbePoint.x), ProbePoint.y, ProbePoint.z), 1.0);
        return;
    }
    if (ProbeMode >= 4) {
        vec4 reconstructed = ProbeInverseView * vec4(probeClip, 1.0, 1.0);
        vec3 ray = normalize(reconstructed.xyz / reconstructed.w);
        float pixelAngle = max(length(dFdx(ray)), length(dFdy(ray))) * 0.65;
        pixelAngle = max(pixelAngle, 1.0 / max(ProbeScreenSize.x, ProbeScreenSize.y) * 0.25);
        vec3 bodyRay = ProbeBodyRotation * ray;
        if (ProbeCalendar == 0) {
            float c = cos(ProbeAngles.x), s = sin(ProbeAngles.x);
            vec3 tilted = vec3(ray.x, c * ray.y + s * ray.z, -s * ray.y + c * ray.z);
            c = cos(ProbeAngles.y); s = sin(ProbeAngles.y);
            bodyRay = vec3(c * tilted.x + s * tilted.z, tilted.y, -s * tilted.x + c * tilted.z);
        }
        float cloudDistance;
        vec4 transport = earthCloudTransport(CloudPlanet.xyz, bodyRay,
                length(CloudPlanet.xyz) + CloudPlanet.w + CloudLayer.y, pixelAngle, ProbeSteps, cloudDistance);
        fragColor = vec4(ProbeMode == 4 ? vec3(transport.a) : celestialDisplay(transport.rgb, 1.0), 1.0);
        return;
    }
    if (ProbeMode == 3) {
        fragColor = vec4(earthCloudScatteringFraction(ProbeSource), 0.0, 0.0, 1.0);
        return;
    }
    if (ProbeMode != 0) {
        vec3 transmission;
        vec3 scattering = earthAirScattering(vec3(6371.5, 0.0, 0.0), vec3(1.0, 0.0, 0.0), 6371.0,
                vec3(1.0, 0.0, 0.0), ProbeSource, 0.00465, 2.5, 12, transmission);
        fragColor = ProbeMode == 1 ? vec4(scattering, transmission.r) : vec4(transmission, 1.0);
        return;
    }
    fragColor = vec4(earthCloudRegion(ProbePoint, 0.0), earthIncidentIrradiance(1.0) / EARTH_PI);
}
