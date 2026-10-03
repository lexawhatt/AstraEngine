package dev.lexawhatt.astraengine.client.surface;

/** Client connection-lifetime host attachment. Logout discards both the packet listener and its prepared state. */
public interface BoundaryHandoffAccess {
    BoundaryHandoffClient astra$handoff();
}
