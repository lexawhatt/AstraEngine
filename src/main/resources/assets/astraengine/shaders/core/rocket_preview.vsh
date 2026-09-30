#version 150
in vec3 Position;
out vec2 clipPosition;
void main() {
    gl_Position = vec4(Position, 1.0);
    clipPosition = Position.xy;
}
