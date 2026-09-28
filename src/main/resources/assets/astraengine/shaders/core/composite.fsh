#version 150
uniform sampler2D SceneColor;
uniform sampler2D BloomColor;
uniform float BloomStrength;
uniform float Exposure;
in vec2 clipPosition;
out vec4 fragColor;
void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec4 scene = texture(SceneColor, uv);
    vec3 color = scene.rgb + texture(BloomColor, uv).rgb * BloomStrength;
    color = 1.0 - pow(max(vec3(0), vec3(1) - min(color, vec3(1))), vec3(Exposure));
    fragColor = vec4(color, scene.a);
}
