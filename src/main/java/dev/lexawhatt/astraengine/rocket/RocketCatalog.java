package dev.lexawhatt.astraengine.rocket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable connection/startup-owned part definitions, passed explicitly to all schema-dependent operations. */
public final class RocketCatalog {
    private final List<RocketPartDefinition> definitions;
    private final Map<String, RocketPartDefinition> byId;

    /** Copies up to 256 definitions and rejects duplicate IDs or incompatible standard module schemas. */
    public RocketCatalog(List<RocketPartDefinition> definitions) {
        if (definitions == null || definitions.size() > RocketModelLimits.MAX_DEFINITIONS
                || definitions.stream().anyMatch(definition -> definition == null)) {
            throw new IllegalArgumentException("Rocket catalog requires at most 256 non-null definitions");
        }
        this.definitions = List.copyOf(definitions);
        Map<String, RocketPartDefinition> index = new LinkedHashMap<>();
        for (RocketPartDefinition definition : definitions) {
            if (index.put(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate rocket definition " + definition.id());
            }
            for (RocketModuleDefinition module : definition.modules()) {
                RocketStandardModules.validate(module);
            }
        }
        byId = Collections.unmodifiableMap(index);
    }

    /** Immutable registration-order definitions used by generic editors and connection synchronization. */
    public List<RocketPartDefinition> definitions() {
        return definitions;
    }

    /** Resolves an exact namespaced definition ID, throwing when the consumer definition is unavailable. */
    public RocketPartDefinition requireDefinition(String id) {
        return findDefinition(id).orElseThrow(() -> new IllegalArgumentException("Unknown rocket definition " + id));
    }

    /** Looks up a valid ID without generating fallback content; malformed and null IDs are rejected. */
    public Optional<RocketPartDefinition> findDefinition(String id) {
        RocketModelLimits.requireId(id, "Rocket definition lookup ID");
        return Optional.ofNullable(byId.get(id));
    }

    /** Complete immutable initial values. Saved instances retain their own values when later defaults differ. */
    public Map<String, Map<String, RocketValue>> defaultValues(String definitionId) {
        Map<String, Map<String, RocketValue>> values = new LinkedHashMap<>();
        for (RocketModuleDefinition module : requireDefinition(definitionId).modules()) {
            Map<String, RocketValue> parameters = new LinkedHashMap<>();
            for (RocketParameterDefinition parameter : module.parameters()) {
                parameters.put(parameter.key(), parameter.defaultValue());
            }
            values.put(module.id(), Collections.unmodifiableMap(parameters));
        }
        return Collections.unmodifiableMap(values);
    }

    /** Rejects missing definitions, missing/extra module fields, and values outside the registered schema. */
    public void validate(RocketBlueprint blueprint) {
        if (blueprint == null) {
            throw new IllegalArgumentException("Rocket blueprint must not be null");
        }
        for (RocketPart part : blueprint.parts()) {
            validate(part);
        }
    }

    /** Validates one immutable part against this catalog without mutating it or filling missing fields. */
    public void validate(RocketPart part) {
        if (part == null) {
            throw new IllegalArgumentException("Rocket part must not be null");
        }
        RocketPartDefinition definition = requireDefinition(part.definitionId());
        if (part.moduleValues().size() != definition.modules().size()) {
            throw new IllegalArgumentException("Rocket part " + part.id() + " has mismatched modules");
        }
        for (RocketModuleDefinition module : definition.modules()) {
            Map<String, RocketValue> values = part.moduleValues().get(module.id());
            if (values == null || values.size() != module.parameters().size()) {
                throw new IllegalArgumentException("Rocket part " + part.id() + " has mismatched module fields");
            }
            for (RocketParameterDefinition parameter : module.parameters()) {
                parameter.validate(values.get(parameter.key()));
            }
            RocketStandardModules.validateValues(module.id(), values);
        }
    }
}
