#version 150
uniform sampler2D Meter;
uniform sampler2D Previous;
uniform int Initialized;
uniform float FrameSeconds;
in vec2 clipPosition;
out vec4 fragColor;
void main() {
    vec2 measured = texture(Meter, vec2(0.5)).rg;
    // The arithmetic floor keeps a bright planetary crescent from being ignored by
    // a predominantly black frame. The capped mean limits isolated photospheres.
    float luminance = max(exp2(measured.x), measured.y * 0.25);
    float targetEv = clamp(log2(0.18 / max(luminance, 0.0001)), -6.0, 2.0);
    float previousEv = Initialized != 0 ? texture(Previous, vec2(0.5)).r : targetEv;
    float responseSeconds = targetEv < previousEv ? 0.35 : 1.5;
    float weight = 1.0 - exp(-max(FrameSeconds, 0.0) / responseSeconds);
    fragColor = vec4(mix(previousEv, targetEv, weight), targetEv, luminance, 1.0);
}
