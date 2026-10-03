#version 150
in vec3 Position;
out vec2 probeClip;
void main() { probeClip = Position.xy; gl_Position = vec4(Position.xy, 1.0, 1.0); }
