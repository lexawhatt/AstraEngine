package dev.lexawhatt.astraengine.api.rocket;

import dev.lexawhatt.astraengine.rocket.RocketCatalog;
import dev.lexawhatt.astraengine.rocket.RocketPartDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * One synchronous native mod-bus registration phase during common setup, before worlds exist.
 * Consumers register immutable part definitions with reusable typed module schemas on both distributions.
 * Registration freezes afterwards; this is a definition catalog, not a runtime gameplay/plugin registry.
 */
public final class RegisterRocketPartsEvent extends Event implements IModBusEvent {
    private final Map<String, RocketPartDefinition> definitions = new LinkedHashMap<>();
    private boolean frozen;
    public RegisterRocketPartsEvent(List<RocketPartDefinition> builtins) { builtins.forEach(this::register); }

    /** Adds one namespaced definition; duplicate IDs, null input and more than 256 definitions fail setup. */
    public void register(RocketPartDefinition definition) {
        if (frozen) { throw new IllegalStateException("Rocket part registration is already frozen"); }
        if (definition == null || definitions.size() >= 256 || definitions.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException("Invalid, duplicate or excessive rocket part registration");
        }
    }

    /** Completes native setup and returns the immutable validated catalog; later registrations are rejected. */
    public RocketCatalog freeze() {
        if (frozen) { throw new IllegalStateException("Rocket part registration is already frozen"); }
        RocketCatalog result = new RocketCatalog(new ArrayList<>(definitions.values())); frozen = true; return result;
    }
}
