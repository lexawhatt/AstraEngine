package dev.lexawhatt.astraengine.client.map;

import java.util.Optional;

/**
 * Client-thread navigation access supplied when opening an engine map. Consumers may retain this handle
 * while connected, including resource reload; after logout it stays invalid even on another connection.
 * Requests never mutate authoritative state. A true result means queued locally, not accepted by the server.
 */
public interface NavigationMapAccess {
    /** Immutable current view, or empty before synchronization and after the owning connection ends. */
    Optional<NavigationMapSnapshot> snapshot();

    /**
     * Requests an action for a canonical system/body ID, or an empty target for SCAN and CANCEL_ROUTE.
     * Invalid IDs, unavailable targets, stale handles and ineligible local state return false.
     * A null action/target throws. Server permissions, rate limits and collision checks still apply.
     */
    boolean request(Action action, String target);

    /** Opens the given map through the same replacement event. False means the connection is no longer available. */
    boolean open(View view);

    /** Distinct default screens; replacement does not change the canonical navigation state. */
    enum View { SYSTEM, ATLAS }

    /** Visual aiming and server-authorized requests; no construction or propulsion statistics. */
    enum Action { SELECT_BODY, AIM_SYSTEM, CHART_ATLAS, APPROACH_BODY, JUMP_SYSTEM, SCAN, CANCEL_ROUTE }
}
