package dev.lexawhatt.astraengine.server.interaction;

/** Entity-owned transient action context. It must be null outside one synchronous canonical host callback. */
public interface BoundaryActionAccess {
    BoundaryActionScope astra$actionScope();
    void astra$actionScope(BoundaryActionScope scope);
}
