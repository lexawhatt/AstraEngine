#version 150
uniform sampler2D SceneColor;
uniform sampler2D BloomColor;
uniform float BloomStrength;
uniform float Exposure;
in vec2 clipPosition;
out vec4 fragColor;
#moj_import <astraengine:celestial_display.glsl>
void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec3 radiance = texture(SceneColor, uv).rgb;
    if (BloomStrength > 0.0) { radiance += texture(BloomColor, uv).rgb * BloomStrength; }
    fragColor = vec4(celestialDisplay(radiance, Exposure), 1.0);
}
