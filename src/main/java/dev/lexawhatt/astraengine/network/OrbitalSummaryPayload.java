package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.surface.CubeFace;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalSurface;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded server-to-client transaction of derived orbital observations; no client request grants storage access. */
public record OrbitalSummaryPayload(long epoch, boolean reset, boolean complete, List<OrbitalPatch> patches)
        implements CustomPacketPayload {
    public static final int MAX_PATCHES = 96;
    public static final int MAX_BYTES = 96 * 1024;
    public static final Type<OrbitalSummaryPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:orbital_summary"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OrbitalSummaryPayload> CODEC = StreamCodec.ofMember(
            OrbitalSummaryPayload::write, OrbitalSummaryPayload::read);
    public OrbitalSummaryPayload {
        if (epoch < 1 || patches == null || patches.size() > MAX_PATCHES) {
            throw new IllegalArgumentException("Invalid orbital summary transaction");
        }
        patches = List.copyOf(patches);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        int start = buffer.writerIndex(); buffer.writeLong(epoch); buffer.writeBoolean(reset); buffer.writeBoolean(complete);
        buffer.writeVarInt(patches.size());
        for (var patch : patches) {
            var surface = patch.surface();
            buffer.writeUtf(surface.systemId(), 128); buffer.writeUtf(surface.bodyId(), 128);
            buffer.writeUtf(surface.dimensionId(), 256); buffer.writeUtf(surface.face().id(), 2);
            buffer.writeDouble(surface.radiusMeters()); buffer.writeInt(surface.altitudeOriginMeters());
            buffer.writeInt(patch.x()); buffer.writeInt(patch.z()); buffer.writeByte(patch.level()); buffer.writeLong(patch.revision());
            for (var cell : patch.cells()) {
                buffer.writeFloat(cell.altitudeMeters()); buffer.writeInt(cell.rgb());
                buffer.writeFloat(cell.emission()); buffer.writeFloat(cell.coverage());
            }
        }
        if (buffer.writerIndex() - start > MAX_BYTES) { throw new IllegalArgumentException("Orbital summary exceeds its wire budget"); }
    }

    private static OrbitalSummaryPayload read(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > MAX_BYTES) { throw new IllegalArgumentException("Oversize orbital summary payload"); }
        long epoch = buffer.readLong(); boolean reset = buffer.readBoolean(), complete = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_PATCHES) { throw new IllegalArgumentException("Invalid orbital patch count"); }
        var patches = new ArrayList<OrbitalPatch>(count);
        for (int i = 0; i < count; i++) {
            var surface = new OrbitalSurface(buffer.readUtf(128), buffer.readUtf(128), buffer.readUtf(256),
                    CubeFace.fromId(buffer.readUtf(2)), buffer.readDouble(), buffer.readInt());
            int x = buffer.readInt(), z = buffer.readInt(), level = buffer.readUnsignedByte(); long revision = buffer.readLong();
            var cells = new ArrayList<OrbitalPatch.Cell>(16);
            for (int cell = 0; cell < 16; cell++) {
                cells.add(new OrbitalPatch.Cell(buffer.readFloat(), buffer.readInt(), buffer.readFloat(), buffer.readFloat()));
            }
            patches.add(new OrbitalPatch(surface, x, z, level, revision, cells));
        }
        return new OrbitalSummaryPayload(epoch, reset, complete, patches);
    }
    @Override public Type<OrbitalSummaryPayload> type() { return TYPE; }
}
