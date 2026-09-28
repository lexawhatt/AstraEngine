#version 150
uniform sampler2D Source;
uniform vec2 TexelStep;
uniform int Extract;
in vec2 clipPosition;
out vec4 fragColor;
vec3 sampleColor(vec2 uv) {
    vec3 c = texture(Source, uv).rgb;
    if (Extract == 1) {
        float bright = max(c.r, max(c.g, c.b));
        c *= smoothstep(0.55, 1.0, bright);
    }
    return c;
}
void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec3 c = sampleColor(uv) * 0.227027;
    c += sampleColor(uv + TexelStep * 2.769230) * 0.316216;
    c += sampleColor(uv - TexelStep * 2.769230) * 0.316216;
    c += sampleColor(uv + TexelStep * 6.461538) * 0.070270;
    c += sampleColor(uv - TexelStep * 6.461538) * 0.070270;
    fragColor = vec4(c, 1.0);
}
