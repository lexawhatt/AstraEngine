package dev.lexawhatt.astraengine.api;

import dev.lexawhatt.astraengine.server.SystemCatalog;
import dev.lexawhatt.astraengine.systems.StellarSystem;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;

/** First engine API. All calls require the owning server thread; no client classes are reachable here. */
public final class AstraSystems {
    private AstraSystems() {}

    /** Reads alpha/beta's primary body, without loading its dimension's chunks. */
    public static SystemSnapshot snapshot(MinecraftServer server, String systemId) {
        return SystemCatalog.get(server).system(systemId).snapshot();
    }

    /**
     * Extracts engine resource units from the primary body. The caller supplies a stable operation ID
     * and is responsible for permissions and its own economy. Unknown bodies and invalid amounts throw.
     * Receipts survive normal saves; this does not atomically persist another mod's inventory.
     */
    public static ExtractionResult extract(MinecraftServer server, String systemId, String bodyId,
                                           UUID operationId, long amount) {
        if (!"primary".equals(bodyId)) {
            throw new IllegalArgumentException("The first slice exposes only the primary body");
        }
        SystemCatalog catalog = SystemCatalog.get(server);
        StellarSystem system = catalog.system(systemId);
        SystemSnapshot before = system.snapshot();
        ExtractionResult result = system.extract(operationId, amount);
        if (result.status() == ExtractionResult.Status.APPLIED) {
            catalog.setDirty();
            NeoForge.EVENT_BUS.post(new StellarStateEvent(before, system.snapshot(), StellarStateEvent.Kind.EXTRACTION));
        }
        return result;
    }
}
