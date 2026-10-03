package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.CubeStorageChart;
import dev.lexawhatt.astraengine.surface.EarthChart;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import dev.lexawhatt.astraengine.surface.SolidPlanetProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Bounded numeric wire representation; receipt supplies identity only, never permission to allocate a world. */
public final class CubeStorageCharts {
    private CubeStorageCharts() { }

    public static void write(RegistryFriendlyByteBuf buffer, CubeStorageChart chart) {
        if (chart instanceof EarthChart earth) {
            buffer.writeByte(0); buffer.writeVarInt(earth.terrainVersion());
        } else if (chart instanceof PlanetChart planet) {
            buffer.writeByte(1);
            var profile = planet.profile();
            buffer.writeVarInt(profile.version()); buffer.writeUtf(profile.systemId(), 160); buffer.writeUtf(profile.bodyId(), 64);
            buffer.writeLong(profile.seed()); buffer.writeDouble(profile.radiusMeters()); buffer.writeUtf(profile.kind().name(), 16);
            buffer.writeDouble(profile.rotationSeconds()); buffer.writeDouble(profile.axialTiltRadians()); buffer.writeFloat(profile.atmosphereStrength());
        } else { throw new IllegalArgumentException("Unsupported cube storage chart type"); }
        buffer.writeUtf(chart.face().id(), 2); buffer.writeVarInt(chart.band());
    }

    public static CubeStorageChart read(RegistryFriendlyByteBuf buffer) {
        int kind = buffer.readUnsignedByte();
        if (kind == 0) {
            int terrain = buffer.readVarInt();
            return new EarthChart(CubeFace.fromId(buffer.readUtf(2)), buffer.readVarInt(), terrain);
        }
        if (kind != 1) { throw new IllegalArgumentException("Unsupported cube storage wire tag"); }
        var profile = new SolidPlanetProfile(buffer.readVarInt(), buffer.readUtf(160), buffer.readUtf(64),
                buffer.readLong(), buffer.readDouble(), CelestialBody.Kind.valueOf(buffer.readUtf(16)),
                buffer.readDouble(), buffer.readDouble(), buffer.readFloat());
        return new PlanetChart(profile, CubeFace.fromId(buffer.readUtf(2)), buffer.readVarInt());
    }
}
