#version 150
in vec3 Position;
in vec4 Color;
in vec3 Normal;
in vec2 UV0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 LocalTransform;
uniform vec3 FlatOffset;
uniform vec2 MaterialOffset;
out vec3 localPosition;
out vec3 localNormal;
out vec3 materialColor;
out vec2 materialPosition;
void main() {
    localPosition = mix(Position + FlatOffset, (LocalTransform * vec4(Position, 1.0)).xyz, UV0.x);
    localNormal = mix(Normal, mat3(LocalTransform) * Normal, UV0.x);
    materialColor = Color.rgb;
    materialPosition = Position.xz + MaterialOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(localPosition, 1.0);
}
