// One atlas and billow model for the legacy slab and canonical spherical Earth.
uniform vec2 CloudLayer;
#moj_import <astraengine:cloud_noise.glsl>

float cloudResolvedNoise(vec3 p, float footprint) {
    float resolved = 1.0 - smoothstep(0.25, 1.0, footprint);
    if (resolved <= 0.0) { return 0.5; }
    return mix(0.5, cloudVolumeNoise(p), resolved);
}

float cloudDensityAt(vec3 p, float altitudeKm, float coverage, bool fineDetail, float footprintKm) {
    float height = (altitudeKm - CloudLayer.x) / (CloudLayer.y - CloudLayer.x);
    if (height <= 0.0 || height >= 1.0 || coverage <= 0.001) { return 0.0; }
    float broad = cloudResolvedNoise(p * vec3(2.0, 2.4, 2.0) + vec3(0.0, 11.7, 0.0), footprintKm * 2.4);
    float billowWeight = 1.0 - smoothstep(0.25, 1.0, footprintKm * 6.0);
    float billows = 0.70;
    if (billowWeight > 0.0) {
        float resolved = cloudVolumeNoise(p * 6.0 + vec3(0.0, 31.1, 0.0));
        billows = mix(0.70, 1.0 - abs(resolved * 2.0 - 1.0), billowWeight);
    }
    float field = broad * 0.70 + billows * 0.30 - 0.055;
    float threshold = mix(0.72, 0.22, coverage) + height * height * 0.12;
    float body = max(0.0, field - threshold);
    if (body <= 0.0) { return 0.0; }
    if (fineDetail) {
        float erosion = cloudResolvedNoise(p * 24.0 + vec3(0.0, 71.3, 0.0), footprintKm * 24.0);
        body = max(0.0, body - (1.0 - erosion) * 0.13);
    }
    return (1.0 - exp(-body * 6.0)) * smoothstep(0.0, 0.13, height)
            * (1.0 - smoothstep(0.54, 1.0, height));
}
