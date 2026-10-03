package dev.lexawhatt.astraengine.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;

/** Strict saved profile encoding shared by generator definitions and the permanent allocation manifest. */
public final class PlanetProfileCodec {
    private static final Codec<CelestialBody.Kind> KIND = Codec.STRING.comapFlatMap(value -> {
        try { return DataResult.success(CelestialBody.Kind.valueOf(value)); }
        catch (IllegalArgumentException exception) { return DataResult.error(() -> "Unknown solid-planet material: " + value); }
    }, Enum::name);
    private static final Codec<Definition> DEFINITION = RecordCodecBuilder.create(instance -> instance.group(
            TerrainCodecs.EXACT_INT.fieldOf("version").forGetter(Definition::version),
            Codec.STRING.fieldOf("system").forGetter(Definition::system),
            Codec.STRING.fieldOf("body").forGetter(Definition::body),
            TerrainCodecs.EXACT_LONG.fieldOf("seed").forGetter(Definition::seed),
            Codec.DOUBLE.fieldOf("radius_meters").forGetter(Definition::radius),
            KIND.fieldOf("kind").forGetter(Definition::kind),
            Codec.DOUBLE.fieldOf("rotation_seconds").forGetter(Definition::rotation),
            Codec.DOUBLE.fieldOf("axial_tilt_radians").forGetter(Definition::tilt),
            Codec.FLOAT.fieldOf("atmosphere").forGetter(Definition::atmosphere)
    ).apply(instance, Definition::new));

    public static final Codec<SolidPlanetProfile> CODEC = DEFINITION.comapFlatMap(value -> {
        try { return DataResult.success(new SolidPlanetProfile(value.version(), value.system(), value.body(),
                value.seed(), value.radius(), value.kind(), value.rotation(), value.tilt(), value.atmosphere())); }
        catch (IllegalArgumentException exception) { return DataResult.error(exception::getMessage); }
    }, value -> new Definition(value.version(), value.systemId(), value.bodyId(), value.seed(), value.radiusMeters(),
            value.kind(), value.rotationSeconds(), value.axialTiltRadians(), value.atmosphereStrength()));

    private PlanetProfileCodec() { }
    private record Definition(int version, String system, String body, long seed, double radius,
                              CelestialBody.Kind kind, double rotation, double tilt, float atmosphere) { }
}
