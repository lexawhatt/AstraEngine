// Closed analytic solids in normalized local coordinates. Directions deliberately
// remain unnormalized so returned distances still use camera-relative block units.
float shipBox(vec3 origin, vec3 direction, out vec3 normal) {
    float enter = -1e20;
    float leave = 1e20;
    for (int axis = 0; axis < 3; axis++) {
        if (abs(direction[axis]) < 1e-9) {
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
    vec3 point = origin + direction * distance;
    vec3 edge = abs(point);
    normal = edge.x >= edge.y && edge.x >= edge.z ? vec3(sign(point.x), 0, 0)
            : edge.y >= edge.z ? vec3(0, sign(point.y), 0) : vec3(0, 0, sign(point.z));
    return distance;
}

void shipFrustumSide(vec3 origin, vec3 direction, float distance, float slope,
        float middle, inout float nearest, inout vec3 normal) {
    if (distance < 0.0 || distance >= nearest) { return; }
    vec3 point = origin + direction * distance;
    if (abs(point.y) > 1.000001) { return; }
    float radius = max(0.0, middle + slope * point.y);
    nearest = distance;
    normal = vec3(point.x, -radius * slope, point.z);
    if (dot(normal, normal) < 1e-16) { normal = vec3(0, slope < 0.0 ? 1.0 : -1.0, 0); }
}

float shipFrustum(vec3 origin, vec3 direction, vec2 radii, out vec3 normal) {
    float slope = (radii.y - radii.x) * 0.5;
    float middle = (radii.y + radii.x) * 0.5;
    float radius = middle + slope * origin.y;
    float a = dot(direction.xz, direction.xz) - slope * slope * direction.y * direction.y;
    float b = dot(origin.xz, direction.xz) - radius * slope * direction.y;
    float c = dot(origin.xz, origin.xz) - radius * radius;
    float nearest = 1e20;
    normal = vec3(0, 1, 0);
    if (abs(a) < 1e-10) {
        if (abs(b) > 1e-10) { shipFrustumSide(origin, direction, -c / (2.0 * b), slope, middle, nearest, normal); }
    } else {
        float discriminant = b * b - a * c;
        if (discriminant >= 0.0) {
            float root = sqrt(max(0.0, discriminant));
            // Stable quadratic evaluation retains thin distant surfaces and near-linear cones.
            float q = -b - (b < 0.0 ? -root : root);
            float first = abs(q) > 1e-12 ? q / a : -b / a;
            float second = abs(q) > 1e-12 ? c / q : first;
            shipFrustumSide(origin, direction, min(first, second), slope, middle, nearest, normal);
            shipFrustumSide(origin, direction, max(first, second), slope, middle, nearest, normal);
        }
    }
    if (abs(direction.y) > 1e-10) {
        for (int side = 0; side < 2; side++) {
            float height = side == 0 ? -1.0 : 1.0;
            float distance = (height - origin.y) / direction.y;
            if (distance < 0.0 || distance >= nearest) { continue; }
            vec3 point = origin + direction * distance;
            float capRadius = side == 0 ? radii.x : radii.y;
            if (dot(point.xz, point.xz) <= capRadius * capRadius + 1e-7) {
                nearest = distance;
                normal = vec3(0, height, 0);
            }
        }
    }
    return nearest < 1e19 ? nearest : -1.0;
}

float shipMarker(vec3 origin, vec3 direction, vec3 center, float radius) {
    vec3 offset = origin - center;
    float along = dot(offset, direction);
    float discriminant = along * along - dot(offset, offset) + radius * radius;
    if (discriminant < 0.0) { return -1.0; }
    float root = sqrt(discriminant);
    float distance = -along - root;
    return distance >= 0.0 ? distance : -along + root;
}
