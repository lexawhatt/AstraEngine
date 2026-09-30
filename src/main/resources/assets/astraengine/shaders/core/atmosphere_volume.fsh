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

vec3 positionAt(vec2 uv, float depth) {
    vec4 value = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return value.xyz / value.w;
}

void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec3 ray = normalize(positionAt(uv, 1.0));
    float distanceKm = 16.0;
    if (GeometryPass == 1) {
        float depth = texture(SceneDepth, uv).r;
        // Conservative nearest surface across the reduced pixel's footprint.
        // This prevents a distant sky sample from adding light over a thin wall.
        vec2 offset = max(vec2(0.0), (VolumeTexel - SceneTexel) * 0.5);
        depth = min(depth, texture(SceneDepth, uv + offset).r);
        depth = min(depth, texture(SceneDepth, uv - offset).r);
        depth = min(depth, texture(SceneDepth, uv + vec2(offset.x, -offset.y)).r);
        depth = min(depth, texture(SceneDepth, uv + vec2(-offset.x, offset.y)).r);
        if (depth >= 0.999999 || SkyAccess <= 0.0) { fragColor = vec4(0, 0, 0, 1); return; }
        distanceKm = min(length(positionAt(uv, depth)) * 0.001, distanceKm);
    }
    fragColor = cloudTransport(ObserverKm, ray, distanceKm, CloudParams.x, CloudParams.y,
                              CloudParams.z, CloudParams.w, ShaftStrength, ViewSteps, ShadowSteps);
    if (GeometryPass == 1) {
        // Camera-local sky access is a bounded interior safeguard, not a terrain shadow map.
        fragColor = mix(vec4(0, 0, 0, 1), fragColor, SkyAccess);
    }
}
