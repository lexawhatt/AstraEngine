package dev.lexawhatt.astraengine.client.surface.orbit;

import dev.lexawhatt.astraengine.network.OrbitalSummaryReceivedEvent;
import dev.lexawhatt.astraengine.surface.orbit.OrbitalPatch;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Connection-owned bounded observation transaction. Reload retains CPU data; logout discards both published and pending data. */
public final class OrbitalSummaryClient {
    public static final int MAX_PATCHES = 4096;
    private List<OrbitalPatch> patches = List.of();
    private final ArrayList<OrbitalPatch> pending = new ArrayList<>();
    private long pendingEpoch;
    private long revision;

    /** Accepts only increasing, complete server transactions; partial streams never replace the visible cache. */
    public void receive(OrbitalSummaryReceivedEvent event) {
        var payload = event.payload();
        if (payload.epoch() <= revision || payload.epoch() < pendingEpoch) { return; }
        if (payload.reset()) { pending.clear(); pendingEpoch = payload.epoch(); }
        else if (payload.epoch() != pendingEpoch) { return; }
        if (pending.size() + payload.patches().size() > MAX_PATCHES) {
            pending.clear(); pendingEpoch = 0;
            throw new IllegalArgumentException("Orbital transaction exceeds its connection budget");
        }
        pending.addAll(payload.patches());
        if (payload.complete()) {
            patches = List.copyOf(pending); revision = pendingEpoch; pending.clear(); pendingEpoch = 0;
        }
    }
    /** Immutable published observations, bounded independently of how much canonical terrain was explored. */
    public List<OrbitalPatch> patches() { return patches; }
    /** Changes only after an atomic completed transport transaction. */
    public long revision() { return revision; }
    /** Clears data from the previous server before a new connection can render. */
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        patches = List.of(); pending.clear(); pendingEpoch = 0; revision = 0;
    }
}
