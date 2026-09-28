package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Strict version-one custom descriptor persistence and explicit bounded wire encoding.
 * Uses no logical-side state or client-only classes. Callers own tags and buffers; returned descriptors are immutable.
 * Invalid or missing fields, unknown versions/kinds and unsupported custom bounds throw before a descriptor is returned.
 */
public final class CosmosDescriptorCodec {
    public static final int VERSION = 1;

    private CosmosDescriptorCodec() {
    }

    /** Creates a new, fully populated NBT tag after validating the complete custom system. */
    public static CompoundTag encode(CosmosSystem system) {
        CelestialSystems.validateCustom(system);
        CompoundTag root = new CompoundTag();
        root.putInt("version", VERSION);
        root.putString("id", system.id());
        root.putString("name", system.name());
        root.putLong("seed", system.seed());
        root.putString("kind", system.kind().name());
        putVector(root, "galaxy_", system.galaxyPosition());
        ListTag bodies = new ListTag();
        for (CelestialBody body : system.bodies()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", body.id());
            tag.putString("name", body.name());
            tag.putString("kind", body.kind().name());
            tag.putDouble("radius_meters", body.radiusMeters());
            tag.putDouble("orbit_meters", body.orbitMeters());
            tag.putDouble("orbital_period_seconds", body.orbitalPeriodSeconds());
            tag.putDouble("phase_radians", body.phaseRadians());
            tag.putDouble("inclination_radians", body.inclinationRadians());
            tag.putDouble("eccentricity", body.eccentricity());
            putVector(tag, "color_", body.color());
            tag.putFloat("atmosphere", body.atmosphere());
            tag.putFloat("ring_inner_ratio", body.ringInnerRatio());
            tag.putFloat("ring_outer_ratio", body.ringOuterRatio());
            tag.putDouble("axial_tilt_radians", body.axialTiltRadians());
            bodies.add(tag);
        }
        root.put("bodies", bodies);
        return root;
    }

    /** Reads version-one NBT without defaulting missing fields or changing the input tag. Null is rejected. */
    public static CosmosSystem decode(CompoundTag root) {
        require(root, Tag.TAG_INT, "version");
        require(root, Tag.TAG_STRING, "id", "name", "kind");
        require(root, Tag.TAG_LONG, "seed");
        require(root, Tag.TAG_DOUBLE, "galaxy_x", "galaxy_y", "galaxy_z");
        require(root, Tag.TAG_LIST, "bodies");
        if (root.getInt("version") != VERSION) {
            throw new IllegalArgumentException("Unsupported custom descriptor version: " + root.getInt("version"));
        }
        ListTag entries = (ListTag) root.get("bodies");
        if (entries.isEmpty() || entries.size() > CelestialSystems.MAX_BODIES
                || entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Custom descriptor bodies must contain 1..12 compounds");
        }
        List<CelestialBody> bodies = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            require(tag, Tag.TAG_STRING, "id", "name", "kind");
            require(tag, Tag.TAG_DOUBLE, "radius_meters", "orbit_meters", "orbital_period_seconds", "phase_radians",
                    "inclination_radians", "eccentricity", "color_x", "color_y", "color_z", "axial_tilt_radians");
            require(tag, Tag.TAG_FLOAT, "atmosphere", "ring_inner_ratio", "ring_outer_ratio");
            bodies.add(new CelestialBody(tag.getString("id"), tag.getString("name"),
                    enumName(CelestialBody.Kind.class, tag.getString("kind")), tag.getDouble("radius_meters"),
                    tag.getDouble("orbit_meters"), tag.getDouble("orbital_period_seconds"),
                    tag.getDouble("phase_radians"), tag.getDouble("inclination_radians"), tag.getDouble("eccentricity"),
                    getVector(tag, "color_"), tag.getFloat("atmosphere"), tag.getFloat("ring_inner_ratio"),
                    tag.getFloat("ring_outer_ratio"), tag.getDouble("axial_tilt_radians")));
        }
        CosmosSystem system = new CosmosSystem(root.getString("id"), root.getString("name"), root.getLong("seed"),
                enumName(CosmosSystem.Kind.class, root.getString("kind")), getVector(root, "galaxy_"), bodies);
        CelestialSystems.validateCustom(system);
        return system;
    }

    /** Writes version-one explicit fields; validates before writing and never writes an arbitrary NBT payload. */
    public static void write(RegistryFriendlyByteBuf buffer, CosmosSystem system) {
        requireBuffer(buffer);
        CelestialSystems.validateCustom(system);
        buffer.writeVarInt(VERSION);
        buffer.writeUtf(system.id(), 64);
        buffer.writeUtf(system.name(), 96);
        buffer.writeLong(system.seed());
        buffer.writeVarInt(system.kind().ordinal());
        writeVector(buffer, system.galaxyPosition());
        buffer.writeVarInt(system.bodies().size());
        for (CelestialBody body : system.bodies()) {
            buffer.writeUtf(body.id(), 64);
            buffer.writeUtf(body.name(), 96);
            buffer.writeVarInt(body.kind().ordinal());
            buffer.writeDouble(body.radiusMeters());
            buffer.writeDouble(body.orbitMeters());
            buffer.writeDouble(body.orbitalPeriodSeconds());
            buffer.writeDouble(body.phaseRadians());
            buffer.writeDouble(body.inclinationRadians());
            buffer.writeDouble(body.eccentricity());
            writeVector(buffer, body.color());
            buffer.writeFloat(body.atmosphere());
            buffer.writeFloat(body.ringInnerRatio());
            buffer.writeFloat(body.ringOuterRatio());
            buffer.writeDouble(body.axialTiltRadians());
        }
    }

    /** Reads bounded explicit fields; malformed input fails without publishing a partial descriptor. */
    public static CosmosSystem read(RegistryFriendlyByteBuf buffer) {
        requireBuffer(buffer);
        int version = buffer.readVarInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported custom descriptor wire version: " + version);
        }
        String id = buffer.readUtf(64);
        String name = buffer.readUtf(96);
        long seed = buffer.readLong();
        CosmosSystem.Kind kind = enumOrdinal(CosmosSystem.Kind.values(), buffer.readVarInt());
        SpaceVector galaxy = readVector(buffer);
        int count = buffer.readVarInt();
        if (count < 1 || count > CelestialSystems.MAX_BODIES) {
            throw new IllegalArgumentException("Custom descriptor wire body count must be 1..12");
        }
        List<CelestialBody> bodies = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            bodies.add(new CelestialBody(buffer.readUtf(64), buffer.readUtf(96),
                    enumOrdinal(CelestialBody.Kind.values(), buffer.readVarInt()), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), readVector(buffer), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readDouble()));
        }
        CosmosSystem system = new CosmosSystem(id, name, seed, kind, galaxy, bodies);
        CelestialSystems.validateCustom(system);
        return system;
    }

    private static void require(CompoundTag tag, int type, String... keys) {
        if (tag == null) {
            throw new IllegalArgumentException("A custom descriptor tag must not be null");
        }
        for (String key : keys) {
            if (!tag.contains(key, type)) {
                throw new IllegalArgumentException("Missing or invalid custom descriptor field: " + key);
            }
        }
    }

    private static <T extends Enum<T>> T enumName(Class<T> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown custom descriptor " + type.getSimpleName() + ": " + name, invalid);
        }
    }

    private static <T> T enumOrdinal(T[] values, int ordinal) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Custom descriptor kind ordinal exceeds bounds: " + ordinal);
        }
        return values[ordinal];
    }

    private static void putVector(CompoundTag tag, String prefix, SpaceVector value) {
        tag.putDouble(prefix + "x", value.x());
        tag.putDouble(prefix + "y", value.y());
        tag.putDouble(prefix + "z", value.z());
    }

    private static SpaceVector getVector(CompoundTag tag, String prefix) {
        return new SpaceVector(tag.getDouble(prefix + "x"), tag.getDouble(prefix + "y"), tag.getDouble(prefix + "z"));
    }

    private static void requireBuffer(RegistryFriendlyByteBuf buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("A custom descriptor buffer must not be null");
        }
    }

    private static void writeVector(RegistryFriendlyByteBuf buffer, SpaceVector vector) {
        buffer.writeDouble(vector.x());
        buffer.writeDouble(vector.y());
        buffer.writeDouble(vector.z());
    }

    private static SpaceVector readVector(RegistryFriendlyByteBuf buffer) {
        return new SpaceVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }
}
