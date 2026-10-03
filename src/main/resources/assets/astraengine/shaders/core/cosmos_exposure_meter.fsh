#version 150
uniform sampler2D Source;
uniform int Extract;
uniform vec2 MeterSize;
in vec2 clipPosition;
out vec4 fragColor;
void main() {
    // First pass: fixed 256x256 stratified samples, reduced to 64x64. Following passes
    // average each 4x4 block exactly. Never average encoded RGB before measuring luminance.
    vec2 cell = floor((clipPosition * 0.5 + 0.5) * MeterSize);
    vec2 total = vec2(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec2 uv = (cell + (vec2(x, y) + 0.5) * 0.25) / MeterSize;
            vec3 value = texture(Source, uv).rgb;
            if (Extract != 0) {
                float luminance = clamp(dot(max(value, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722)), 0.0001, 4096.0);
                total += vec2(log2(luminance), min(luminance, 16.0));
            } else {
                total += value.rg;
            }
        }
    }
    fragColor = vec4(total / 16.0, 0.0, 1.0);
}
