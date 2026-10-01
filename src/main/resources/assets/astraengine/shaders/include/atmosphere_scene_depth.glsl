// Main-view-only borrowed distant terrain depth uses its own projection. Raw depth
// values from the two targets must never be compared. Host geometry covers the background.
uniform sampler2D DistantDepth;
uniform int DistantReady;
uniform mat4 DistantInverseViewProjection;
float atmosphereSceneDistance(vec2 uv) {
    float hostDepth = texture(SceneDepth, uv).r;
    if (hostDepth < 0.999999) {
        vec4 point = InverseViewProjection * vec4(uv * 2.0 - 1.0, hostDepth * 2.0 - 1.0, 1.0);
        return length(point.xyz / point.w);
    }
    if (DistantReady != 0) {
        float distantDepth = texture(DistantDepth, uv).r;
        if (distantDepth < 1.0) {
            vec4 point = DistantInverseViewProjection * vec4(uv * 2.0 - 1.0, distantDepth * 2.0 - 1.0, 1.0);
            return length(point.xyz / point.w);
        }
    }
    return -1.0;
}
