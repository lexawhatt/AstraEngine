#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 RelativeX;
uniform vec3 RelativeZ;
uniform vec3 RelativeDenominator;
uniform float RelativeY;
out vec4 vertexColor;
out vec2 texCoord0;
void main() {
    vec3 local = vec3(1.0, Position.xz);
    float denominator = dot(RelativeDenominator, local);
    vec3 relative = vec3(dot(RelativeX, local) / denominator, RelativeY + Position.y,
                         dot(RelativeZ, local) / denominator);
    gl_Position = ProjMat * ModelViewMat * vec4(relative, 1.0);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
}
