#version 150
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D Transport;
uniform mat4 InverseViewProjection;
uniform vec2 TransportSize;
uniform float Exposure;
in vec2 clipPosition;
out vec4 fragColor;
#moj_import <astraengine:celestial_display.glsl>

#moj_import <astraengine:atmosphere_scene_depth.glsl>

void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec4 scene = texture(SceneColor, uv);
    float distance = atmosphereSceneDistance(uv);
    if (distance < 0.0) { fragColor = scene; return; }
    vec2 grid = uv * TransportSize - 0.5;
    vec2 base = floor(grid);
    vec2 blend = fract(grid);
    vec4 integrated = vec4(0.0);
    float weight = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            vec2 sampleUV = (base + vec2(x, y) + 0.5) / TransportSize;
            float sampleDistance = atmosphereSceneDistance(sampleUV);
            if (sampleDistance < 0.0) { continue; }
            float difference = abs(sampleDistance - distance);
            float w = (x == 0 ? 1.0 - blend.x : blend.x) * (y == 0 ? 1.0 - blend.y : blend.y);
            w *= 1.0 - smoothstep(max(0.15, distance * 0.015), max(0.5, distance * 0.04), difference);
            integrated += texture(Transport, sampleUV) * w;
            weight += w;
        }
    }
    if (weight < 0.001) { fragColor = scene; return; }
    integrated /= weight;
    if (integrated.a > 0.99999 && dot(integrated.rgb, vec3(1.0)) < 0.00001) { fragColor = scene; return; }
    // Invert only the display shoulder for composition; do not expose/tonemap the
    // host scene again. This preserves an exact identity when transport is absent.
    vec3 radiance = celestialRadiance(scene.rgb) * clamp(integrated.a, 0.0, 1.0)
                  + max(integrated.rgb, vec3(0.0)) * Exposure;
    fragColor = vec4(celestialDisplay(radiance, 1.0), scene.a);
}
