#version 150
uniform sampler2D Source;
uniform vec2 SourceTexel;
uniform int Extract;
uniform float Threshold;
in vec2 clipPosition;
out vec4 fragColor;

vec3 sampleRadiance(vec2 uv) {
    vec4 source = texture(Source, uv);
    vec3 value = max(source.rgb, vec3(0.0));
    if (Extract == 0) { return value; }
    // Alpha is bloom eligibility in owned HDR sources, not world transparency. Other celestial
    // sources write one; host-matched non-emissive Earth terrain can explicitly exclude itself.
    value *= clamp(source.a, 0.0, 1.0);
    float peak = max(value.r, max(value.g, value.b));
    float knee = max(Threshold * 0.5, 0.0001);
    float soft = clamp(peak - Threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 0.0001);
    return value * (max(peak - Threshold, soft) / max(peak, 0.0001));
}

void main() {
    vec2 uv = clipPosition * 0.5 + 0.5;
    vec2 t = SourceTexel;
    // Thirteen taps suppress blocky halos and retain bright subpixel sources while
    // avoiding the directional streak introduced by a single two-pass Gaussian.
    vec3 color = sampleRadiance(uv) * 0.125;
    color += (sampleRadiance(uv + t * vec2(-2, -2)) + sampleRadiance(uv + t * vec2(2, -2))
            + sampleRadiance(uv + t * vec2(-2, 2)) + sampleRadiance(uv + t * vec2(2, 2))) * 0.03125;
    color += (sampleRadiance(uv + t * vec2(-2, 0)) + sampleRadiance(uv + t * vec2(2, 0))
            + sampleRadiance(uv + t * vec2(0, -2)) + sampleRadiance(uv + t * vec2(0, 2))) * 0.0625;
    color += (sampleRadiance(uv + t * vec2(-1, -1)) + sampleRadiance(uv + t * vec2(1, -1))
            + sampleRadiance(uv + t * vec2(-1, 1)) + sampleRadiance(uv + t * vec2(1, 1))) * 0.125;
    fragColor = vec4(color, 1.0);
}
