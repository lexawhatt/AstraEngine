#version 150
uniform sampler2D LandscapeColor;
in vec2 clipPosition;
out vec4 fragColor;
void main() {
    vec4 color = texture(LandscapeColor, clipPosition * 0.5 + 0.5);
    if (color.a < 0.5) { discard; }
    fragColor = color;
}
