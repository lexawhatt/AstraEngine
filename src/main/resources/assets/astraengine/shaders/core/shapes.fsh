#version 150
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform mat4 InverseViewProjection;
uniform mat4 ViewProjection;
uniform int ShapeCount;
uniform vec4 ShapePositionKind[16];
uniform vec4 ShapeScaleInner[16];
uniform vec4 ShapeColorEmission[16];
uniform mat3 ShapeInverseRotation[16];
in vec2 clipPosition;
out vec4 fragColor;

vec3 positionAt(float depth) {
    vec4 p = InverseViewProjection * vec4(clipPosition, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

float sphereIntersection(vec3 origin, vec3 direction, out vec3 normal) {
    float a = dot(direction, direction);
    float b = dot(origin, direction);
    float c = dot(origin, origin) - 1.0;
    float discriminant = b * b - a * c;
    if (discriminant < 0.0) { return -1.0; }
    float root = sqrt(discriminant);
    float distance = (-b - root) / a;
    if (distance < 0.0) { distance = (-b + root) / a; }
    normal = origin + direction * distance;
    return distance;
}

float boxIntersection(vec3 origin, vec3 direction, out vec3 normal) {
    float enter = -1e20;
    float leave = 1e20;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(direction[axis]) < 1e-8) {
            if (abs(origin[axis]) > 1.0) { return -1.0; }
        } else {
            float a = (-1.0 - origin[axis]) / direction[axis];
            float b = (1.0 - origin[axis]) / direction[axis];
            enter = max(enter, min(a, b));
            leave = min(leave, max(a, b));
        }
    }
    if (leave < max(enter, 0.0)) { return -1.0; }
    float distance = enter >= 0.0 ? enter : leave;
    vec3 hit = origin + direction * distance;
    vec3 edge = abs(hit);
    if (edge.x >= edge.y && edge.x >= edge.z) { normal = vec3(sign(hit.x), 0, 0); }
    else if (edge.y >= edge.z) { normal = vec3(0, sign(hit.y), 0); }
    else { normal = vec3(0, 0, sign(hit.z)); }
    return distance;
}

// Disks and annuli are double-sided surfaces in local XZ, without artificial thickness.
float diskIntersection(vec3 origin, vec3 direction, float inner, out vec3 normal) {
    if (abs(direction.y) < 1e-8) { return -1.0; }
    float distance = -origin.y / direction.y;
    vec3 hit = origin + direction * distance;
    float radiusSquared = dot(hit.xz, hit.xz);
    if (distance < 0.0 || radiusSquared > 1.0 || radiusSquared < inner * inner) { return -1.0; }
    normal = vec3(0, direction.y < 0.0 ? 1.0 : -1.0, 0);
    return distance;
}

void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    float sceneDepth = texture(SceneDepth, uv).r;
    vec3 rayOrigin = positionAt(0.0);
    vec3 scenePosition = positionAt(sceneDepth);
    vec3 rayDirection = normalize(positionAt(1.0) - rayOrigin);
    float nearest = length(scenePosition - rayOrigin);
    int selected = -1;
    vec3 selectedNormal = vec3(0, 1, 0);
    for (int i = 0; i < 16; i++) {
        if (i >= ShapeCount) { break; }
        vec3 scale = ShapeScaleInner[i].xyz;
        mat3 inverseRotation = ShapeInverseRotation[i];
        vec3 origin = (inverseRotation * (rayOrigin - ShapePositionKind[i].xyz)) / scale;
        // Keep this direction unnormalized so intersection distance remains in world blocks.
        vec3 direction = (inverseRotation * rayDirection) / scale;
        int kind = int(ShapePositionKind[i].w);
        vec3 normal;
        float distance;
        if (kind == 0) { distance = sphereIntersection(origin, direction, normal); }
        else if (kind == 1) { distance = boxIntersection(origin, direction, normal); }
        else { distance = diskIntersection(origin, direction, kind == 2 ? ShapeScaleInner[i].w : 0.0, normal); }
        if (distance >= 0.0 && distance < nearest) {
            nearest = distance;
            selected = i;
            selectedNormal = normalize(transpose(inverseRotation) * (normal / scale));
        }
    }
    fragColor = texture(SceneColor, uv);
    gl_FragDepth = sceneDepth;
    if (selected < 0) { return; }
    vec3 position = rayOrigin + rayDirection * nearest;
    vec4 clip = ViewProjection * vec4(position, 1.0);
    float depth = clip.z / clip.w * 0.5 + 0.5;
    if (clip.w <= 0.0 || depth < 0.0 || depth > sceneDepth) { return; }
    gl_FragDepth = depth;
    vec4 material = ShapeColorEmission[selected];
    // A modest hemisphere base makes unlit shapes readable; subsequent lighting uses written depth.
    float ambient = 0.45 + 0.25 * max(selectedNormal.y, 0.0);
    vec3 color = material.rgb * (ambient + 1.0 - exp(-material.a));
    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
