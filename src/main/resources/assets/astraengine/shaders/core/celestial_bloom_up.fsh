#version 150
uniform sampler2D Source;
uniform sampler2D DetailColor;
uniform vec2 SourceTexel;
uniform float Scatter;
in vec2 clipPosition;
out vec4 fragColor;
void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec2 t = SourceTexel;
    vec3 color = texture(Source, uv).rgb * 4.0;
    color += (texture(Source, uv + t * vec2(-1, 0)).rgb + texture(Source, uv + t * vec2(1, 0)).rgb
            + texture(Source, uv + t * vec2(0, -1)).rgb + texture(Source, uv + t * vec2(0, 1)).rgb) * 2.0;
    color += texture(Source, uv + t * vec2(-1, -1)).rgb + texture(Source, uv + t * vec2(1, -1)).rgb
           + texture(Source, uv + t * vec2(-1, 1)).rgb + texture(Source, uv + t * vec2(1, 1)).rgb;
    fragColor = vec4(mix(texture(DetailColor, uv).rgb, color / 16.0, Scatter), 1.0);
}
