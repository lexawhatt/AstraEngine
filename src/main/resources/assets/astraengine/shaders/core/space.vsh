#version 150

in vec3 Position;
out vec2 clipPosition;

void main() {
    clipPosition = Position.xy;
    gl_Position = vec4(Position.xy, 1.0, 1.0);
}
