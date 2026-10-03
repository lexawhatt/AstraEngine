package dev.lexawhatt.astraengine.server.orbit;

import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPage;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalSurface;
import java.util.ArrayList;
import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Strict version-one codec for rebuildable orbital observations; never serializes canonical chunks. */
public final class OrbitalSummaryCodec {
    private OrbitalSummaryCodec() { }

    /** Encodes one immutable chunk or coarse page summary. */
    public static CompoundTag patch(OrbitalPatch patch) {
        CompoundTag tag = surface(patch.surface());
        tag.putInt("x", patch.x()); tag.putInt("z", patch.z()); tag.putInt("level", patch.level());
        tag.putLong("revision", patch.revision());
        int[] data = new int[OrbitalPatch.CELL_COUNT * 4];
        for (int i = 0; i < patch.cells().size(); i++) {
            var cell = patch.cells().get(i);
            data[i * 4] = Float.floatToIntBits(cell.altitudeMeters()); data[i * 4 + 1] = cell.rgb();
            data[i * 4 + 2] = Float.floatToIntBits(cell.emission());
            data[i * 4 + 3] = Float.floatToIntBits(cell.coverage());
        }
        tag.putIntArray("cells", data);
        return tag;
    }

    /** Decodes validated finite values and rejects missing or invalid field types. */
    public static OrbitalPatch patch(CompoundTag tag) {
        require(tag, Tag.TAG_INT, "x", "z", "level"); require(tag, Tag.TAG_LONG, "revision");
        require(tag, Tag.TAG_INT_ARRAY, "cells");
        int[] data = tag.getIntArray("cells");
        if (data.length != OrbitalPatch.CELL_COUNT * 4) { throw new IllegalArgumentException("Invalid orbital cells"); }
        var cells = new ArrayList<OrbitalPatch.Cell>(16);
        for (int i = 0; i < 16; i++) {
            cells.add(new OrbitalPatch.Cell(Float.intBitsToFloat(data[i * 4]), data[i * 4 + 1],
                    Float.intBitsToFloat(data[i * 4 + 2]), Float.intBitsToFloat(data[i * 4 + 3])));
        }
        return new OrbitalPatch(surface(tag), tag.getInt("x"), tag.getInt("z"), tag.getInt("level"),
                tag.getLong("revision"), cells);
    }

    /** Encodes at most 256 chunks under their page identity. */
    public static CompoundTag page(OrbitalPage page) {
        CompoundTag tag = surface(page.key().surface());
        tag.putInt("version", 1); tag.putInt("x", page.key().x()); tag.putInt("z", page.key().z());
        ListTag chunks = new ListTag();
        page.chunks().values().stream().sorted(java.util.Comparator.comparingInt(value -> OrbitalPage.slot(value.x(), value.z())))
                .forEach(value -> chunks.add(patch(value)));
        tag.put("chunks", chunks); return tag;
    }

    /** Rejects foreign keys, duplicate chunks, malformed values and unsupported cache versions. */
    public static OrbitalPage page(CompoundTag tag, OrbitalPage.Key expected) {
        require(tag, Tag.TAG_INT, "version", "x", "z"); require(tag, Tag.TAG_LIST, "chunks");
        var key = new OrbitalPage.Key(surface(tag), tag.getInt("x"), tag.getInt("z"));
        if (tag.getInt("version") != 1 || !key.equals(expected)) {
            throw new IllegalArgumentException("Orbital page identity or version changed");
        }
        ListTag entries = tag.getList("chunks", Tag.TAG_COMPOUND);
        if (entries.size() > 256) { throw new IllegalArgumentException("Orbital page exceeds its chunk budget"); }
        var chunks = new HashMap<Integer, OrbitalPatch>();
        for (int i = 0; i < entries.size(); i++) {
            var patch = patch(entries.getCompound(i));
            if (chunks.put(OrbitalPage.slot(patch.x(), patch.z()), patch) != null) {
                throw new IllegalArgumentException("Duplicate orbital chunk");
            }
        }
        return new OrbitalPage(key, chunks);
    }

    private static CompoundTag surface(OrbitalSurface value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("system", value.systemId()); tag.putString("body", value.bodyId());
        tag.putString("dimension", value.dimensionId()); tag.putString("face", value.face().id());
        tag.putDouble("radius", value.radiusMeters()); tag.putInt("origin", value.altitudeOriginMeters());
        return tag;
    }

    private static OrbitalSurface surface(CompoundTag tag) {
        require(tag, Tag.TAG_STRING, "system", "body", "dimension", "face");
        require(tag, Tag.TAG_DOUBLE, "radius"); require(tag, Tag.TAG_INT, "origin");
        return new OrbitalSurface(tag.getString("system"), tag.getString("body"), tag.getString("dimension"),
                CubeFace.fromId(tag.getString("face")), tag.getDouble("radius"), tag.getInt("origin"));
    }

    private static void require(CompoundTag tag, int type, String... names) {
        if (tag == null) { throw new IllegalArgumentException("Orbital cache data is required"); }
        for (String name : names) {
            if (!tag.contains(name, type)) { throw new IllegalArgumentException("Missing orbital cache field: " + name); }
        }
    }
}
