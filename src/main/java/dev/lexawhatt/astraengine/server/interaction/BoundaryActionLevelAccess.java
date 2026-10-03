package dev.lexawhatt.astraengine.server.interaction;

/** Level-lifetime slot used only during one synchronous canonical action; empty during ticks and world IO. */
public interface BoundaryActionLevelAccess {
    BoundaryActionScope astra$levelAction();
    void astra$levelAction(BoundaryActionScope scope);
}
