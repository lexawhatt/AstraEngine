package dev.lexawhatt.astraengine.rocket;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Namespaced consumer module and its editable data schema; it contains no executable code. */
public record RocketModuleDefinition(String id, String displayName, List<RocketParameterDefinition> parameters) {
    public RocketModuleDefinition {
        RocketModelLimits.requireId(id, "Rocket module ID");
        RocketModelLimits.requireText(displayName, "Module display name", 64);
        if (parameters == null || parameters.size() > RocketModelLimits.MAX_PARAMETERS
                || parameters.stream().anyMatch(parameter -> parameter == null)) {
            throw new IllegalArgumentException("Rocket module parameters must be a bounded non-null list");
        }
        parameters = List.copyOf(parameters);
        Set<String> keys = new HashSet<>();
        for (RocketParameterDefinition parameter : parameters) {
            if (!keys.add(parameter.key())) {
                throw new IllegalArgumentException("Duplicate rocket parameter " + id + "/" + parameter.key());
            }
        }
    }
}
