#version 150
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform mat4 InverseViewProjection;
uniform mat4 ViewProjection;
uniform int PreviewMode;
uniform int PartCount;
uniform int SelectedPart;
uniform vec4 PartPositionKind[32];
uniform vec4 PartSizeMaterial[32];
uniform vec4 PartRotationRadii[32];
uniform vec3 PartColor[32];
uniform vec4 GridPlane;
uniform vec3 AssemblyCenter;
uniform float AssemblyRadius;
uniform int MarkerCount;
uniform vec3 MarkerPosition[6];
in vec2 clipPosition;
out vec4 fragColor;

#moj_import <astraengine:rocket_shapes.glsl>

vec3 rocketPositionAt(float depth) {
    vec4 position = InverseViewProjection * vec4(clipPosition, depth * 2.0 - 1.0, 1.0);
    return position.xyz / position.w;
}

mat3 rocketInverseRotation(vec2 rotation) {
    float c = rotation.x;
    float s = rotation.y;
    return mat3(c, 0, s, 0, 1, 0, -s, 0, c);
}

float rocketLine(float value, float footprint) {
    float edge = abs(fract(value + 0.5) - 0.5);
    return 1.0 - smoothstep(max(0.0, footprint * 0.5), footprint * 1.5 + 0.003, edge);
}

vec3 rocketMaterial(int index, vec3 local, vec3 normal, vec3 ray, vec3 footprint) {
    int material = int(PartSizeMaterial[index].w + 0.5);
    vec3 color = PartColor[index];
    float band = 1.0 - smoothstep(0.88, 0.98, abs(local.y));
    color *= mix(0.40, 1.0, band);
    float angle = dot(local.xz, local.xz) > 1e-12 ? atan(local.z, local.x) / 6.2831853 + 0.5 : 0.5;
    float panel = rocketLine(angle * 12.0, max(footprint.x, footprint.z) * 3.0) * 0.18;
    float seam = rocketLine((local.y + 1.0) * 3.0, footprint.y) * 0.12;
    color *= 1.0 - max(panel, seam);
    if (material == 2) {
        float window = smoothstep(0.03, 0.12, local.y) * (1.0 - smoothstep(0.56, 0.65, local.y))
                     * smoothstep(0.38, 0.50, abs(local.x)) * (1.0 - smoothstep(0.82, 0.92, abs(local.x)));
        color = mix(color, vec3(0.014, 0.065, 0.10), window);
    } else if (material == 3) {
        float stripe = smoothstep(-0.19, -0.15, local.y) * (1.0 - smoothstep(0.13, 0.17, local.y));
        color = mix(color, vec3(0.96, 0.34, 0.07), stripe * 0.88);
    } else if (material == 4) {
        float grooves = rocketLine(angle * 32.0, max(footprint.x, footprint.z) * 8.0);
        color *= 0.75 + grooves * 0.25;
        color = mix(color, vec3(0.13, 0.09, 0.055), smoothstep(-0.6, 0.3, local.y) * 0.35);
    } else if (material == 5) {
        vec3 size = PartSizeMaterial[index].xyz;
        vec2 surface = size.z <= min(size.x, size.y) ? local.xy
                : size.y <= size.x ? local.xz : local.yz;
        vec2 surfaceFootprint = size.z <= min(size.x, size.y) ? footprint.xy
                : size.y <= size.x ? footprint.xz : footprint.yz;
        float cells = max(rocketLine(surface.x * 7.0, surfaceFootprint.x * 7.0),
                rocketLine(surface.y * 10.0, surfaceFootprint.y * 10.0));
        color = mix(vec3(0.013, 0.035, 0.12), vec3(0.40, 0.52, 0.60), cells * 0.55);
    } else if (material == 6) {
        float fins = rocketLine(local.y * 18.0, footprint.y * 18.0);
        color *= 0.40 + fins * 0.60;
    }
    vec3 key = normalize(vec3(-0.55, 0.85, 0.7));
    vec3 fill = normalize(vec3(0.75, 0.25, -0.7));
    float diffuse = max(0.0, dot(normal, key));
    float fillLight = max(0.0, dot(normal, fill));
    float specular = pow(max(0.0, dot(reflect(-key, normal), -ray)), material == 5 ? 90.0 : 42.0);
    color = color * (0.24 + diffuse * 0.63 + fillLight * 0.16) + vec3(0.80, 0.88, 1.0) * specular * 0.22;
    if (index == SelectedPart) {
        float rim = pow(1.0 - abs(dot(normal, -ray)), 2.0);
        color = mix(color, vec3(0.12, 0.65, 0.90), 0.13) + vec3(0.07, 0.31, 0.42) * rim;
    }
    return clamp(color, 0.0, 1.0);
}

void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    float sceneDepth = PreviewMode == 1 ? 1.0 : texture(SceneDepth, uv).r;
    vec3 origin = rocketPositionAt(0.0);
    vec3 direction = normalize(rocketPositionAt(1.0) - origin);
    float pixelAngle = max(length(dFdx(direction)), length(dFdy(direction)));
    float nearest = length(rocketPositionAt(sceneDepth) - origin);
    vec3 color = PreviewMode == 1 ? mix(vec3(0.022, 0.028, 0.039), vec3(0.055, 0.070, 0.088), uv.y)
            : texture(SceneColor, uv).rgb;
    int selected = -1;
    vec3 selectedNormal = vec3(0, 1, 0);
    vec3 selectedLocal = vec3(0);
    for (int index = 0; index < 32; index++) {
        if (index >= PartCount) { break; }
        vec3 halfSize = PartSizeMaterial[index].xyz;
        mat3 inverseRotation = rocketInverseRotation(PartRotationRadii[index].xy);
        vec3 localOrigin = (inverseRotation * (origin - PartPositionKind[index].xyz)) / halfSize;
        vec3 localDirection = (inverseRotation * direction) / halfSize;
        vec3 normal;
        float distance = int(PartPositionKind[index].w + 0.5) == 2
                ? rocketBox(localOrigin, localDirection, normal)
                : rocketFrustum(localOrigin, localDirection, PartRotationRadii[index].zw, normal);
        if (distance >= 0.0 && distance < nearest) {
            selected = index;
            nearest = distance;
            selectedLocal = localOrigin + localDirection * distance;
            selectedNormal = normalize(transpose(inverseRotation) * (normal / halfSize));
        }
    }
    gl_FragDepth = sceneDepth;
    if (PreviewMode == 1 && abs(direction.y) > 1e-7) {
        float gridDistance = (GridPlane.y - origin.y) / direction.y;
        if (gridDistance >= 0.0 && gridDistance < nearest) {
            vec3 point = origin + direction * gridDistance;
            vec2 grid = (point.xz - GridPlane.xz) / GridPlane.w;
            float width = clamp(pixelAngle * gridDistance / GridPlane.w, 0.004, 0.45);
            float line = max(rocketLine(grid.x, width), rocketLine(grid.y, width));
            float radial = length(point.xz - AssemblyCenter.xz) / max(AssemblyRadius * 3.0, 1.0);
            float fade = exp(-radial * radial) * min(1.0, 0.02 / max(width, 0.005));
            color += vec3(0.10, 0.15, 0.19) * line * fade;
            float shadow = exp(-length(point.xz - AssemblyCenter.xz) / max(AssemblyRadius * 0.45, 0.5));
            color *= 1.0 - shadow * 0.3;
        }
    }
    if (selected >= 0) {
        vec3 point = origin + direction * nearest;
        vec4 clip = ViewProjection * vec4(point, 1.0);
        float depth = clip.z / clip.w * 0.5 + 0.5;
        if (clip.w > 0.0 && depth >= 0.0 && depth <= sceneDepth) {
            gl_FragDepth = depth;
            color = rocketMaterial(selected, selectedLocal, selectedNormal, direction,
                    pixelAngle * nearest / max(vec3(0.025), PartSizeMaterial[selected].xyz));
        }
    }
    for (int marker = 0; marker < 6; marker++) {
        if (marker >= MarkerCount) { break; }
        float distance = rocketMarker(origin, direction, MarkerPosition[marker], max(0.045, AssemblyRadius * 0.008));
        if (distance >= 0.0 && distance < nearest) {
            color = marker < 2 ? vec3(0.23, 0.85, 0.58) : vec3(0.24, 0.67, 0.95);
            nearest = distance;
        }
    }
    fragColor = vec4(color, 1.0);
}
