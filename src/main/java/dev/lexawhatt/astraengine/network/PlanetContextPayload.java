package dev.lexawhatt.astraengine.network;

import dev.lexawhatt.astraengine.server.PlanetSurfaceBindings;
import dev.lexawhatt.astraengine.surface.PlanetChart;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-authored complete bounded chart context; client caches are replaced only by newer connection revisions. */
public record PlanetContextPayload(long revision, List<PlanetChart> charts) implements CustomPacketPayload {
    public static final Type<PlanetContextPayload> TYPE = new Type<>(ResourceLocation.parse("astraengine:planet_context"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlanetContextPayload> CODEC = StreamCodec.ofMember((value, buffer) -> {
        buffer.writeVarLong(value.revision()); buffer.writeVarInt(value.charts().size());
        for (var chart : value.charts()) { CubeStorageCharts.write(buffer, chart); }
    }, buffer -> {
        long revision = buffer.readVarLong(); int count = buffer.readVarInt();
        if (count < 0 || count > PlanetSurfaceBindings.MAX_CHARTS) { throw new IllegalArgumentException("Invalid planetary context count"); }
        var charts = new ArrayList<PlanetChart>(count);
        for (int index = 0; index < count; index++) {
            var chart = CubeStorageCharts.read(buffer);
            if (!(chart instanceof PlanetChart planet)) { throw new IllegalArgumentException("Unexpected planetary context chart type"); }
            charts.add(planet);
        }
        return new PlanetContextPayload(revision, charts);
    });

    public PlanetContextPayload {
        if (revision < 0 || charts == null || charts.size() > PlanetSurfaceBindings.MAX_CHARTS) {
            throw new IllegalArgumentException("Invalid planetary context revision or count");
        }
        charts = List.copyOf(charts);
        var dimensions = new HashSet<String>();
        var profiles = new HashMap<String, dev.lexawhatt.astraengine.surface.SolidPlanetProfile>();
        for (var chart : charts) {
            if (!dimensions.add(chart.dimensionId())) { throw new IllegalArgumentException("Duplicate planetary context chart"); }
            var old = profiles.putIfAbsent(chart.profile().bindingKey(), chart.profile());
            if (old != null && !old.equals(chart.profile())) { throw new IllegalArgumentException("Conflicting planetary profiles"); }
        }
    }
    @Override public Type<PlanetContextPayload> type() { return TYPE; }
}
