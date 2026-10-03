#version 150
uniform sampler2D SceneDepth;
uniform mat4 InverseViewProjection;
uniform vec3 ObserverKm;
uniform vec3 SunDirection;
uniform vec2 CloudWind;
uniform vec4 CloudParams;
uniform vec2 Weather;
uniform vec2 SceneTexel;
uniform vec2 VolumeTexel;
const int Detail = 4;
uniform int ViewSteps;
uniform int ShadowSteps;
uniform int GeometryPass;
uniform float ShaftStrength;
uniform float SkyAccess;
in vec2 clipPosition;
out vec4 fragColor;
const float PI = 3.14159265359;
const vec2 CloudOffset = vec2(0.0);
#moj_import <astraengine:atmosphere.glsl>
#moj_import <astraengine:cloud_volume.glsl>
#moj_import <astraengine:earth_optics.glsl>
#moj_import <astraengine:earth_clouds.glsl>
#moj_import <astraengine:atmosphere_scene_depth.glsl>

vec3 positionAt(vec2 uv, float depth) {
    vec4 value = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return value.xyz / value.w;
}

void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec3 ray = normalize(positionAt(uv, 1.0));
    float distanceKm = CloudPlanet.w > 0.0 ? 20000.0 : 16.0;
    if (GeometryPass == 1) {
        float distanceMeters = atmosphereSceneDistance(uv);
        // Conservative nearest surface across the reduced pixel's footprint.
        vec2 offset = max(vec2(0.0), (VolumeTexel - SceneTexel) * 0.5);
        for (int y = -1; y <= 1; y += 2) {
            for (int x = -1; x <= 1; x += 2) {
                float candidate = atmosphereSceneDistance(uv + offset * vec2(x, y));
                if (candidate >= 0.0) { distanceMeters = distanceMeters < 0.0 ? candidate : min(distanceMeters, candidate); }
            }
        }
        if (distanceMeters < 0.0 || SkyAccess <= 0.0) { fragColor = vec4(0, 0, 0, 1); return; }
        distanceKm = min(distanceMeters * 0.001, distanceKm);
    }
    if (CloudPlanet.w > 0.0) {
        float cloudDistance;
        vec3 bodyRay = normalize(CloudLocalToBody * ray);
        float pixelAngle = max(length(dFdx(bodyRay)), length(dFdy(bodyRay)));
        fragColor = earthCloudTransport(CloudPlanet.xyz, bodyRay, distanceKm, pixelAngle, ViewSteps, cloudDistance);
        // Existing scene radiance already includes the clear foreground atmosphere. Scalar cloud
        // transmission multiplies that scene during composition, so restore only its occluded share:
        // T_cloud * existingSky + T_air * cloudLight + (1 - T_cloud) * foregroundAir.
        // Integrating the entire sky here would count its unoccluded scattering twice.
        if (cloudDistance > 0.0) {
            vec3 foregroundTransmission;
            vec3 foregroundScattering = earthAirScattering(CloudPlanet.xyz, bodyRay, CloudPlanet.w,
                    EarthCloudSun, EarthCloudParams.w, 0.00465, cloudDistance, 12, foregroundTransmission);
            fragColor.rgb = fragColor.rgb * foregroundTransmission + (1.0 - fragColor.a) * foregroundScattering;
        }
        float airStart;
        vec4 air = earthCloudAirTransport(CloudPlanet.xyz, bodyRay, distanceKm, ShaftStrength, airStart);
        if (cloudDistance <= 0.0 || airStart <= cloudDistance) {
            fragColor.rgb = air.rgb + air.a * fragColor.rgb;
        } else { fragColor.rgb += fragColor.a * air.rgb; }
        fragColor.a *= air.a;
    } else {
        fragColor = cloudTransport(ObserverKm, ray, distanceKm, CloudParams.x, CloudParams.y,
                                  CloudParams.z, CloudParams.w, ShaftStrength, ViewSteps, ShadowSteps);
    }
    if (GeometryPass == 1) {
        // Camera-local sky access is a bounded interior safeguard, not a terrain shadow map.
        fragColor = mix(vec4(0, 0, 0, 1), fragColor, SkyAccess);
    }
}
