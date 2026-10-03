#version 150
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;
uniform sampler2D SkyColor;
uniform float SkyOpacity;
in vec2 clipPosition;
out vec4 fragColor;

void main() {
    // Matching full-size targets allow exact samples at terrain/hand silhouettes. An epsilon
    // below clear depth would erase sufficiently distant geometry, so retain every written depth.
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), textureSize(SceneColor, 0) - 1);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    vec4 scene = texelFetch(SceneColor, pixel, 0);
    fragColor = depth >= 1.0 ? mix(scene, texelFetch(SkyColor, pixel, 0), SkyOpacity) : scene;
}
