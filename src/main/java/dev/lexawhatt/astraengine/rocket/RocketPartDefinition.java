package dev.lexawhatt.astraengine.rocket;

import dev.lexawhatt.astraengine.cosmos.SpaceVector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable consumer-defined part, visual, and editable modules; registration supplies its behavior separately. */
public record RocketPartDefinition(String id, String displayName, String category, RocketVisual visual,
                                   SpaceVector defaultSize, List<RocketModuleDefinition> modules) {
    public RocketPartDefinition {
        RocketModelLimits.requireId(id, "Rocket part definition ID");
        RocketModelLimits.requireId(category, "Rocket category ID");
        RocketModelLimits.requireText(displayName, "Part display name", 64);
        RocketPart.validateSize(defaultSize);
        if (visual == null || modules == null || modules.size() > RocketModelLimits.MAX_MODULES
                || modules.stream().anyMatch(module -> module == null)) {
            throw new IllegalArgumentException("Rocket definition requires a visual and bounded non-null modules");
        }
        modules = List.copyOf(modules);
        Set<String> moduleIds = new HashSet<>();
        int parameterCount = 0;
        for (RocketModuleDefinition module : modules) {
            if (!moduleIds.add(module.id())) {
                throw new IllegalArgumentException("Duplicate module " + module.id() + " in " + id);
            }
            parameterCount += module.parameters().size();
        }
        if (parameterCount > RocketModelLimits.MAX_PARAMETERS) {
            throw new IllegalArgumentException("Rocket part definitions support at most 32 total parameters");
        }
    }
}
