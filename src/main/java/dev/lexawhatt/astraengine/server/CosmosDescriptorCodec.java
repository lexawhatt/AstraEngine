package dev.lexawhatt.astraengine.server;

import dev.lexawhatt.astraengine.api.celestial.CelestialSystems;
import dev.lexawhatt.astraengine.cosmos.CelestialBody;
import dev.lexawhatt.astraengine.cosmos.CosmosSystem;
import dev.lexawhatt.astraengine.cosmos.SpaceVector;
import io.netty.buffer.ByteBufUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;

/**
 * Strict version-two custom descriptor persistence and bounded wire encoding, with version-one NBT migration.
 * Uses no logical-side state or client-only classes. Callers own tags and buffers; returned descriptors are immutable.
 * Invalid or missing fields, unknown versions/kinds and unsupported custom bounds throw before a descriptor is returned.
 */
public final class CosmosDescriptorCodec {
    public static final int VERSION = 2;
    /** Aggregate descriptor wire budget, leaving headroom below the host's one-MiB clientbound payload limit. */
    public static final int MAX_SNAPSHOT_BYTES = 900 * 1024;

    private CosmosDescriptorCodec() {
    }

    /** Exact explicit wire length, including the descriptor version and each UTF-8 byte-length prefix. */
    public static int encodedBytes(CosmosSystem system) {
        CelestialSystems.validateCustom(system);
        int bytes = VarInt.getByteSize(VERSION) + stringBytes(system.id()) + stringBytes(system.name())
                + 8 + VarInt.getByteSize(system.kind().ordinal()) + 24 + VarInt.getByteSize(system.bodies().size());
        for (CelestialBody body : system.bodies()) {
            bytes += stringBytes(body.id()) + stringBytes(body.name()) + VarInt.getByteSize(body.kind().ordinal())
                    + 48 + 24 + 12 + 8 + stringBytes(body.parentId());
        }
        return bytes;
    }

    /** Exact aggregate wire size, including its leading system count; all values must be valid custom systems. */
    public static int snapshotBytes(Collection<CosmosSystem> systems) {
        if (systems == null) { throw new IllegalArgumentException("A descriptor collection is required"); }
        int bytes = VarInt.getByteSize(systems.size());
        for (CosmosSystem system : systems) { bytes = Math.addExact(bytes, encodedBytes(system)); }
        return bytes;
    }

    private static int stringBytes(String value) {
        int bytes = ByteBufUtil.utf8Bytes(value);
        return VarInt.getByteSize(bytes) + bytes;
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
            tag.putString("parent_id", body.parentId());
            bodies.add(tag);
        }
        root.put("bodies", bodies);
        return root;
    }

    /** Reads v1 origin-relative or v2 parent-relative NBT without changing the input. V2 requires parent_id. */
    public static CosmosSystem decode(CompoundTag root) {
        require(root, Tag.TAG_INT, "version");
        require(root, Tag.TAG_STRING, "id", "name", "kind");
        require(root, Tag.TAG_LONG, "seed");
        require(root, Tag.TAG_DOUBLE, "galaxy_x", "galaxy_y", "galaxy_z");
        require(root, Tag.TAG_LIST, "bodies");
        int version = root.getInt("version");
        if (version != 1 && version != VERSION) {
            throw new IllegalArgumentException("Unsupported custom descriptor version: " + root.getInt("version"));
        }
        ListTag entries = (ListTag) root.get("bodies");
        if (entries.isEmpty() || entries.size() > (version == 1 ? 12 : CelestialSystems.MAX_BODIES)
                || entries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Custom descriptor bodies exceed their versioned count or compound type");
        }
        List<CelestialBody> bodies = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            require(tag, Tag.TAG_STRING, "id", "name", "kind");
            require(tag, Tag.TAG_DOUBLE, "radius_meters", "orbit_meters", "orbital_period_seconds", "phase_radians",
                    "inclination_radians", "eccentricity", "color_x", "color_y", "color_z", "axial_tilt_radians");
            require(tag, Tag.TAG_FLOAT, "atmosphere", "ring_inner_ratio", "ring_outer_ratio");
            if (version >= 2) {
                require(tag, Tag.TAG_STRING, "parent_id");
            } else if (tag.contains("parent_id")) {
                throw new IllegalArgumentException("Version-one descriptors cannot contain a parent field");
            }
            bodies.add(new CelestialBody(tag.getString("id"), tag.getString("name"),
                    enumName(CelestialBody.Kind.class, tag.getString("kind")), tag.getDouble("radius_meters"),
                    tag.getDouble("orbit_meters"), tag.getDouble("orbital_period_seconds"),
                    tag.getDouble("phase_radians"), tag.getDouble("inclination_radians"), tag.getDouble("eccentricity"),
                    getVector(tag, "color_"), tag.getFloat("atmosphere"), tag.getFloat("ring_inner_ratio"),
                    tag.getFloat("ring_outer_ratio"), tag.getDouble("axial_tilt_radians"),
                    version >= 2 ? tag.getString("parent_id") : ""));
        }
        CosmosSystem system = new CosmosSystem(root.getString("id"), root.getString("name"), root.getLong("seed"),
                enumName(CosmosSystem.Kind.class, root.getString("kind")), getVector(root, "galaxy_"), bodies);
        CelestialSystems.validateCustom(system);
        return system;
    }

    /** Writes version-two explicit fields; validates before writing and never writes an arbitrary NBT payload. */
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
            buffer.writeUtf(body.parentId(), 64);
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
            throw new IllegalArgumentException("Custom descriptor wire body count must be 1..64");
        }
        List<CelestialBody> bodies = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            bodies.add(new CelestialBody(buffer.readUtf(64), buffer.readUtf(96),
                    enumOrdinal(CelestialBody.Kind.values(), buffer.readVarInt()), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), readVector(buffer), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readDouble(), buffer.readUtf(64)));
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
