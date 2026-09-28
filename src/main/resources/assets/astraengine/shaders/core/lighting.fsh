#version 150
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform mat4 InverseViewProjection;
uniform mat4 ViewProjection;
uniform vec2 ScreenSize;
uniform float BaseGain;
uniform int LightCount;
uniform int ShadowSteps;
uniform vec4 LightPosition[16];
uniform vec4 LightDirection[16];
uniform vec4 LightColor[16];
uniform vec2 LightParameters[16];
in vec2 clipPosition;
out vec4 fragColor;

vec3 positionAt(vec2 uv, float depth) {
    vec4 p = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

// Choose the neighbor on the same surface to avoid normals crossing depth discontinuities.
vec3 normalAt(vec2 uv, vec3 p) {
    vec2 stepUV = 1.0 / ScreenSize;
    vec2 left = uv - vec2(stepUV.x, 0), right = uv + vec2(stepUV.x, 0);
    vec2 down = uv - vec2(0, stepUV.y), up = uv + vec2(0, stepUV.y);
    vec3 l = positionAt(left, texture(SceneDepth, left).r);
    vec3 r = positionAt(right, texture(SceneDepth, right).r);
    vec3 d = positionAt(down, texture(SceneDepth, down).r);
    vec3 u = positionAt(up, texture(SceneDepth, up).r);
    vec3 dx = length(r - p) < length(p - l) ? r - p : p - l;
    vec3 dy = length(u - p) < length(p - d) ? u - p : p - d;
    vec3 n = cross(dx, dy);
    if (dot(n, n) < 0.00000001) { return normalize(-p); }
    n = normalize(n);
    return dot(n, -p) < 0.0 ? -n : n;
}

float visibility(vec3 p, vec3 n, vec3 toward, float distance) {
    if (ShadowSteps == 0) { return 1.0; }
    float reach = min(distance, 8.0);
    for (int i = 1; i <= 16; i++) {
        if (i > ShadowSteps) { break; }
        float t = float(i) / float(ShadowSteps);
        vec3 samplePosition = p + n * 0.07 + toward * (0.12 + reach * t * t);
        vec4 clip = ViewProjection * vec4(samplePosition, 1.0);
        if (clip.w <= 0.0) { break; }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0))) || any(greaterThan(uv, vec2(1)))) { break; }
        float depth = texture(SceneDepth, uv).r;
        if (depth >= 0.999999) { continue; }
        vec3 blocker = positionAt(uv, depth);
        float separation = length(samplePosition) - length(blocker);
        // Bounded thickness avoids claiming hidden geometry from the front depth layer.
        if (separation > 0.05 && separation < 0.45) { return 0.12; }
    }
    return 1.0;
}

void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec4 scene = texture(SceneColor, uv);
    float depth = texture(SceneDepth, uv).r;
    if (depth >= 0.999999) { fragColor = scene; return; }
    vec3 p = positionAt(uv, depth);
    vec3 n = normalAt(uv, p);
    vec3 illumination = vec3(0);
    for (int i = 0; i < 16; i++) {
        if (i >= LightCount) { break; }
        vec3 toward;
        float falloff = 1.0;
        float distance = 8.0;
        if (LightPosition[i].w == 0.0) {
            toward = LightDirection[i].xyz;
        } else {
            vec3 delta = LightPosition[i].xyz - p;
            distance = length(delta);
            toward = delta / max(distance, 0.0001);
            falloff = pow(max(0.0, 1.0 - distance / LightPosition[i].w), 2.0);
            if (LightDirection[i].w >= 0.0) {
                falloff *= smoothstep(LightDirection[i].w, LightParameters[i].x,
                                      dot(-toward, LightDirection[i].xyz));
            }
        }
        float diffuse = max(dot(n, toward), 0.0);
        if (falloff * diffuse < 0.001) { continue; }
        float shadow = LightParameters[i].y > 0.5 ? visibility(p, n, toward, distance) : 1.0;
        illumination += LightColor[i].rgb * LightColor[i].a * falloff * diffuse * shadow;
    }
    // Existing Minecraft shading supplies surface color; this is an additive visual lighting layer.
    vec3 base = clamp(scene.rgb * BaseGain, 0.0, 1.0);
    vec3 contribution = (scene.rgb + vec3(0.08)) * illumination;
    // Compress added energy into the remaining display range instead of clipping textured sunlit faces.
    vec3 lit = base + (vec3(1) - base) * (vec3(1) - exp(-contribution));
    fragColor = vec4(lit, scene.a);
}
